package flyingFish.ai.superKnowledgeBase.document;

import java.time.OffsetDateTime;

/**
 * @param sourcePath 文件夹导入时文件的相对路径（含文件夹名和文件名），单独上传的文件为 null
 * @param ocrPages   最近一次处理时需要文字识别的页数，0 表示不需要
 * @param ocrDone    已完成文字识别的页数，用于显示进度
 */
public record Document(Long id, Long kbId, String filename, String sourcePath, String filePath, String sha256, String mimeType,
                       long sizeBytes, String status, String errorMsg, int chunkCount, int ocrPages, int ocrDone,
                       OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
