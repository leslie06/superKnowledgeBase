package flyingFish.ai.superKnowledgeBase.ingestion;

import flyingFish.ai.superKnowledgeBase.common.SkbProperties;
import flyingFish.ai.superKnowledgeBase.document.Document;
import flyingFish.ai.superKnowledgeBase.document.DocumentRepository;
import flyingFish.ai.superKnowledgeBase.document.DocumentStatus;
import flyingFish.ai.superKnowledgeBase.document.FileStorage;
import flyingFish.ai.superKnowledgeBase.ingestion.Chunker.ChunkDraft;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 文档摄入管线：解析 → 切块 → 生成文档摘要 → 向量化 → 入库。异步执行，并发数受限以免压垮本地 embedding 服务。
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final int MAX_ERROR_LENGTH = 500;

    private final DocumentRepository documents;
    private final ChunkRepository chunks;
    private final FileStorage fileStorage;
    private final DocumentParser parser;
    private final Chunker chunker;
    private final EmbeddingModel embeddingModel;
    private final TransactionTemplate transactionTemplate;
    private final int embeddingBatchSize;
    private final OcrService ocr;
    private final DocumentSummarizer summarizer;
    private final ChapterSummarizer chapterSummarizer;
    private final ExecutorService executor;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    /** 应用正在关闭：此时处理中断不算失败，保持原状态，下次启动后继续 */
    private volatile boolean stopping;

    public IngestionService(DocumentRepository documents, ChunkRepository chunks, FileStorage fileStorage,
                            DocumentParser parser, Chunker chunker, EmbeddingModel embeddingModel,
                            TransactionTemplate transactionTemplate, OcrService ocr, DocumentSummarizer summarizer,
                            ChapterSummarizer chapterSummarizer,
                            SkbProperties properties) {
        this.documents = documents;
        this.chunks = chunks;
        this.fileStorage = fileStorage;
        this.parser = parser;
        this.chunker = chunker;
        this.embeddingModel = embeddingModel;
        this.transactionTemplate = transactionTemplate;
        this.ocr = ocr;
        this.summarizer = summarizer;
        this.chapterSummarizer = chapterSummarizer;
        this.embeddingBatchSize = properties.ingestion().embeddingBatchSize();
        this.executor = Executors.newFixedThreadPool(properties.ingestion().concurrency(),
                Thread.ofVirtual().name("ingest-", 0).factory());
    }

    public void submit(long documentId) {
        if (!inFlight.add(documentId)) {
            throw new IllegalStateException("文档正在处理中，请稍后再试");
        }
        executor.execute(() -> {
            try {
                process(documentId);
            } finally {
                inFlight.remove(documentId);
            }
        });
    }

    public boolean isProcessing(long documentId) {
        return inFlight.contains(documentId);
    }

    boolean isStopping() {
        return stopping;
    }

    /**
     * 为已处理完成的文档补生成摘要片段，不重新解析和向量化正文。由 {@link SummaryBackfill} 在后台逐个调用；
     * 与正常处理共用"处理中"标记，同一文档不会同时被重新处理和补摘要。
     *
     * @return 是否写入了摘要片段
     */
    boolean addSummary(long documentId) {
        if (!inFlight.add(documentId)) {
            return false;
        }
        try {
            Document doc = documents.findById(documentId).orElse(null);
            if (doc == null || !DocumentStatus.READY.name().equals(doc.status())) {
                return false;
            }
            String source = source(doc);
            List<ChunkDraft> drafts = chunks.findTextByDocument(documentId).stream()
                    .map(c -> new ChunkDraft(c.content(), c.headingPath(), c.location()))
                    .toList();
            String summary = summary(doc, source, drafts);
            if (summary == null) {
                return false;
            }
            float[] embedding = embeddingModel.embed(summaryEmbeddingText(source, summary));
            transactionTemplate.executeWithoutResult(status -> {
                chunks.deleteSummary(documentId);
                chunks.insertSummary(documentId, doc.kbId(), source, summary, embedding);
            });
            return true;
        } finally {
            inFlight.remove(documentId);
        }
    }

    /** 应用重启后，继续处理上次未完成的文档。 */
    @EventListener(ApplicationReadyEvent.class)
    public void resumeUnfinished() {
        List<Long> ids = documents.findIdsByStatus(List.of(
                DocumentStatus.PENDING.name(), DocumentStatus.PARSING.name(), DocumentStatus.OCR.name(),
                DocumentStatus.EMBEDDING.name()));
        if (!ids.isEmpty()) {
            log.info("恢复 {} 个未完成的文档处理任务", ids.size());
            ids.forEach(this::submit);
        }
    }

    @PreDestroy
    public void shutdown() {
        stopping = true;
        executor.shutdownNow();
    }

    private void process(long documentId) {
        Document found = documents.findById(documentId).orElse(null);
        if (found == null) {
            return;
        }
        long start = System.currentTimeMillis();
        try {
            documents.markParsing(documentId);
            Document doc = checkLocalFile(found);
            List<TextUnit> units = parser.parse(fileStorage.locate(doc), doc.filename(), doc.sha256(),
                    (done, total) -> documents.updateOcrProgress(documentId, done, total));
            List<ChunkDraft> drafts = chunker.chunk(units, DocType.fromFilename(doc.filename()));
            if (drafts.isEmpty()) {
                throw new IllegalStateException(ocr.available()
                        ? "未从文件中提取到文字内容，文字识别也没有识别出文字"
                        : "未从文件中提取到文字内容（扫描件需要文字识别，请先编译 scripts/ocr/vision_ocr.swift，见 README）");
            }

            documents.updateStatus(documentId, DocumentStatus.EMBEDDING, null);
            String source = source(doc);
            String summary = summary(doc, source, drafts);
            List<float[]> embeddings = embed(source, drafts);
            float[] summaryEmbedding = summary == null ? null : embeddingModel.embed(summaryEmbeddingText(source, summary));

            transactionTemplate.executeWithoutResult(status -> {
                chunks.deleteByDocument(documentId);
                chunks.insertAll(documentId, doc.kbId(), drafts, embeddings);
                if (summary != null) {
                    chunks.insertSummary(documentId, doc.kbId(), source, summary, summaryEmbedding);
                }
                documents.markReady(documentId, drafts.size());
            });
            log.info("文档处理完成：{}（{} 个片段，耗时 {} ms）", doc.filename(), drafts.size(),
                    System.currentTimeMillis() - start);
            chapterSummarizer.submit(documentId);
        } catch (Exception e) {
            if (stopping) {
                log.info("应用关闭，文档下次启动后继续处理：{}", found.filename());
                return;
            }
            log.warn("文档处理失败：{}", found.filename(), e);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            try {
                documents.updateStatus(documentId, DocumentStatus.FAILED,
                        message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message);
            } catch (Exception updateError) {
                // 处理过程中文档被删除等情况
                log.debug("更新文档状态失败：{}", documentId, updateError);
            }
        }
    }

    /**
     * 引用本机原文件的文档：原文件找不到时报错（旧片段保留，照样能检索）；内容变了（被替换成新版本）时更新指纹、清掉摘要，
     * 按新版本处理，文字识别缓存也按新指纹查。复制保存的文档原样返回。
     */
    private Document checkLocalFile(Document doc) throws IOException {
        if (doc.localPath() == null) {
            return doc;
        }
        Path path = Path.of(doc.localPath());
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("原文件不存在：" + path + "（移动过的话，可在文档管理「修改文件夹位置」或重新运行导入脚本）");
        }
        String sha = FileStorage.sha256(path);
        if (sha.equals(doc.sha256())) {
            return doc;
        }
        try {
            documents.updateLocalVersion(doc.id(), sha, Files.size(path));
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("原文件内容已变化，且与知识库中另一个文档相同：" + path);
        }
        log.info("原文件内容已变化，按新版本处理：{}", path);
        return documents.findById(doc.id()).orElseThrow();
    }

    /** 文件夹导入的文件用相对路径（目录名就是分类），否则用文件名 */
    private static String source(Document doc) {
        return doc.sourcePath() != null ? doc.sourcePath() : doc.filename();
    }

    /**
     * 摘要只取决于文件内容，重新处理时复用已有的；文件换成新版本时 replaceFile 已把它清空。
     * 短文档不需要摘要片段（见 skb.summary.min-chunks），返回 null。
     */
    private String summary(Document doc, String source, List<ChunkDraft> drafts) {
        if (drafts.size() < summarizer.minChunks()) {
            return null;
        }
        if (doc.summary() != null) {
            return doc.summary();
        }
        String summary = summarizer.summarize(source, drafts).orElse(null);
        if (summary != null) {
            documents.updateSummary(doc.id(), summary);
        }
        return summary;
    }

    private static String summaryEmbeddingText(String source, String summary) {
        return embeddingText(source, new ChunkDraft(summary, ChunkRepository.SUMMARY_HEADING, null));
    }

    private List<float[]> embed(String source, List<ChunkDraft> drafts) {
        List<float[]> result = new ArrayList<>(drafts.size());
        for (int from = 0; from < drafts.size(); from += embeddingBatchSize) {
            List<String> texts = drafts.subList(from, Math.min(from + embeddingBatchSize, drafts.size())).stream()
                    .map(d -> embeddingText(source, d))
                    .toList();
            result.addAll(embeddingModel.embed(texts));
        }
        return result;
    }

    /**
     * 把文件出处和章节路径拼进向量文本，"员工手册里的年假规定"这类问题更容易命中。
     *
     * @param source 文件夹导入的文件是相对路径（目录名就是分类，如"人事制度/2024/考勤管理办法.docx"），否则是文件名
     */
    static String embeddingText(String source, ChunkDraft draft) {
        StringJoiner header = new StringJoiner("\n");
        header.add("文件：" + source);
        if (draft.headingPath() != null) {
            header.add("章节：" + draft.headingPath());
        }
        if (draft.location() != null) {
            header.add("位置：" + draft.location());
        }
        return header + "\n\n" + draft.content();
    }
}
