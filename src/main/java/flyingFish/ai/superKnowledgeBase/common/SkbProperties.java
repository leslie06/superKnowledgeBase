package flyingFish.ai.superKnowledgeBase.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("skb")
public record SkbProperties(String storageDir, Ingestion ingestion, Chunk chunk, Retrieval retrieval, Rerank rerank,
                            Chat chat) {

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

    /**
     * @param enabled    是否启用精排；服务不可用时自动退回 RRF 排序和余弦阈值
     * @param baseUrl    Rerank 服务地址（scripts/rerank_server.py，接口与 TEI 的 /rerank 相同）
     * @param candidates 取 RRF 融合后的前多少个片段送去精排
     * @param minScore   精排分数阈值（0~1），低于它且没有精确命中编号的片段视为不相关，取代余弦阈值
     * @param timeout    单次精排请求的超时时间
     */
    public record Rerank(boolean enabled, String baseUrl, int candidates, double minScore, Duration timeout) {
    }

    public record Chat(int historyMessages, int maxContextChars) {
    }
}
