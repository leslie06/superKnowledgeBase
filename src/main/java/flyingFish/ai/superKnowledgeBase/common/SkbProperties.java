package flyingFish.ai.superKnowledgeBase.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("skb")
public record SkbProperties(String storageDir, Ingestion ingestion, Chunk chunk, Retrieval retrieval, Chat chat) {

    public record Ingestion(int concurrency, int embeddingBatchSize) {
    }

    public record Chunk(int targetChars, int maxChars, int overlapChars) {
    }

    public record Retrieval(int topK, double minScore) {
    }

    public record Chat(int historyMessages, int maxContextChars) {
    }
}
