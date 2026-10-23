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
