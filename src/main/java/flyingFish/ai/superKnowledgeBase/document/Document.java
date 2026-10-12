package flyingFish.ai.superKnowledgeBase.document;

import java.time.OffsetDateTime;

public record Document(Long id, Long kbId, String filename, String filePath, String sha256, String mimeType,
                       long sizeBytes, String status, String errorMsg, int chunkCount,
                       OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
