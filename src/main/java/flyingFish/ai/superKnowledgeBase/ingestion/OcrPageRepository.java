package flyingFish.ai.superKnowledgeBase.ingestion;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.stream.Collectors;

/** 文字识别结果缓存（ocr_page 表），按文件内容的 SHA-256 和页码保存。 */
@Repository
public class OcrPageRepository {

    private final JdbcClient jdbc;

    public OcrPageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 已识别的页：页码 → 文字 */
    public Map<Integer, String> findBySha256(String sha256) {
        return jdbc.sql("SELECT page, content FROM ocr_page WHERE sha256 = ?")
                .param(sha256)
                .query((rs, n) -> Map.entry(rs.getInt("page"), rs.getString("content")))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public void save(String sha256, int page, String engine, String content) {
        jdbc.sql("""
                        INSERT INTO ocr_page (sha256, page, engine, content) VALUES (?, ?, ?, ?)
                        ON CONFLICT (sha256, page) DO UPDATE SET engine = excluded.engine, content = excluded.content,
                                                                 created_at = now()
                        """)
                .params(sha256, page, engine, content)
                .update();
    }

    /** 删除已经没有文档引用的缓存（删除文档或知识库之后调用） */
    public int deleteOrphans() {
        return jdbc.sql("DELETE FROM ocr_page o WHERE NOT EXISTS (SELECT 1 FROM document d WHERE d.sha256 = o.sha256)")
                .update();
    }
}
