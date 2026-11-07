package flyingFish.ai.superKnowledgeBase.ingestion;

import flyingFish.ai.superKnowledgeBase.common.PgVectors;
import flyingFish.ai.superKnowledgeBase.ingestion.Chunker.ChunkDraft;
import flyingFish.ai.superKnowledgeBase.retrieval.KeywordAnalyzer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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

    /** 章节摘要片段的章节路径是"章节路径 > 章节摘要"，和正文区分开，上下文扩展也不会把它和正文接在一起 */
    public static final String CHAPTER_SUMMARY_SUFFIX = " > 章节摘要";

    /**
     * 写入一个章节摘要片段；同一章节已有摘要片段时不重复写入（重新处理与补生成可能先后提交同一文档）。
     * 关键词索引和文档摘要一样加上文件出处，"红楼梦第五回讲了什么"能靠书名和回目命中。
     *
     * @param chunkIndex 从 -2 往下，排在文档摘要和正文之前
     */
    void insertChapterSummary(long documentId, long kbId, int chunkIndex, String chapter, String location,
                              String source, String summary, float[] embedding) {
        String heading = chapter + CHAPTER_SUMMARY_SUFFIX;
        List<String> terms = keywordTerms(heading + "\n" + source, summary);
        jdbc.sql("""
                        INSERT INTO chunk (document_id, kb_id, chunk_index, kind, content, heading_path, location, embedding,
                                           keyword_tsv, keyword_token_count)
                        SELECT ?, ?, ?, 'CHAPTER', ?, ?, ?, ?::vector, ?::tsvector, ?
                        WHERE NOT EXISTS (SELECT 1 FROM chunk WHERE document_id = ? AND kind = 'CHAPTER' AND heading_path = ?)
                        """)
                .params(documentId, kbId, chunkIndex, summary, heading, location, PgVectors.toLiteral(embedding),
                        KeywordAnalyzer.toTsvector(terms), terms.size(), documentId, heading)
                .update();
    }

    /** 已有章节摘要片段的章节路径（去掉"> 章节摘要"后缀） */
    Set<String> findChapterSummaryHeadings(long documentId) {
        return jdbc.sql("SELECT heading_path FROM chunk WHERE document_id = ? AND kind = 'CHAPTER'")
                .param(documentId).query(String.class).list().stream()
                .map(h -> h.endsWith(CHAPTER_SUMMARY_SUFFIX) ? h.substring(0, h.length() - CHAPTER_SUMMARY_SUFFIX.length()) : h)
                .collect(Collectors.toSet());
    }

    /** 删除不再存在（或不再需要摘要）的章节的摘要片段 */
    int deleteChapterSummaries(long documentId, Collection<String> chapters) {
        if (chapters.isEmpty()) {
            return 0;
        }
        return jdbc.sql("DELETE FROM chunk WHERE document_id = :id AND kind = 'CHAPTER' AND heading_path IN (:headings)")
                .param("id", documentId)
                .param("headings", chapters.stream().map(c -> c + CHAPTER_SUMMARY_SUFFIX).toList())
                .update();
    }

    Optional<String> findCachedChapterSummary(String sha256, String chapter) {
        return jdbc.sql("SELECT summary FROM chapter_summary WHERE sha256 = ? AND heading_path = ?")
                .params(sha256, chapter).query(String.class).optional();
    }

    void cacheChapterSummary(String sha256, String chapter, String summary) {
        jdbc.sql("""
                        INSERT INTO chapter_summary (sha256, heading_path, summary) VALUES (?, ?, ?)
                        ON CONFLICT (sha256, heading_path) DO UPDATE SET summary = EXCLUDED.summary, created_at = now()
                        """)
                .params(sha256, chapter, summary).update();
    }

    /** 已处理完成的文档，启动时逐个检查章节摘要是否齐全 */
    List<Long> findReadyDocuments() {
        return jdbc.sql("SELECT id FROM document WHERE status = 'READY' ORDER BY id").query(Long.class).list();
    }

    public void deleteSummary(long documentId) {
        jdbc.sql("DELETE FROM chunk WHERE document_id = ? AND kind = 'SUMMARY'").param(documentId).update();
    }

    /** 已处理完成、正文片段数达到 minChunks、但还没有摘要片段的文档 */
    List<Long> findDocumentsWithoutSummary(int minChunks) {
        return jdbc.sql("""
                        SELECT d.id FROM document d
                        WHERE d.status = 'READY' AND d.chunk_count >= ?
                          AND NOT EXISTS (SELECT 1 FROM chunk c WHERE c.document_id = d.id AND c.kind = 'SUMMARY')
                        ORDER BY d.id
                        """)
                .param(minChunks).query(Long.class).list();
    }

    /** 删除短文档（正文片段数少于 minChunks）的摘要片段，返回删除数；摘要原文仍留在 document.summary 供查看 */
    int deleteShortDocumentSummaries(int minChunks) {
        return jdbc.sql("""
                        DELETE FROM chunk c USING document d
                        WHERE c.document_id = d.id AND c.kind = 'SUMMARY' AND d.chunk_count < ?
                        """)
                .param(minChunks).update();
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
