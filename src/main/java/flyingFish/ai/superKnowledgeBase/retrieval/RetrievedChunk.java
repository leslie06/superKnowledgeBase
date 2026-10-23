package flyingFish.ai.superKnowledgeBase.retrieval;

/**
 * 检索结果。列表按"精确命中优先，其余按加权 RRF"的顺序排列。
 *
 * @param score        与问题的余弦相似度（无论片段从哪一路召回都会计算），用于阈值过滤和展示
 * @param keywordScore BM25 得分；没有被关键词召回时为 null
 * @param vectorRank   在向量召回中的排名，从 1 开始；没有被向量召回时为 null
 * @param keywordRank  在关键词召回中的排名，从 1 开始；没有被关键词召回时为 null
 * @param exactMatch   是否包含问题中的精确词项（条款编号、q5、商品编码等），包含时不受相似度阈值限制
 */
public record RetrievedChunk(Long chunkId, Long documentId, String filename, String headingPath, String location,
                             String content, double score, Double keywordScore, Integer vectorRank,
                             Integer keywordRank, boolean exactMatch) {
}
