package flyingFish.ai.superKnowledgeBase.ingestion;

import flyingFish.ai.superKnowledgeBase.ingestion.ChunkRepository.KeywordSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 为加入关键词检索之前入库的片段补建关键词索引。只需要分词，不用重新向量化，所以启动时在后台直接补齐。
 */
@Component
public class KeywordIndexBackfill {

    private static final Logger log = LoggerFactory.getLogger(KeywordIndexBackfill.class);
    private static final int BATCH_SIZE = 500;

    private final ChunkRepository chunks;

    public KeywordIndexBackfill(ChunkRepository chunks) {
        this.chunks = chunks;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        Thread.ofVirtual().name("keyword-backfill").start(this::backfill);
    }

    void backfill() {
        int total = 0;
        try {
            List<KeywordSource> batch;
            while (!(batch = chunks.findWithoutKeywordIndex(BATCH_SIZE)).isEmpty()) {
                chunks.updateKeywordIndex(batch);
                total += batch.size();
            }
        } catch (Exception e) {
            log.warn("关键词索引回填失败，已回填 {} 个片段，下次启动会继续", total, e);
            return;
        }
        if (total > 0) {
            log.info("已为 {} 个片段补建关键词索引", total);
        }
    }
}
