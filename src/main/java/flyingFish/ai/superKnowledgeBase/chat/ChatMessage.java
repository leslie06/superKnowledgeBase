package flyingFish.ai.superKnowledgeBase.chat;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * @param verification 回答核对结果，没有核对时为 null
 * @param feedback     用户评价，没有评价时为 null
 */
public record ChatMessage(Long id, Long conversationId, String role, String content, List<Citation> citations,
                          Verification verification, Feedback feedback, OffsetDateTime createdAt) {

    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";
}
