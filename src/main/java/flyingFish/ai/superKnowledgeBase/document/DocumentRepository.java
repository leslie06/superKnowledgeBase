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
                        UPDATE document SET filename = ?, file_path = ?, local_path = NULL, sha256 = ?, mime_type = ?,
                                            size_bytes = ?, summary = NULL, status = ?, error_msg = NULL, updated_at = now()
                        WHERE id = ?
                        """)
                .params(filename, filePath, sha256, mimeType, size, DocumentStatus.PENDING.name(), id)
                .update();
    }

    /** 换成本机原文件的新版本（引用原文件导入）并重新排队处理 */
    public void replaceWithLocal(long id, String filename, String localPath, String sha256, String mimeType, long size) {
        jdbc.sql("""
                        UPDATE document SET filename = ?, file_path = NULL, local_path = ?, sha256 = ?, mime_type = ?,
                                            size_bytes = ?, summary = NULL, status = ?, error_msg = NULL, updated_at = now()
                        WHERE id = ?
                        """)
                .params(filename, localPath, sha256, mimeType, size, DocumentStatus.PENDING.name(), id)
                .update();
    }

    /**
     * 改为引用本机原文件（或原文件换了位置）：内容不变，不用重新处理。
     *
     * @param sourcePath 新的相对路径，null 时保留原来的
     */
    public void updateLocalPath(long id, String localPath, String sourcePath) {
        jdbc.sql("""
                        UPDATE document SET local_path = ?, file_path = NULL, source_path = COALESCE(?, source_path),
                                            updated_at = now()
                        WHERE id = ?
                        """)
                .params(localPath, sourcePath, id)
                .update();
    }

    /** 本机原文件的内容变了（被替换成新版本）：更新指纹，摘要要重新生成 */
    public void updateLocalVersion(long id, String sha256, long size) {
        jdbc.sql("UPDATE document SET sha256 = ?, size_bytes = ?, summary = NULL, updated_at = now() WHERE id = ?")
                .params(sha256, size, id)
                .update();
    }

    /** 引用的原文件在某个文件夹（含子文件夹）里的文档 */
    public List<Document> findLocalUnder(long kbId, String folder) {
        return jdbc.sql("""
                        SELECT * FROM document
                        WHERE kb_id = ? AND local_path IS NOT NULL AND (local_path = ? OR starts_with(local_path, ?))
                        ORDER BY id
                        """)
                .params(kbId, folder, folder + "/")
                .query(Document.class).list();
    }

    public void updateSummary(long id, String summary) {
        jdbc.sql("UPDATE document SET summary = ? WHERE id = ?").params(summary, id).update();
    }

    public List<Long> findIdsByStatus(List<String> statuses) {
        return jdbc.sql("SELECT id FROM document WHERE status IN (:statuses) ORDER BY id")
                .param("statuses", statuses).query(Long.class).list();
    }

    public long insert(long kbId, String filename, String sourcePath, String filePath, String sha256, String mimeType,
                       long size) {
        return insert(kbId, filename, sourcePath, filePath, null, sha256, mimeType, size);
    }

    /** @param filePath 复制保存的路径；引用本机原文件时为 null，localPath 为原文件的绝对路径 */
    public long insert(long kbId, String filename, String sourcePath, String filePath, String localPath, String sha256,
                       String mimeType, long size) {
        return jdbc.sql("""
                        INSERT INTO document (kb_id, filename, source_path, file_path, local_path, sha256, mime_type,
                                              size_bytes, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                        """)
                .params(kbId, filename, sourcePath, filePath, localPath, sha256, mimeType, size,
                        DocumentStatus.PENDING.name())
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
