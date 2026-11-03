package flyingFish.ai.superKnowledgeBase.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryRewriterTests {

    @Test
    void appendsRewriteToOriginalQuestion() {
        assertThat(QueryRewriter.combine("10岁买游戏装备算数吗？", "  限制民事行为能力人 民事法律行为效力\n"))
                .isEqualTo("10岁买游戏装备算数吗？\n限制民事行为能力人 民事法律行为效力");
    }

    @Test
    void keepsOnlyFirstLineAndCapsLength() {
        assertThat(QueryRewriter.combine("问题", "改写\n解释：这是改写")).isEqualTo("问题\n改写");
        assertThat(QueryRewriter.combine("问题", "长".repeat(500))).hasSize("问题\n".length() + 120);
    }

    @Test
    void fallsBackToQuestionWhenRewriteIsEmptyOrSame() {
        assertThat(QueryRewriter.combine("婚假有几天？", null)).isEqualTo("婚假有几天？");
        assertThat(QueryRewriter.combine("婚假有几天？", "  ")).isEqualTo("婚假有几天？");
        assertThat(QueryRewriter.combine("婚假有几天？", "婚假有几天？")).isEqualTo("婚假有几天？");
    }
}
