package flyingFish.ai.superKnowledgeBase.chat;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class ConversationRepository {

    private static final TypeReference<List<Citation>> CITATIONS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public ConversationRepository(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    public List<Conversation> findByKb(long kbId) {
        return jdbc.sql("SELECT * FROM conversation WHERE kb_id = ? ORDER BY updated_at DESC, id DESC")
                .param(kbId).query(Conversation.class).list();
    }

    public Optional<Conversation> findById(long id) {
        return jdbc.sql("SELECT * FROM conversation WHERE id = ?").param(id).query(Conversation.class).optional();
    }

    public long insert(long kbId, String title) {
        return jdbc.sql("INSERT INTO conversation (kb_id, title) VALUES (?, ?) RETURNING id")
                .params(kbId, title).query(Long.class).single();
    }

    public void updateTitle(long id, String title) {
        jdbc.sql("UPDATE conversation SET title = ? WHERE id = ?").params(title, id).update();
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM conversation WHERE id = ?").param(id).update();
    }

    public List<ChatMessage> findMessages(long conversationId) {
        return jdbc.sql("SELECT * FROM message WHERE conversation_id = ? ORDER BY id")
                .param(conversationId).query(this::mapMessage).list();
    }

    /** 最近 limit 条消息，按时间正序返回。 */
    public List<ChatMessage> findRecentMessages(long conversationId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM (SELECT * FROM message WHERE conversation_id = ? ORDER BY id DESC LIMIT ?) t
                        ORDER BY id
                        """)
                .params(conversationId, limit).query(this::mapMessage).list();
    }

    public long insertMessage(long conversationId, String role, String content, List<Citation> citations,
                              Object retrievalTrace) {
        long id = jdbc.sql("""
                        INSERT INTO message (conversation_id, role, content, citations, retrieval_trace)
                        VALUES (?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb)) RETURNING id
                        """)
                .params(conversationId, role, content, toJson(citations), toJson(retrievalTrace))
                .query(Long.class).single();
        jdbc.sql("UPDATE conversation SET updated_at = now() WHERE id = ?").param(conversationId).update();
        return id;
    }

    private ChatMessage mapMessage(ResultSet rs, int rowNum) throws SQLException {
        return new ChatMessage(rs.getLong("id"), rs.getLong("conversation_id"), rs.getString("role"),
                rs.getString("content"), parseCitations(rs.getString("citations")),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private String toJson(Object value) {
        return value == null ? null : jsonMapper.writeValueAsString(value);
    }

    private List<Citation> parseCitations(String json) {
        return json == null ? List.of() : jsonMapper.readValue(json, CITATIONS);
    }
}
