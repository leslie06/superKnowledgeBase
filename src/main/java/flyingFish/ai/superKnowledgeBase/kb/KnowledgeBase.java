package flyingFish.ai.superKnowledgeBase.kb;

import java.time.OffsetDateTime;

public record KnowledgeBase(Long id, String name, String description, String embeddingModel,
                            OffsetDateTime createdAt, long documentCount) {
}
