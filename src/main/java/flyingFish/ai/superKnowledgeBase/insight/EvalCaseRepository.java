package flyingFish.ai.superKnowledgeBase.insight;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class EvalCaseRepository {

    private final JdbcClient jdbc;

    public EvalCaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<EvalCase> findByKb(long kbId) {
        return jdbc.sql("SELECT * FROM eval_case WHERE kb_id = ? ORDER BY id").param(kbId).query(EvalCase.class).list();
    }

    public long insert(long kbId, String question, String expectedFile, String expectedSection, String expectedText,
                       String scope, String note, Long messageId) {
        return jdbc.sql("""
                        INSERT INTO eval_case (kb_id, question, expected_file, expected_section, expected_text, scope, note,
                                               message_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                        """)
                .params(kbId, question, expectedFile, expectedSection, expectedText, scope, note, messageId)
                .query(Long.class).single();
    }

    public int delete(long id) {
        return jdbc.sql("DELETE FROM eval_case WHERE id = ?").param(id).update();
    }
}
