package flyingFish.ai.superKnowledgeBase.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("skb")
public record SkbProperties(String storageDir, Ingestion ingestion, Chunk chunk, Retrieval retrieval, Rerank rerank,
                            Chat chat, Ocr ocr, Summary summary) {

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
     * @param rewriteTimeout 查询改写（同步调用 LLM）的最长等待时间，超时后用原问题检索
     * @param efSearch      向量召回时 HNSW 索引的搜索范围（hnsw.ef_search），越大越接近精确结果；必须明显大于 candidates
     */
    public record Retrieval(int topK, double minScore, int candidates, int rrfK, double keywordWeight,
                            Duration rewriteTimeout, int efSearch) {
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

    /**
     * @param historyMessages 携带的历史消息条数
     * @param maxContextChars 参考资料的总字数预算
     * @param expandChars     每条参考资料扩展到的最大字数：把命中片段在同一章节里的前后片段一起交给模型，0 表示不扩展
     * @param verifyAnswers   生成回答后是否再请 LLM 对照参考资料核对（找出无依据内容和引用错误），每个问题多一次调用
     */
    public record Chat(int historyMessages, int maxContextChars, int expandChars, boolean verifyAnswers) {
    }

    /**
     * @param enabled      是否对扫描页做文字识别
     * @param command      识别命令（scripts/ocr/vision_ocr.swift 编译后的可执行文件），参数与输出格式见该文件头部注释；不存在时跳过识别
     * @param dpi          把页面渲染成图片的分辨率，越高越慢
     * @param minPageChars 文字层少于这么多个字（不含空白）的页视为扫描页
     */
    public record Ocr(boolean enabled, String command, int dpi, int minPageChars) {
    }

    /**
     * @param enabled    是否为文档生成摘要（需要配置 DeepSeek Key）；已有文档在启动时后台补生成
     * @param inputChars 交给 LLM 的文档信息字数上限（目录 + 开头 + 均匀抽取的几段正文）
     */
    public record Summary(boolean enabled, int inputChars) {
    }
}
