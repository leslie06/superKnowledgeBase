package flyingFish.ai.superKnowledgeBase.chat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnswerVerifierTests {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void parsesJsonWrappedInCodeFence() {
        String content = """
                ```json
                {"unsupported": [{"text": "赔偿金为经济补偿的二倍", "reason": "资料未提及二倍标准"}], "citationErrors": []}
                ```
                """;

        Verification v = AnswerVerifier.parse(jsonMapper, content);

        assertThat(v.unsupported()).extracting(Verification.Issue::text).containsExactly("赔偿金为经济补偿的二倍");
        assertThat(v.citationErrors()).isEmpty();
        assertThat(v.passed()).isFalse();
    }

    @Test
    void sameSentenceIsReportedOnlyAsUnsupported() {
        String content = """
                {"unsupported": [{"text": "婚假是 5 天 [2]", "reason": "资料写 3 天"}],
                 "citationErrors": [{"text": "婚假是 5 天 [2]", "reason": "第 2 条不支持"}, {"text": "按日工资扣款 [1]", "reason": "第 1 条没写"}]}
                """;

        Verification v = AnswerVerifier.parse(jsonMapper, content);

        assertThat(v.unsupported()).hasSize(1);
        assertThat(v.citationErrors()).extracting(Verification.Issue::text).containsExactly("按日工资扣款 [1]");
    }

    @Test
    void missingListsMeanNoIssues() {
        assertThat(AnswerVerifier.parse(jsonMapper, "{\"unsupported\": []}").passed()).isTrue();
    }

    @Test
    void rejectsNonJson() {
        assertThatThrownBy(() -> AnswerVerifier.parse(jsonMapper, "回答都有依据")).isInstanceOf(IllegalArgumentException.class);
    }
}
