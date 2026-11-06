package flyingFish.ai.superKnowledgeBase.insight;

import flyingFish.ai.superKnowledgeBase.chat.Citation;
import flyingFish.ai.superKnowledgeBase.chat.Feedback;
import flyingFish.ai.superKnowledgeBase.chat.Verification;
import flyingFish.ai.superKnowledgeBase.insight.InsightService.Flag;
import flyingFish.ai.superKnowledgeBase.insight.InsightService.TraceHit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InsightServiceTests {

    private static final Citation CITATION = new Citation(1, 10L, 1L, "a.pdf", null, "第1页", "正文", 0.6);

    @Test
    void normalAnswerHasNoProblemFlags() {
        assertThat(InsightService.flags("年假 5 天 [1]。资料中没有说明能否折现。", List.of(CITATION),
                List.of(new TraceHit(10L, 0.6, 0.9)), Map.of("rewriteMs", 1000L, "retrieveMs", 2000L, "firstTokenMs", 1500L),
                new Verification(List.of(), List.of()), null)).isEmpty();
    }

    @Test
    void flagsRefusalUnrerankedSlowAndDownVote() {
        assertThat(InsightService.flags("知识库中没有找到相关信息。", List.of(CITATION), List.of(new TraceHit(10L, 0.6, null)),
                Map.of("rewriteMs", 2000L, "retrieveMs", 12000L, "firstTokenMs", 2000L), null,
                new Feedback(Feedback.DOWN, "NOT_FOUND", null)))
                .containsExactlyInAnyOrder(Flag.DOWN, Flag.REFUSED, Flag.UNRERANKED, Flag.SLOW);
    }

    @Test
    void retrievalRefusalAndVerificationIssue() {
        assertThat(InsightService.flags("知识库中没有找到与这个问题相关的内容。", List.of(), List.of(), Map.of(), null, null))
                .containsExactly(Flag.REFUSED);
        Verification issue = new Verification(List.of(new Verification.Issue("赔偿金为二倍", "资料未提及")), List.of());
        assertThat(InsightService.flags("答案 [1]", List.of(CITATION), List.of(), Map.of(), issue, null))
                .containsExactly(Flag.VERIFY_ISSUE);
    }

    @Test
    void csvFieldQuotesSpecialCharacters() {
        assertThat(EvalCaseService.csvField("普通")).isEqualTo("普通");
        assertThat(EvalCaseService.csvField("a,b")).isEqualTo("\"a,b\"");
        assertThat(EvalCaseService.csvField("说\"明\"")).isEqualTo("\"说\"\"明\"\"\"");
        assertThat(EvalCaseService.csvField(null)).isEmpty();
    }
}
