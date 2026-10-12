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
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 文档摄入管线：解析 → 切块 → 向量化 → 入库。异步执行，并发数受限以免压垮本地 embedding 服务。
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
    private final ExecutorService executor;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public IngestionService(DocumentRepository documents, ChunkRepository chunks, FileStorage fileStorage,
                            DocumentParser parser, Chunker chunker, EmbeddingModel embeddingModel,
                            TransactionTemplate transactionTemplate, SkbProperties properties) {
        this.documents = documents;
        this.chunks = chunks;
        this.fileStorage = fileStorage;
        this.parser = parser;
        this.chunker = chunker;
        this.embeddingModel = embeddingModel;
        this.transactionTemplate = transactionTemplate;
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

    /** 应用重启后，继续处理上次未完成的文档。 */
    @EventListener(ApplicationReadyEvent.class)
    public void resumeUnfinished() {
        List<Long> ids = documents.findIdsByStatus(List.of(
                DocumentStatus.PENDING.name(), DocumentStatus.PARSING.name(), DocumentStatus.EMBEDDING.name()));
        if (!ids.isEmpty()) {
            log.info("恢复 {} 个未完成的文档处理任务", ids.size());
            ids.forEach(this::submit);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private void process(long documentId) {
        Document doc = documents.findById(documentId).orElse(null);
        if (doc == null) {
            return;
        }
        long start = System.currentTimeMillis();
        try {
            documents.updateStatus(documentId, DocumentStatus.PARSING, null);
            List<TextUnit> units = parser.parse(fileStorage.resolve(doc.filePath()), doc.filename());
            List<ChunkDraft> drafts = chunker.chunk(units, DocType.fromFilename(doc.filename()));
            if (drafts.isEmpty()) {
                throw new IllegalStateException("未从文件中提取到文字内容（扫描件或纯图片需要 OCR，当前版本暂不支持）");
            }

            documents.updateStatus(documentId, DocumentStatus.EMBEDDING, null);
            List<float[]> embeddings = embed(doc.filename(), drafts);

            transactionTemplate.executeWithoutResult(status -> {
                chunks.deleteByDocument(documentId);
                chunks.insertAll(documentId, doc.kbId(), drafts, embeddings);
                documents.markReady(documentId, drafts.size());
            });
            log.info("文档处理完成：{}（{} 个片段，耗时 {} ms）", doc.filename(), drafts.size(),
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("文档处理失败：{}", doc.filename(), e);
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

    private List<float[]> embed(String filename, List<ChunkDraft> drafts) {
        List<float[]> result = new ArrayList<>(drafts.size());
        for (int from = 0; from < drafts.size(); from += embeddingBatchSize) {
            List<String> texts = drafts.subList(from, Math.min(from + embeddingBatchSize, drafts.size())).stream()
                    .map(d -> embeddingText(filename, d))
                    .toList();
            result.addAll(embeddingModel.embed(texts));
        }
        return result;
    }

    /** 把文件名和章节路径拼进向量文本，"员工手册里的年假规定"这类问题更容易命中。 */
    static String embeddingText(String filename, ChunkDraft draft) {
        StringJoiner header = new StringJoiner("\n");
        header.add("文件：" + filename);
        if (draft.headingPath() != null) {
            header.add("章节：" + draft.headingPath());
        }
        if (draft.location() != null) {
            header.add("位置：" + draft.location());
        }
        return header + "\n\n" + draft.content();
    }
}
