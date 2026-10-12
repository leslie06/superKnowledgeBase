package flyingFish.ai.superKnowledgeBase.ingestion;

import flyingFish.ai.superKnowledgeBase.common.PgVectors;
import flyingFish.ai.superKnowledgeBase.ingestion.Chunker.ChunkDraft;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class ChunkRepository {

    public record ChunkView(Long id, int chunkIndex, String content, String headingPath, String location) {
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
            args.add(new Object[]{documentId, kbId, i, d.content(), d.headingPath(), d.location(),
                    PgVectors.toLiteral(embeddings.get(i))});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO chunk (document_id, kb_id, chunk_index, content, heading_path, location, embedding)
                VALUES (?, ?, ?, ?, ?, ?, ?::vector)
                """, args);
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
