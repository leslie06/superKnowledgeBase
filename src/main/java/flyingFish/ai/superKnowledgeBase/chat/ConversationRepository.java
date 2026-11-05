package flyingFish.ai.superKnowledgeBase.chat;

import flyingFish.ai.superKnowledgeBase.retrieval.SearchScope;
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
                .param(kbId).query(this::mapConversation).list();
    }

    public Optional<Conversation> findById(long id) {
        return jdbc.sql("SELECT * FROM conversation WHERE id = ?").param(id).query(this::mapConversation).optional();
    }

    public long insert(long kbId, String title, SearchScope scope) {
        return jdbc.sql("INSERT INTO conversation (kb_id, title, scope) VALUES (?, ?, CAST(? AS jsonb)) RETURNING id")
                .params(kbId, title, scopeJson(scope)).query(Long.class).single();
    }

    public void updateScope(long id, SearchScope scope) {
        jdbc.sql("UPDATE conversation SET scope = CAST(? AS jsonb) WHERE id = ?").params(scopeJson(scope), id).update();
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

    private Conversation mapConversation(ResultSet rs, int rowNum) throws SQLException {
        String scope = rs.getString("scope");
        return new Conversation(rs.getLong("id"), rs.getLong("kb_id"), rs.getString("title"),
                scope == null ? SearchScope.ALL : jsonMapper.readValue(scope, SearchScope.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }

    /** 不限范围时存 NULL */
    private String scopeJson(SearchScope scope) {
        return scope == null || scope.isAll() ? null : toJson(scope);
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
