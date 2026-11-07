package flyingFish.ai.superKnowledgeBase.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetrieverTests {

    @Test
    void rerankPoolKeepsTopKeywordHitsRankedLowByRrf() {
        List<RetrievedChunk> fused = List.of(chunk(1, 1, null), chunk(2, 2, null), chunk(3, 3, 3),
                chunk(4, 4, null), chunk(5, null, 1), chunk(6, null, 2), chunk(7, null, 4));

        // 关键词前 3 名（5、6、3）必进，其中 5、6 只被关键词召回，按 RRF 会排在所有向量结果之后；再按 RRF 补足
        assertThat(Retriever.rerankPool(fused, 4, 3)).extracting(RetrievedChunk::chunkId).containsExactly(5L, 6L, 3L, 1L);
    }

    @Test
    void summaryDoesNotTakeKeywordGuaranteeSlot() {
        RetrievedChunk summary = new RetrievedChunk(9L, 1L, "简历.pdf", "文档摘要", null, "求职简历", 0.5, null, null, 1,
                null, false, true, RetrievedChunk.SUMMARY);
        List<RetrievedChunk> fused = List.of(chunk(1, 1, null), chunk(2, 2, null), summary, chunk(4, null, 2),
                chunk(5, null, 3), chunk(6, null, 4));

        // 关键词前 3 名里有摘要，必进名额顺延给关键词第 4 名的正文片段；摘要按 RRF 顺序补进候选
        assertThat(Retriever.rerankPool(fused, 6, 3)).extracting(RetrievedChunk::chunkId)
                .containsExactly(4L, 5L, 6L, 1L, 2L, 9L);
    }

    @Test
    void interleaveTakesEachRouteInTurnAndSkipsRepeats() {
        List<RetrievedChunk> main = List.of(chunk(1, 1, null), chunk(2, 2, null), chunk(3, 3, null));
        List<RetrievedChunk> first = List.of(chunk(10, 1, null), chunk(1, 2, null));
        List<RetrievedChunk> second = List.of(chunk(20, 1, null));

        assertThat(Retriever.interleave(List.of(main, first, second))).extracting(RetrievedChunk::chunkId)
                .containsExactly(1L, 10L, 20L, 2L, 3L);
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
    void fullTextSectionsSplitOnHeadingChangeAndLength() {
        List<Retriever.TextRow> rows = List.of(
                new Retriever.TextRow(1L, "第一章", "第1页", "a".repeat(400)),
                new Retriever.TextRow(2L, "第一章", "第2页", "b".repeat(400)),
                new Retriever.TextRow(3L, "第一章", "第3页", "c".repeat(400)),
                new Retriever.TextRow(4L, "第二章", "第3页", "d".repeat(100)),
                new Retriever.TextRow(5L, null, "第4页", "e".repeat(100)));

        assertThat(Retriever.sections(rows, 1000)).extracting(s -> s.stream().map(Retriever.TextRow::chunkId).toList())
                .containsExactly(List.of(1L, 2L), List.of(3L), List.of(4L), List.of(5L));
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
                true, "TEXT");
    }

    private static RetrievedChunk chunk(long id, String content) {
        return new RetrievedChunk(id, 1L, "a.pdf", null, null, content, 0.5, null, null, null, null, false, true, "TEXT");
    }

    @Test
    void findsChaptersNamedInQuestion() {
        assertThat(Retriever.chapterNumbers("金瓶梅第七十九回讲了什么")).containsExactly(79);
        assertThat(Retriever.chapterNumbers("红楼梦第5章和第一百零一回")).containsExactly(5, 101);
        assertThat(Retriever.chapterNumbers("劳动合同法第三十九条")).isEmpty();
        assertThat(Retriever.parseNumber("十一")).isEqualTo(11);
        assertThat(Retriever.parseNumber("一百二十")).isEqualTo(120);
        assertThat(Retriever.chineseNumber(79)).isEqualTo("七十九");
        assertThat(Retriever.chineseNumber(10)).isEqualTo("十");
        assertThat(Retriever.chineseNumber(101)).isEqualTo("一百零一");
        assertThat(Retriever.chineseNumber(110)).isEqualTo("一百一十");
        assertThat(Retriever.chineseNumber(100)).isEqualTo("一百");
    }
}
