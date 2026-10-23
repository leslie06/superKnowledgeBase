package flyingFish.ai.superKnowledgeBase.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("skb")
public record SkbProperties(String storageDir, Ingestion ingestion, Chunk chunk, Retrieval retrieval, Chat chat) {

    public record Ingestion(int concurrency, int embeddingBatchSize) {
    }

    public record Chunk(int targetChars, int maxChars, int overlapChars) {
    }

    /**
     * @param topK       问答时交给模型的片段数
     * @param minScore   余弦相似度阈值，低于它且没有精确命中编号的片段视为不相关
     * @param candidates    向量、关键词两路各自召回的候选数
     * @param rrfK          RRF 融合常数，片段得分 = 1 / (rrfK + 向量排名) + keywordWeight / (rrfK + 关键词排名)
     * @param keywordWeight 关键词一路在 RRF 中的权重；二字组 BM25 对改写、口语化的问题噪声较大，所以低于向量
     */
    public record Retrieval(int topK, double minScore, int candidates, int rrfK, double keywordWeight) {
    }

    public record Chat(int historyMessages, int maxContextChars) {
    }
}
