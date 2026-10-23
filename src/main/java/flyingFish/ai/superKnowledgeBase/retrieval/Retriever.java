package flyingFish.ai.superKnowledgeBase.retrieval;

import flyingFish.ai.superKnowledgeBase.common.PgVectors;
import flyingFish.ai.superKnowledgeBase.common.SkbProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 混合检索：向量召回（语义相近）和关键词召回（BM25，擅长编号、型号、专有名词）各取若干候选，
 * 用加权 RRF 按排名融合；精确命中问题中编号（条款号、型号等）的片段排在最前。后续在这里加入 Rerank。
 */
@Service
public class Retriever {

    public enum Mode {HYBRID, VECTOR, KEYWORD}

    private record Hit(long chunkId, double score) {
    }

    private record ChunkRow(Long chunkId, Long documentId, String filename, String headingPath, String location,
                            String content, double score, boolean exactMatch) {
    }

    private static final double BM25_K1 = 1.2;
    private static final double BM25_B = 0.75;

    private final JdbcClient jdbc;
    private final EmbeddingModel embeddingModel;
    private final TransactionTemplate readOnlyTx;
    private final int defaultTopK;
    private final double minScore;
    private final int candidates;
    private final int rrfK;
    private final double keywordWeight;

    public Retriever(JdbcClient jdbc, EmbeddingModel embeddingModel, TransactionTemplate transactionTemplate,
                     SkbProperties properties) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
        this.readOnlyTx = new TransactionTemplate(transactionTemplate.getTransactionManager());
        this.readOnlyTx.setReadOnly(true);
        this.defaultTopK = properties.retrieval().topK();
        this.minScore = properties.retrieval().minScore();
        this.candidates = properties.retrieval().candidates();
        this.rrfK = properties.retrieval().rrfK();
        this.keywordWeight = properties.retrieval().keywordWeight();
    }

    /** 问答用：混合检索后去掉不相关的片段，取前 top-k 个。 */
    public List<RetrievedChunk> retrieve(long kbId, String query) {
        return rank(kbId, query, Mode.HYBRID, candidates).stream()
                .filter(c -> c.score() >= minScore || c.exactMatch())
                .limit(defaultTopK)
                .toList();
    }

    /** 不做相关性过滤的原始检索结果，供调试接口查看各路排名和分数。 */
    public List<RetrievedChunk> search(long kbId, String query, int topK, Mode mode) {
        return rank(kbId, query, mode, Math.max(candidates, topK)).stream().limit(topK).toList();
    }

    private List<RetrievedChunk> rank(long kbId, String query, Mode mode, int poolSize) {
        String vector = PgVectors.toLiteral(embeddingModel.embed(query));
        List<String> terms = KeywordAnalyzer.queryTerms(query);
        return readOnlyTx.execute(status -> {
            List<Hit> vectorHits = mode == Mode.KEYWORD ? List.of() : vectorSearch(kbId, vector, poolSize);
            List<Hit> keywordHits = mode == Mode.VECTOR || terms.isEmpty() ? List.of() : keywordSearch(kbId, terms, poolSize);

            Map<Long, Integer> vectorRanks = ranks(vectorHits);
            Map<Long, Integer> keywordRanks = ranks(keywordHits);
            Map<Long, Double> keywordScores = keywordHits.stream().collect(Collectors.toMap(Hit::chunkId, Hit::score));
            Map<Long, Double> fused = new HashMap<>();
            vectorRanks.forEach((id, rank) -> fused.merge(id, 1.0 / (rrfK + rank), Double::sum));
            keywordRanks.forEach((id, rank) -> fused.merge(id, keywordWeight / (rrfK + rank), Double::sum));
            if (fused.isEmpty()) {
                return List.of();
            }

            // 纯向量模式用于对比单路效果，不做精确命中优先
            Set<String> exactTerms = mode == Mode.VECTOR ? Set.of() : KeywordAnalyzer.exactTerms(terms);
            Map<Long, ChunkRow> rows = loadChunks(fused.keySet(), vector, exactTerms);
            // 问题里写明了"第39条""Q5"这类编号时，包含它的片段几乎一定是用户要找的，直接排到最前
            return rows.values().stream()
                    .sorted(Comparator.comparing(ChunkRow::exactMatch).reversed()
                            .thenComparing(Comparator.comparingDouble((ChunkRow r) -> fused.get(r.chunkId())).reversed())
                            .thenComparing(Comparator.comparingDouble(ChunkRow::score).reversed()))
                    .map(r -> new RetrievedChunk(r.chunkId(), r.documentId(), r.filename(), r.headingPath(),
                            r.location(), r.content(), r.score(), keywordScores.get(r.chunkId()),
                            vectorRanks.get(r.chunkId()), keywordRanks.get(r.chunkId()), r.exactMatch()))
                    .toList();
        });
    }

    private List<Hit> vectorSearch(long kbId, String vector, int limit) {
        // 带 kb_id 过滤时让 HNSW 继续扫描，避免存在多个知识库时召回数量不足
        jdbc.sql("SET LOCAL hnsw.iterative_scan = relaxed_order").update();
        List<Hit> hits = jdbc.sql("""
                        SELECT c.id AS chunk_id, 1 - (c.embedding <=> CAST(:vector AS vector)) AS score
                        FROM chunk c
                        WHERE c.kb_id = :kbId
                        ORDER BY c.embedding <=> CAST(:vector AS vector)
                        LIMIT :limit
                        """)
                .param("vector", vector)
                .param("kbId", kbId)
                .param("limit", limit)
                .query(Hit.class)
                .list();
        // relaxed_order 下结果可能略微乱序
        return hits.stream().sorted(Comparator.comparingDouble(Hit::score).reversed()).toList();
    }

    /**
     * BM25：idf = ln(1 + (N - df + 0.5) / (df + 0.5))，
     * 得分 = Σ idf · tf · (k1 + 1) / (tf + k1 · (1 - b + b · 文档长度 / 平均文档长度))。
     * N、df、平均长度都按当前知识库实时统计；tf 取 tsvector 中该词项的位置个数。
     */
    private List<Hit> keywordSearch(long kbId, List<String> terms, int limit) {
        return jdbc.sql("""
                        WITH stats AS (
                            SELECT count(*) AS n, greatest(avg(keyword_token_count), 1) AS avgdl
                            FROM chunk WHERE kb_id = :kbId
                        ), idf AS (
                            SELECT q.term, ln(1 + (s.n - d.df + 0.5) / (d.df + 0.5)) AS idf
                            FROM unnest(string_to_array(:terms, ' ')) AS q(term)
                            CROSS JOIN stats s
                            CROSS JOIN LATERAL (
                                SELECT count(*) AS df FROM chunk c
                                WHERE c.kb_id = :kbId AND c.keyword_tsv @@ CAST(quote_literal(q.term) AS tsquery)
                            ) d
                            WHERE d.df > 0
                        ), tf AS (
                            SELECT c.id, c.keyword_token_count AS dl, u.lexeme, coalesce(cardinality(u.positions), 1) AS tf
                            FROM chunk c CROSS JOIN LATERAL unnest(c.keyword_tsv) AS u
                            WHERE c.kb_id = :kbId AND c.keyword_tsv @@ CAST(:tsquery AS tsquery)
                              AND u.lexeme = ANY (string_to_array(:terms, ' '))
                        )
                        SELECT tf.id AS chunk_id,
                               sum(i.idf * tf.tf * (:k1 + 1) / (tf.tf + :k1 * (1 - :b + :b * tf.dl / s.avgdl))) AS score
                        FROM tf JOIN idf i ON i.term = tf.lexeme CROSS JOIN stats s
                        GROUP BY tf.id
                        ORDER BY score DESC
                        LIMIT :limit
                        """)
                .param("kbId", kbId)
                .param("terms", String.join(" ", terms))
                .param("tsquery", KeywordAnalyzer.toTsquery(terms))
                .param("k1", BM25_K1)
                .param("b", BM25_B)
                .param("limit", limit)
                .query(Hit.class)
                .list();
    }

    /** 取片段详情；关键词召回的片段也补算余弦相似度，过滤和展示统一用它。 */
    private Map<Long, ChunkRow> loadChunks(Set<Long> ids, String vector, Set<String> exactTerms) {
        String exactMatch = exactTerms.isEmpty() ? "false" : "c.keyword_tsv @@ CAST(:exact AS tsquery)";
        JdbcClient.StatementSpec spec = jdbc.sql("""
                        SELECT c.id AS chunk_id, c.document_id, d.filename, c.heading_path, c.location, c.content,
                               1 - (c.embedding <=> CAST(:vector AS vector)) AS score,
                               coalesce(%s, false) AS exact_match
                        FROM chunk c JOIN document d ON d.id = c.document_id
                        WHERE c.id IN (:ids)
                        """.formatted(exactMatch))
                .param("vector", vector)
                .param("ids", new ArrayList<>(ids));
        if (!exactTerms.isEmpty()) {
            spec = spec.param("exact", KeywordAnalyzer.toTsquery(exactTerms));
        }
        return spec.query(ChunkRow.class).list().stream()
                .collect(Collectors.toMap(ChunkRow::chunkId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    private static Map<Long, Integer> ranks(List<Hit> hits) {
        Map<Long, Integer> ranks = new HashMap<>();
        for (int i = 0; i < hits.size(); i++) {
            ranks.putIfAbsent(hits.get(i).chunkId(), i + 1);
        }
        return ranks;
    }
}
