package flyingFish.ai.superKnowledgeBase.retrieval;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceCheckerTests {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void insufficientEvidenceGivesNewQueriesExcludingTried() {
        EvidenceChecker.Verdict v = EvidenceChecker.parse(jsonMapper, """
                ```json
                {"sufficient": false, "missing": "缺少西门庆临终的情节",
                 "queries": ["西门庆 断气身亡", "西门庆死在谁的床上", "金瓶梅 第七十九回", "西门庆 丧命"]}
                ```""", Set.of("西门庆死在谁的床上"));

        assertThat(v.sufficient()).isFalse();
        assertThat(v.missing()).isEqualTo("缺少西门庆临终的情节");
        // 去掉已经试过的查询，最多 MAX_QUERIES 个
        assertThat(v.queries()).containsExactly("西门庆 断气身亡", "金瓶梅 第七十九回");
    }

    @Test
    void treatsAnythingElseAsSufficient() {
        assertThat(EvidenceChecker.parse(jsonMapper, "{\"sufficient\": true, \"queries\": []}", Set.of()).sufficient()).isTrue();
        // 判为不足却没有可用的新查询，补查也没有意义
        assertThat(EvidenceChecker.parse(jsonMapper, "{\"sufficient\": false, \"queries\": [\"已试过\"]}", Set.of("已试过"))
                .sufficient()).isTrue();
        assertThat(EvidenceChecker.parse(jsonMapper, "资料足够", Set.of()).sufficient()).isTrue();
    }

    @Test
    void promptListsTriedQueriesAndEvidence() {
        RetrievedChunk chunk = new RetrievedChunk(1L, 1L, "金瓶梅.pdf", null, "第9页", "这西门大官人先头浑家陈氏早逝", 0.6,
                null, null, null, 0.9, false, true, "TEXT");

        String prompt = EvidenceChecker.prompt("西门庆第一个女人是谁", List.of("西门庆 第一个女人"), List.of(chunk));

        assertThat(prompt).contains("- 西门庆 第一个女人", "[1] 金瓶梅.pdf ｜ 第9页", "先头浑家陈氏");
        assertThat(EvidenceChecker.prompt("问题", List.of(), List.of())).contains("（没有检索到相关资料）");
    }
}
