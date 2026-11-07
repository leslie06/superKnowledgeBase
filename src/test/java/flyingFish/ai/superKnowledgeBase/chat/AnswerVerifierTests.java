package flyingFish.ai.superKnowledgeBase.chat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.stream.IntStream;

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
    void sendsOnlyCitedReferences() {
        // 1~5 各属一个文档，6、7 与 2 同属文档 2（整章读入时同一章分成多条）
        List<Citation> citations = new java.util.ArrayList<>(IntStream.rangeClosed(1, 5)
                .mapToObj(i -> new Citation(i, (long) i, (long) i, "员工手册.docx", null, null, "资料" + i, 0.8)).toList());
        citations.add(new Citation(6, 6L, 2L, "员工手册.docx", null, null, "资料6", 0.8));
        citations.add(new Citation(7, 7L, 2L, "员工手册.docx", null, null, "资料7", 0.8));

        assertThat(AnswerVerifier.cited(citations, "婚假 3 天 [2]，丧假见 [4、5]，另见[1,2]。"))
                .extracting(Citation::index).containsExactly(1, 2, 4, 5, 6, 7);
        // 没有任何引用时仍发全部资料，用来判断有没有无依据内容
        assertThat(AnswerVerifier.cited(citations, "知识库中没有找到相关内容。")).hasSize(7);
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
