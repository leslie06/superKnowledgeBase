package flyingFish.ai.superKnowledgeBase.chat;

import java.time.OffsetDateTime;
import java.util.List;

public record ChatMessage(Long id, Long conversationId, String role, String content, List<Citation> citations,
                          OffsetDateTime createdAt) {

    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";
}
