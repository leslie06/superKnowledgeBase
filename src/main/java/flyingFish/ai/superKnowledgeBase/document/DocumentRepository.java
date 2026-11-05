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

    /**
     * 同一来源的文档：文件夹导入的按相对路径，单独上传的（没有路径）按文件名。
     * 早期没有替换旧版本时可能留下多个，取最新的一个。
     */
    public Optional<Document> findBySource(long kbId, String sourcePath, String filename) {
        return (sourcePath != null
                ? jdbc.sql("SELECT * FROM document WHERE kb_id = ? AND source_path = ? ORDER BY id DESC LIMIT 1")
                .params(kbId, sourcePath)
                : jdbc.sql("SELECT * FROM document WHERE kb_id = ? AND source_path IS NULL AND filename = ? ORDER BY id DESC LIMIT 1")
                .params(kbId, filename))
                .query(Document.class).optional();
    }

    /** 换成新版本的文件并重新排队处理；旧的片段保留到新版本处理完成时一并替换 */
    public void replaceFile(long id, String filename, String filePath, String sha256, String mimeType, long size) {
        jdbc.sql("""
                        UPDATE document SET filename = ?, file_path = ?, sha256 = ?, mime_type = ?, size_bytes = ?,
                                            status = ?, error_msg = NULL, updated_at = now()
                        WHERE id = ?
                        """)
                .params(filename, filePath, sha256, mimeType, size, DocumentStatus.PENDING.name(), id)
                .update();
    }

    public List<Long> findIdsByStatus(List<String> statuses) {
        return jdbc.sql("SELECT id FROM document WHERE status IN (:statuses) ORDER BY id")
                .param("statuses", statuses).query(Long.class).list();
    }

    public long insert(long kbId, String filename, String sourcePath, String filePath, String sha256, String mimeType,
                       long size) {
        return jdbc.sql("""
                        INSERT INTO document (kb_id, filename, source_path, file_path, sha256, mime_type, size_bytes, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                        """)
                .params(kbId, filename, sourcePath, filePath, sha256, mimeType, size, DocumentStatus.PENDING.name())
                .query(Long.class).single();
    }

    public void updateStatus(long id, DocumentStatus status, String errorMsg) {
        jdbc.sql("UPDATE document SET status = ?, error_msg = ?, updated_at = now() WHERE id = ?")
                .params(status.name(), errorMsg, id).update();
    }

    /** 开始解析：清零上次的文字识别进度 */
    public void markParsing(long id) {
        jdbc.sql("UPDATE document SET status = ?, error_msg = NULL, ocr_pages = 0, ocr_done = 0, updated_at = now() WHERE id = ?")
                .params(DocumentStatus.PARSING.name(), id).update();
    }

    public void updateOcrProgress(long id, int done, int total) {
        jdbc.sql("UPDATE document SET status = ?, ocr_done = ?, ocr_pages = ?, updated_at = now() WHERE id = ?")
                .params(DocumentStatus.OCR.name(), done, total, id).update();
    }

    public void markReady(long id, int chunkCount) {
        jdbc.sql("UPDATE document SET status = ?, error_msg = NULL, chunk_count = ?, updated_at = now() WHERE id = ?")
                .params(DocumentStatus.READY.name(), chunkCount, id).update();
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM document WHERE id = ?").param(id).update();
    }

}
