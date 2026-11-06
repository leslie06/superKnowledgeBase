package flyingFish.ai.superKnowledgeBase.retrieval;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class QueryPlannerTests {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void parsesAggregatePlan() {
        QueryPlanner.Plan plan = QueryPlanner.parse(jsonMapper, """
                ```json
                {"aggregate": true, "subQueries": ["金瓶梅 潘金莲 外貌描写", "金瓶梅 李瓶儿 外貌描写", " 金瓶梅 潘金莲 外貌描写 "]}
                ```""");

        assertThat(plan.aggregate()).isTrue();
        assertThat(plan.subQueries()).containsExactly("金瓶梅 潘金莲 外貌描写", "金瓶梅 李瓶儿 外貌描写");
    }

    @Test
    void capsSubQueries() {
        QueryPlanner.Plan plan = QueryPlanner.parse(jsonMapper,
                "{\"aggregate\": true, \"subQueries\": [\"a\", \"b\", \"c\", \"d\", \"e\", \"f\", \"g\"]}");

        assertThat(plan.subQueries()).hasSize(QueryPlanner.MAX_SUB_QUERIES);
    }

    @Test
    void fallsBackToSingleWhenNotAggregateOrNotUsable() {
        assertThat(QueryPlanner.parse(jsonMapper, "{\"aggregate\": false, \"subQueries\": []}")).isEqualTo(QueryPlanner.Plan.SINGLE);
        // 只拆出一个子问题等于没拆
        assertThat(QueryPlanner.parse(jsonMapper, "{\"aggregate\": true, \"subQueries\": [\"婚假\"]}")).isEqualTo(QueryPlanner.Plan.SINGLE);
        assertThat(QueryPlanner.parse(jsonMapper, "不是 JSON")).isEqualTo(QueryPlanner.Plan.SINGLE);
        assertThat(QueryPlanner.parse(jsonMapper, "{\"aggregate\": true, \"subQueries\": 3}")).isEqualTo(QueryPlanner.Plan.SINGLE);
    }
}
