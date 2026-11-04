package flyingFish.ai.superKnowledgeBase.document;

import java.time.OffsetDateTime;

/**
 * @param sourcePath 文件夹导入时文件的相对路径（含文件夹名和文件名），单独上传的文件为 null
 */
public record Document(Long id, Long kbId, String filename, String sourcePath, String filePath, String sha256, String mimeType,
                       long sizeBytes, String status, String errorMsg, int chunkCount,
                       OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
