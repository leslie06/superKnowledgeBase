package flyingFish.ai.superKnowledgeBase.insight;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class InsightRepository {

    /** 一条回答及其提问；JSON 列按原文返回，由 {@link InsightService} 解析 */
    record AnswerRow(Long messageId, Long conversationId, String scope, String question, String answer, String citations,
                     String trace, String verification, String feedback, String feedbackReason, String feedbackNote,
                     OffsetDateTime createdAt) {
    }

    /** 评测脚本创建的会话标题以这个开头，不计入统计 */
    private static final String EVAL_TITLE_PREFIX = "[评测]";
    private static final int MAX_ROWS = 1000;

    private final JdbcClient jdbc;

    public InsightRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 知识库最近的回答（最新的在前），提问取同一会话中这条回答之前最近的一条用户消息 */
    List<AnswerRow> findAnswers(long kbId, OffsetDateTime since) {
        return jdbc.sql("""
                        SELECT a.id AS message_id, a.conversation_id, c.scope::text AS scope, q.content AS question,
                               a.content AS answer, a.citations::text AS citations, a.retrieval_trace::text AS trace,
                               a.verification::text AS verification, a.feedback, a.feedback_reason, a.feedback_note,
                               a.created_at
                        FROM message a
                        JOIN conversation c ON c.id = a.conversation_id
                        LEFT JOIN LATERAL (
                            SELECT u.content FROM message u
                            WHERE u.conversation_id = a.conversation_id AND u.id < a.id AND u.role = 'user'
                            ORDER BY u.id DESC LIMIT 1
                        ) q ON true
                        WHERE c.kb_id = :kbId AND a.role = 'assistant' AND a.created_at >= :since
                          AND c.title NOT LIKE :evalPrefix
                        ORDER BY a.id DESC
                        LIMIT :limit
                        """)
                .param("kbId", kbId)
                .param("since", since)
                .param("evalPrefix", EVAL_TITLE_PREFIX + "%")
                .param("limit", MAX_ROWS)
                .query(AnswerRow.class)
                .list();
    }
}
