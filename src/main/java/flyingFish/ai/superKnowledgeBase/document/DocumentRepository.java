package flyingFish.ai.superKnowledgeBase.document;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class DocumentRepository {

    private final JdbcClient jdbc;

    public DocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Document> findByKb(long kbId) {
        return jdbc.sql("SELECT * FROM document WHERE kb_id = ? ORDER BY created_at DESC, id DESC")
                .param(kbId).query(Document.class).list();
    }

    public Optional<Document> findById(long id) {
        return jdbc.sql("SELECT * FROM document WHERE id = ?").param(id).query(Document.class).optional();
    }

    public Optional<Document> findBySha256(long kbId, String sha256) {
        return jdbc.sql("SELECT * FROM document WHERE kb_id = ? AND sha256 = ?")
                .params(kbId, sha256).query(Document.class).optional();
    }

    public List<Long> findIdsByStatus(List<String> statuses) {
        return jdbc.sql("SELECT id FROM document WHERE status IN (:statuses) ORDER BY id")
                .param("statuses", statuses).query(Long.class).list();
    }

    public long insert(long kbId, String filename, String filePath, String sha256, String mimeType, long size) {
        return jdbc.sql("""
                        INSERT INTO document (kb_id, filename, file_path, sha256, mime_type, size_bytes, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
                        """)
                .params(kbId, filename, filePath, sha256, mimeType, size, DocumentStatus.PENDING.name())
                .query(Long.class).single();
    }

    public void updateStatus(long id, DocumentStatus status, String errorMsg) {
        jdbc.sql("UPDATE document SET status = ?, error_msg = ?, updated_at = now() WHERE id = ?")
                .params(status.name(), errorMsg, id).update();
    }

    public void markReady(long id, int chunkCount) {
        jdbc.sql("UPDATE document SET status = ?, error_msg = NULL, chunk_count = ?, updated_at = now() WHERE id = ?")
                .params(DocumentStatus.READY.name(), chunkCount, id).update();
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM document WHERE id = ?").param(id).update();
    }

}
