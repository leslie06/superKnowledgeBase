package flyingFish.ai.superKnowledgeBase.kb;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class KnowledgeBaseRepository {

    private static final String SELECT = """
            SELECT kb.*, (SELECT count(*) FROM document d WHERE d.kb_id = kb.id) AS document_count
            FROM knowledge_base kb
            """;

    private final JdbcClient jdbc;

    public KnowledgeBaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<KnowledgeBase> findAll() {
        return jdbc.sql(SELECT + " ORDER BY kb.id").query(KnowledgeBase.class).list();
    }

    public Optional<KnowledgeBase> findById(long id) {
        return jdbc.sql(SELECT + " WHERE kb.id = ?").param(id).query(KnowledgeBase.class).optional();
    }

    public long insert(String name, String description, String embeddingModel) {
        return jdbc.sql("INSERT INTO knowledge_base (name, description, embedding_model) VALUES (?, ?, ?) RETURNING id")
                .params(name, description, embeddingModel)
                .query(Long.class).single();
    }

    public void update(long id, String name, String description) {
        jdbc.sql("UPDATE knowledge_base SET name = ?, description = ? WHERE id = ?").params(name, description, id).update();
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM knowledge_base WHERE id = ?").param(id).update();
    }
}
