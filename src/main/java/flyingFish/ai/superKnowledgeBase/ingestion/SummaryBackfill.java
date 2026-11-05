package flyingFish.ai.superKnowledgeBase.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 为加入文档摘要之前处理完成的文档补生成摘要片段。只调用一次 LLM 和一次向量化，不用重新处理正文，
 * 所以启动时在后台逐个补齐（一次一个，不占用摄入线程池，新上传的文档不用排在后面）；失败的下次启动再试。
 */
@Component
public class SummaryBackfill {

    private static final Logger log = LoggerFactory.getLogger(SummaryBackfill.class);
    private static final int LOG_EVERY = 10;

    private final ChunkRepository chunks;
    private final IngestionService ingestion;
    private final DocumentSummarizer summarizer;

    public SummaryBackfill(ChunkRepository chunks, IngestionService ingestion, DocumentSummarizer summarizer) {
        this.chunks = chunks;
        this.ingestion = ingestion;
        this.summarizer = summarizer;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        if (summarizer.available()) {
            Thread.ofVirtual().name("summary-backfill").start(this::backfill);
        }
    }

    void backfill() {
        List<Long> ids = chunks.findDocumentsWithoutSummary();
        if (ids.isEmpty()) {
            return;
        }
        log.info("开始为 {} 个已有文档补生成摘要", ids.size());
        int added = 0;
        int done = 0;
        for (long id : ids) {
            if (ingestion.isStopping()) {
                return;
            }
            try {
                if (ingestion.addSummary(id)) {
                    added++;
                }
            } catch (Exception e) {
                log.warn("补生成摘要失败，文档 {}：{}", id, e.getMessage());
            }
            if (++done % LOG_EVERY == 0) {
                log.info("补生成摘要进度 {}/{}", done, ids.size());
            }
        }
        log.info("补生成摘要完成：{} 个文档中 {} 个生成成功", ids.size(), added);
    }
}
