package flyingFish.ai.superKnowledgeBase.retrieval;

/**
 * 检索结果。列表按"精确命中优先，其余按精排分数"排列；未启用或无法精排时按加权 RRF 排列。
 *
 * @param score        与问题的余弦相似度（无论片段从哪一路召回都会计算），用于展示，未精排时也用于阈值过滤
 * @param keywordScore BM25 得分；没有被关键词召回时为 null
 * @param vectorRank   在向量召回中的排名，从 1 开始；没有被向量召回时为 null
 * @param keywordRank  在关键词召回中的排名，从 1 开始；没有被关键词召回时为 null
 * @param rerankScore  交叉编码器精排分数（0~1）；未精排时为 null
 * @param exactMatch   是否包含问题中的精确词项（条款编号、q5、商品编码等），包含时不受阈值限制
 * @param relevant     是否通过相关性过滤（精排分数或余弦相似度达到阈值，或精确命中），问答只使用通过的片段
 */
public record RetrievedChunk(Long chunkId, Long documentId, String filename, String headingPath, String location,
                             String content, double score, Double keywordScore, Integer vectorRank,
                             Integer keywordRank, Double rerankScore, boolean exactMatch, boolean relevant) {
}
