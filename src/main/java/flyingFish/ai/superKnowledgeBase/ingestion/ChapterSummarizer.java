package flyingFish.ai.superKnowledgeBase.ingestion;

import flyingFish.ai.superKnowledgeBase.common.SkbProperties;
import flyingFish.ai.superKnowledgeBase.document.Document;
import flyingFish.ai.superKnowledgeBase.document.DocumentRepository;
import flyingFish.ai.superKnowledgeBase.document.DocumentStatus;
import flyingFish.ai.superKnowledgeBase.ingestion.ChunkRepository.ChunkView;
import flyingFish.ai.superKnowledgeBase.ingestion.Chunker.ChunkDraft;
import flyingFish.ai.superKnowledgeBase.retrieval.Retriever;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 章节摘要：为正文较长的章节（第X章 / 第X回 / 第X篇）各生成一段摘要，作为 kind = CHAPTER 的片段参与检索。
 * 情节类问题的答案在某一回里，原文的用词和问法对不上："西门庆死在谁的床上"，原文写的是"贪欲丧命""三丸药"，
 * 检索不到；第七十九回的摘要写明"因潘金莲灌下胡僧药纵欲而死"，回目本身也点明了情节，首轮检索就能命中。
 * <p>
 * 一本书上百个章节，每个调用一次 LLM，所以不放在摄入流程里：文档处理完成后提交到这里，在后台按
 * skb.summary.chapter-concurrency 并发生成，每生成一个就写入一个片段，中途重启不丢已完成的；启动时检查所有文档补齐。
 * 摘要原文按文件内容和章节路径缓存在 chapter_summary，重新处理时只需要重新向量化。
 */
@Service
public class ChapterSummarizer {

    /** 章节一级的标题："第1章 甄士隐梦幻识通灵 贾雨村风尘怀闺秀""第七十九回 西门庆贪欲丧命""第一囬""Chapter 3 Indexing" */
    private static final Pattern CHAPTER_SEGMENT = Pattern.compile(
            "^(?:第[一二三四五六七八九十百千零〇\\d]+[章回囘囬篇]|Chapter\\s+\\d+)(?:[\\s:：.、].*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final String SEPARATOR = " > ";

    private static final Logger log = LoggerFactory.getLogger(ChapterSummarizer.class);

    /** 一个章节：章节路径（截到"第X章"为止）和它的正文片段 */
    record Chapter(String heading, List<ChunkView> chunks) {

        int chars() {
            return chunks.stream().mapToInt(c -> c.content().length()).sum();
        }

        String location() {
            return Retriever.mergeLocations(chunks.getFirst().location(), chunks.getLast().location());
        }
    }

    private final DocumentRepository documents;
    private final ChunkRepository chunks;
    private final DocumentSummarizer summarizer;
    private final EmbeddingModel embeddingModel;
    private final boolean enabled;
    private final int minChars;
    private final Semaphore permits;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    /** 正在生成的文档；生成中又被提交（重新处理完成）的记入 rerun，完成后再跑一遍 */
    private final Set<Long> active = ConcurrentHashMap.newKeySet();
    private final Set<Long> rerun = ConcurrentHashMap.newKeySet();
    private volatile boolean stopping;

    public ChapterSummarizer(DocumentRepository documents, ChunkRepository chunks, DocumentSummarizer summarizer,
                             EmbeddingModel embeddingModel, SkbProperties properties) {
        this.documents = documents;
        this.chunks = chunks;
        this.summarizer = summarizer;
        this.embeddingModel = embeddingModel;
        this.enabled = properties.summary().chapterEnabled();
        this.minChars = properties.summary().chapterMinChars();
        this.permits = new Semaphore(Math.max(1, properties.summary().chapterConcurrency()));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        if (enabled && summarizer.available()) {
            executor.submit(() -> chunks.findReadyDocuments().forEach(this::submit));
        }
    }

    @PreDestroy
    public void shutdown() {
        stopping = true;
        executor.shutdownNow();
    }

    /** 文档处理完成后调用：补齐缺少的章节摘要，删掉已不存在的章节的摘要 */
    public void submit(long documentId) {
        if (!enabled || !summarizer.available() || stopping) {
            return;
        }
        if (!active.add(documentId)) {
            rerun.add(documentId);
            return;
        }
        executor.submit(() -> {
            try {
                summarize(documentId);
            } catch (Exception e) {
                if (!stopping) {
                    log.warn("生成章节摘要失败，文档 {}：{}", documentId, e.getMessage());
                }
            } finally {
                active.remove(documentId);
                if (rerun.remove(documentId)) {
                    submit(documentId);
                }
            }
        });
    }

    private void summarize(long documentId) throws InterruptedException {
        Document doc = documents.findById(documentId).orElse(null);
        if (doc == null || !DocumentStatus.READY.name().equals(doc.status())) {
            return;
        }
        List<Chapter> wanted = chapters(chunks.findTextByDocument(documentId)).stream()
                .filter(c -> c.chars() >= minChars)
                .toList();
        Set<String> existing = chunks.findChapterSummaryHeadings(documentId);
        Set<String> wantedHeadings = wanted.stream().map(Chapter::heading).collect(Collectors.toSet());
        chunks.deleteChapterSummaries(documentId, existing.stream().filter(h -> !wantedHeadings.contains(h)).toList());
        String source = doc.sourcePath() != null ? doc.sourcePath() : doc.filename();
        List<Future<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < wanted.size(); i++) {
            Chapter chapter = wanted.get(i);
            if (!existing.contains(chapter.heading())) {
                int chunkIndex = -2 - i;
                tasks.add(executor.submit(() -> summarizeChapter(doc, source, chapter, chunkIndex)));
            }
        }
        if (tasks.isEmpty()) {
            return;
        }
        log.info("开始生成章节摘要：{}，{} 个章节", source, tasks.size());
        AtomicInteger added = new AtomicInteger();
        for (Future<Boolean> task : tasks) {
            try {
                if (task.get()) {
                    added.incrementAndGet();
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    throw (InterruptedException) e;
                }
                log.warn("生成章节摘要失败：{}：{}", source, e.getMessage());
            }
        }
        log.info("章节摘要生成完成：{}，{} 个章节中 {} 个成功", source, tasks.size(), added.get());
    }

    private boolean summarizeChapter(Document doc, String source, Chapter chapter, int chunkIndex)
            throws InterruptedException {
        if (stopping) {
            return false;
        }
        String summary = chunks.findCachedChapterSummary(doc.sha256(), chapter.heading()).orElse(null);
        if (summary == null) {
            List<ChunkDraft> drafts = chapter.chunks().stream()
                    .map(c -> new ChunkDraft(c.content(), c.headingPath(), c.location()))
                    .toList();
            permits.acquire();
            try {
                summary = summarizer.summarizeChapter(source, chapter.heading(), drafts).orElse(null);
            } finally {
                permits.release();
            }
            if (summary == null) {
                return false;
            }
            chunks.cacheChapterSummary(doc.sha256(), chapter.heading(), summary);
        }
        String location = chapter.location();
        float[] embedding = embeddingModel.embed(IngestionService.embeddingText(source,
                new ChunkDraft(summary, chapter.heading() + ChunkRepository.CHAPTER_SUMMARY_SUFFIX, location)));
        chunks.insertChapterSummary(doc.id(), doc.kbId(), chunkIndex, chapter.heading(), location, source, summary,
                embedding);
        return true;
    }

    /**
     * 按章节分组：章节路径截到第一个"第X章 / 第X回 / 第X篇"为止，"第一编 总则 > 第一章 基本规定 > 第一节"
     * 属于"第一编 总则 > 第一章 基本规定"；不在任何章节里的片段（前言、目录）不分组。同一章节出现在不连续的位置时合并。
     */
    static List<Chapter> chapters(List<ChunkView> textChunks) {
        Map<String, List<ChunkView>> groups = new LinkedHashMap<>();
        for (ChunkView chunk : textChunks) {
            String chapter = chapterOf(chunk.headingPath());
            if (chapter != null) {
                groups.computeIfAbsent(chapter, k -> new ArrayList<>()).add(chunk);
            }
        }
        return groups.entrySet().stream().map(e -> new Chapter(e.getKey(), e.getValue())).toList();
    }

    static String chapterOf(String headingPath) {
        if (headingPath == null) {
            return null;
        }
        String[] segments = headingPath.split(SEPARATOR);
        for (int i = 0; i < segments.length; i++) {
            if (CHAPTER_SEGMENT.matcher(segments[i].strip()).matches()) {
                return String.join(SEPARATOR, List.of(segments).subList(0, i + 1));
            }
        }
        return null;
    }
}
