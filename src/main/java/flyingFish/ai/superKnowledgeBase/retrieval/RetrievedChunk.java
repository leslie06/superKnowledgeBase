package flyingFish.ai.superKnowledgeBase.retrieval;

public record RetrievedChunk(Long chunkId, Long documentId, String filename, String headingPath, String location,
                             String content, double score) {
}
