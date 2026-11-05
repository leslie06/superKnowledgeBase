package flyingFish.ai.superKnowledgeBase.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetrieverTests {

    @Test
    void rerankPoolKeepsTopKeywordHitsRankedLowByRrf() {
        List<RetrievedChunk> fused = List.of(
                chunk(1, 1, null), chunk(2, 2, null), chunk(3, 3, 5), chunk(4, 4, null), chunk(5, null, 2));

        assertThat(Retriever.rerankPool(fused, 3, 3)).extracting(RetrievedChunk::chunkId).containsExactly(5L, 1L, 2L);
    }

    @Test
    void rerankPoolFallsBackToRrfOrderWithoutKeywordHits() {
        List<RetrievedChunk> fused = List.of(chunk(1, 1, null), chunk(2, 2, null), chunk(3, 3, null));

        assertThat(Retriever.rerankPool(fused, 2, 3)).extracting(RetrievedChunk::chunkId).containsExactly(1L, 2L);
    }

    private static RetrievedChunk chunk(long id, Integer vectorRank, Integer keywordRank) {
        return new RetrievedChunk(id, 1L, "a.pdf", null, null, "正文", 0.5, null, vectorRank, keywordRank, null, false,
                true);
    }
}
