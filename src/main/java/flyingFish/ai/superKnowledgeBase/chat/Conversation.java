package flyingFish.ai.superKnowledgeBase.chat;

import flyingFish.ai.superKnowledgeBase.retrieval.SearchScope;

import java.time.OffsetDateTime;

/** @param scope 检索范围，不限范围时为 {@link SearchScope#ALL} */
public record Conversation(Long id, Long kbId, String title, SearchScope scope, OffsetDateTime createdAt,
                           OffsetDateTime updatedAt) {
}
