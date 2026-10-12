package flyingFish.ai.superKnowledgeBase.chat;

import java.time.OffsetDateTime;

public record Conversation(Long id, Long kbId, String title, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
