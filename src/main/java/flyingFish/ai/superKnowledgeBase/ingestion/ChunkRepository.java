package flyingFish.ai.superKnowledgeBase.ingestion;

import flyingFish.ai.superKnowledgeBase.common.PgVectors;
import flyingFish.ai.superKnowledgeBase.ingestion.Chunker.ChunkDraft;
import flyingFish.ai.superKnowledgeBase.retrieval.KeywordAnalyzer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class ChunkRepository {

    public record ChunkView(Long id, int chunkIndex, String content, String headingPath, String location) {
    }

    record KeywordSource(Long id, String headingPath, String content) {
    }

    /** 文档摘要片段的章节路径，引用和片段列表里显示为这一栏 */
    public static final String SUMMARY_HEADING = "文档摘要";
    /** 摘要片段排在正文之前，序号不会和正文片段相邻（上下文扩展按序号找相邻片段） */
    private static final int SUMMARY_INDEX = -1;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public ChunkRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertAll(long documentId, long kbId, List<ChunkDraft> drafts, List<float[]> embeddings) {
        List<Object[]> args = new ArrayList<>(drafts.size());
        for (int i = 0; i < drafts.size(); i++) {
            ChunkDraft d = drafts.get(i);
            List<String> terms = keywordTerms(d.headingPath(), d.content());
            args.add(new Object[]{documentId, kbId, i, d.content(), d.headingPath(), d.location(),
                    PgVectors.toLiteral(embeddings.get(i)), KeywordAnalyzer.toTsvector(terms), terms.size()});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO chunk (document_id, kb_id, chunk_index, content, heading_path, location, embedding,
                                   keyword_tsv, keyword_token_count)
                VALUES (?, ?, ?, ?, ?, ?, ?::vector, ?::tsvector, ?)
                """, args);
    }

    /**
     * 写入文档摘要片段。关键词索引额外加上文件出处，"《微服务设计》讲了什么"能靠书名命中；
     * 正文片段的文件名只拼进向量文本，不进关键词索引，否则同一本书的所有片段都会命中书名。
     */
    public void insertSummary(long documentId, long kbId, String source, String summary, float[] embedding) {
        List<String> terms = keywordTerms(SUMMARY_HEADING + "\n" + source, summary);
        jdbc.sql("""
                        INSERT INTO chunk (document_id, kb_id, chunk_index, kind, content, heading_path, embedding,
                                           keyword_tsv, keyword_token_count)
                        VALUES (?, ?, ?, 'SUMMARY', ?, ?, ?::vector, ?::tsvector, ?)
                        """)
                .params(documentId, kbId, SUMMARY_INDEX, summary, SUMMARY_HEADING, PgVectors.toLiteral(embedding),
                        KeywordAnalyzer.toTsvector(terms), terms.size())
                .update();
    }

    public void deleteSummary(long documentId) {
        jdbc.sql("DELETE FROM chunk WHERE document_id = ? AND kind = 'SUMMARY'").param(documentId).update();
    }

    /** 已处理完成、但还没有摘要片段的文档 */
    List<Long> findDocumentsWithoutSummary() {
        return jdbc.sql("""
                        SELECT d.id FROM document d
                        WHERE d.status = 'READY'
                          AND NOT EXISTS (SELECT 1 FROM chunk c WHERE c.document_id = d.id AND c.kind = 'SUMMARY')
                        ORDER BY d.id
                        """)
                .query(Long.class).list();
    }

    /** 正文片段（不含摘要），按顺序 */
    List<ChunkView> findTextByDocument(long documentId) {
        return jdbc.sql("""
                        SELECT id, chunk_index, content, heading_path, location
                        FROM chunk WHERE document_id = ? AND kind = 'TEXT' ORDER BY chunk_index
                        """)
                .param(documentId).query(ChunkView.class).list();
    }

    /** 还没有关键词索引的片段（V4 迁移之前入库的）。 */
    List<KeywordSource> findWithoutKeywordIndex(int limit) {
        return jdbc.sql("SELECT id, heading_path, content FROM chunk WHERE keyword_tsv IS NULL ORDER BY id LIMIT ?")
                .param(limit).query(KeywordSource.class).list();
    }

    void updateKeywordIndex(List<KeywordSource> sources) {
        List<Object[]> args = new ArrayList<>(sources.size());
        for (KeywordSource source : sources) {
            List<String> terms = keywordTerms(source.headingPath(), source.content());
            args.add(new Object[]{KeywordAnalyzer.toTsvector(terms), terms.size(), source.id()});
        }
        jdbcTemplate.batchUpdate("UPDATE chunk SET keyword_tsv = ?::tsvector, keyword_token_count = ? WHERE id = ?", args);
    }

    /** 章节路径也参与关键词检索，"证件办理""车辆管理"这类标题词往往就是用户的检索词。 */
    private static List<String> keywordTerms(String headingPath, String content) {
        return KeywordAnalyzer.analyze(headingPath == null ? content : headingPath + "\n" + content);
    }

    public void deleteByDocument(long documentId) {
        jdbc.sql("DELETE FROM chunk WHERE document_id = ?").param(documentId).update();
    }

    public List<ChunkView> findByDocument(long documentId) {
        return jdbc.sql("""
                        SELECT id, chunk_index, content, heading_path, location
                        FROM chunk WHERE document_id = ? ORDER BY chunk_index
                        """)
                .param(documentId).query(ChunkView.class).list();
    }
}
