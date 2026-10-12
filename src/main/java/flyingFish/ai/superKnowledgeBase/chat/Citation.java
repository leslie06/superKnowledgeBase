package flyingFish.ai.superKnowledgeBase.chat;

/**
 * 回答引用的知识片段，index 与回答正文中的 [n] 对应。
 */
public record Citation(int index, Long chunkId, Long documentId, String filename, String headingPath,
                       String location, String content, double score) {
}
