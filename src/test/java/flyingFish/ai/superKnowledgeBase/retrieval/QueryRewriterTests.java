package flyingFish.ai.superKnowledgeBase.retrieval;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class QueryRewriterTests {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void dependentFollowUpUsesOnlyCompletedQuery() {
        QueryRewriter.MultiTurnRewrite r = QueryRewriter.parseMultiTurn(jsonMapper,
                "```json\n{\"dependsOnHistory\": true, \"query\": \"毕业答辩论文题目\"}\n```");

        assertThat(QueryRewriter.combineMultiTurn("不一定是康昱的，找找别人的", r)).isEqualTo("毕业答辩论文题目");
    }

    @Test
    void independentQuestionKeepsOriginalLikeSingleTurn() {
        QueryRewriter.MultiTurnRewrite r = QueryRewriter.parseMultiTurn(jsonMapper,
                "{\"dependsOnHistory\": false, \"query\": \"毕业答辩论文题目\"}");

        assertThat(QueryRewriter.combineMultiTurn("毕业答辩论文的题目是什么？", r))
                .isEqualTo("毕业答辩论文的题目是什么？\n毕业答辩论文题目");
    }

    @Test
    void nonJsonRewriteFallsBackToSingleTurnCombination() {
        QueryRewriter.MultiTurnRewrite r = QueryRewriter.parseMultiTurn(jsonMapper, "Redis 字典 哈希表 实现");

        assertThat(QueryRewriter.combineMultiTurn("那字典呢？", r)).isEqualTo("那字典呢？\nRedis 字典 哈希表 实现");
    }

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
