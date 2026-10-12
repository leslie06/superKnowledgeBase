package flyingFish.ai.superKnowledgeBase.retrieval;

import flyingFish.ai.superKnowledgeBase.common.PgVectors;
import flyingFish.ai.superKnowledgeBase.common.SkbProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.List;

/**
 * 向量检索。第一阶段只做向量召回；后续在这里加入 BM25 混合检索、RRF 融合和 Rerank。
 */
@Service
public class Retriever {

    private final JdbcClient jdbc;
    private final EmbeddingModel embeddingModel;
    private final TransactionTemplate readOnlyTx;
    private final int defaultTopK;
    private final double minScore;

    public Retriever(JdbcClient jdbc, EmbeddingModel embeddingModel, TransactionTemplate transactionTemplate,
                     SkbProperties properties) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
        this.readOnlyTx = new TransactionTemplate(transactionTemplate.getTransactionManager());
        this.readOnlyTx.setReadOnly(true);
        this.defaultTopK = properties.retrieval().topK();
        this.minScore = properties.retrieval().minScore();
    }

    /** 检索并过滤掉低于相关性阈值的片段。 */
    public List<RetrievedChunk> retrieve(long kbId, String query) {
        return search(kbId, query, defaultTopK).stream().filter(c -> c.score() >= minScore).toList();
    }

    /** 不做阈值过滤的原始检索结果，供调试接口查看分数分布。 */
    public List<RetrievedChunk> search(long kbId, String query, int topK) {
        String vector = PgVectors.toLiteral(embeddingModel.embed(query));
        List<RetrievedChunk> result = readOnlyTx.execute(status -> {
            // 带 kb_id 过滤时让 HNSW 继续扫描，避免存在多个知识库时召回数量不足
            jdbc.sql("SET LOCAL hnsw.iterative_scan = relaxed_order").update();
            return jdbc.sql("""
                            SELECT c.id AS chunk_id, c.document_id, d.filename, c.heading_path, c.location, c.content,
                                   1 - (c.embedding <=> CAST(:vector AS vector)) AS score
                            FROM chunk c JOIN document d ON d.id = c.document_id
                            WHERE c.kb_id = :kbId
                            ORDER BY c.embedding <=> CAST(:vector AS vector)
                            LIMIT :topK
                            """)
                    .param("vector", vector)
                    .param("kbId", kbId)
                    .param("topK", topK)
                    .query(RetrievedChunk.class)
                    .list();
        });
        // relaxed_order 下结果可能略微乱序
        return result.stream().sorted(Comparator.comparingDouble(RetrievedChunk::score).reversed()).toList();
    }
}
