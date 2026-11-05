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

    @Test
    void dropsNearDuplicatesKeepingHigherRanked() {
        String text = "当一个线程修改了 volatile 变量，新值对其他线程立即可见，这就是可见性。".repeat(5);
        List<RetrievedChunk> ranked = List.of(
                chunk(1, text), chunk(2, text.replace("立即", "马上")), chunk(3, "完全不同的另一段内容，讲的是线程池的拒绝策略。"));

        assertThat(Retriever.dropDuplicates(ranked)).extracting(RetrievedChunk::chunkId).containsExactly(1L, 3L);
    }

    @Test
    void keepsAdjacentChunksSharingOnlyPartOfText() {
        String shared = "有下列情形之一的，用人单位可以解除劳动合同：";
        List<RetrievedChunk> ranked = List.of(
                chunk(1, shared + "（一）在试用期间被证明不符合录用条件的；（二）严重违反用人单位规章制度的；"),
                chunk(2, shared + "（三）严重失职，营私舞弊，给用人单位造成重大损害的；（四）同时与其他用人单位建立劳动关系的；"));

        assertThat(Retriever.dropDuplicates(ranked)).hasSize(2);
    }

    @Test
    void mergesPageLocations() {
        assertThat(Retriever.mergeLocations("第3页", "第4-5页")).isEqualTo("第3-5页");
        assertThat(Retriever.mergeLocations("第3-4页", "第4页")).isEqualTo("第3-4页");
        assertThat(Retriever.mergeLocations("第3页", "第3页")).isEqualTo("第3页");
        assertThat(Retriever.mergeLocations("第2张幻灯片", "第3张幻灯片")).isEqualTo("第2张幻灯片");
        assertThat(Retriever.mergeLocations(null, "第3页")).isEqualTo("第3页");
    }

    private static RetrievedChunk chunk(long id, Integer vectorRank, Integer keywordRank) {
        return new RetrievedChunk(id, 1L, "a.pdf", null, null, "正文", 0.5, null, vectorRank, keywordRank, null, false,
                true);
    }

    private static RetrievedChunk chunk(long id, String content) {
        return new RetrievedChunk(id, 1L, "a.pdf", null, null, content, 0.5, null, null, null, null, false, true);
    }
}
