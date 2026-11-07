package flyingFish.ai.superKnowledgeBase.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 回答核对：生成回答后再请 LLM 对照参考资料检查一遍，找出资料中找不到依据的内容和对不上的 [n] 引用。
 * 提示词要求只依据资料作答，但约束不是绝对的：答案级评测中模型偶尔会补充资料之外的推断
 * （"违法解除赔偿是经济补偿的二倍""RW_ 是任务的拼音首字母"）。核对结果显示在回答下方，让用户知道哪些话要再确认。
 * <p>
 * 判定标准与答案级评测的裁判（scripts/eval_answer.py）一致。核对失败时返回空，不影响回答本身。
 */
@Service
public class AnswerVerifier {

    private static final Logger log = LoggerFactory.getLogger(AnswerVerifier.class);
    /** [3]、[1,2]、[1、2] 这类引用标记 */
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+(?:\\s*[,，、]\\s*\\d+)*)]");

    private static final String SYSTEM_PROMPT = """
            你是知识库问答的核对员。给定【参考资料】和系统根据资料生成的【回答】，检查回答是否有依据，只输出 JSON。
            1. 无依据内容（unsupported）：回答中参考资料无法支持的事实性内容，例如资料之外的法规、数字、标准、期限、流程，
               对编号或术语含义的猜测，资料没有写明的结论或后果。
               以下不算：对资料的复述和归纳；基于资料的正确推理或计算；"资料中没有提到"之类的说明；
               礼貌用语，以及"建议咨询人力资源部"这类不含具体事实的建议。推理或计算出错的算无依据内容。
            2. 引用错误（citationErrors）：回答中标注了 [n] 的句子，如果编号不存在，或者第 n 条资料并不支持这句话，列出该句。
            text 摘录回答中的原句（不超过 60 字），reason 用一句话说明原因（不超过 40 字）。没有问题时列表为空。
            输出格式：{"unsupported": [{"text": "", "reason": ""}], "citationErrors": [{"text": "", "reason": ""}]}
            """;

    private final ChatClient chatClient;
    private final JsonMapper jsonMapper;

    public AnswerVerifier(ChatClient.Builder chatClientBuilder, JsonMapper jsonMapper) {
        this.chatClient = chatClientBuilder.build();
        this.jsonMapper = jsonMapper;
    }

    public Optional<Verification> verify(List<Citation> citations, String answer) {
        if (citations.isEmpty() || !StringUtils.hasText(answer)) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder("【参考资料】\n");
        for (Citation c : cited(citations, answer)) {
            sb.append('[').append(c.index()).append("] ").append(c.content()).append("\n\n");
        }
        sb.append("【回答】\n").append(answer);
        try {
            String content = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(sb.toString())
                    .options(ChatOptions.builder().temperature(0.0))
                    .call()
                    .content();
            return Optional.of(parse(jsonMapper, content));
        } catch (Exception e) {
            log.warn("回答核对失败：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 只核对回答引用到的资料：回答通常只引用十来条里的三五条，没引用的资料不会成为任何一句话的依据，全部发过去等于参考资料发两遍。
     * 引用了不存在的编号时，核对时找不到这条资料，照样会被判为引用错误；回答没有任何 [n] 时仍发全部资料，用来判断有没有无依据内容。
     */
    static List<Citation> cited(List<Citation> citations, String answer) {
        Set<Integer> indexes = CITATION.matcher(answer).results()
                .flatMap(m -> Arrays.stream(m.group(1).split("[,，、\\s]+")))
                .filter(StringUtils::hasText)
                .map(Integer::valueOf)
                .collect(Collectors.toSet());
        List<Citation> used = citations.stream().filter(c -> indexes.contains(c.index())).toList();
        return used.isEmpty() ? citations : used;
    }

    /**
     * 模型有时会用 ```json 代码块包裹，取第一个 { 到最后一个 } 之间的内容。
     * 同一句话常被同时列为无依据内容和引用错误（编造的内容后面也标了 [n]），只保留在无依据内容里，避免一处问题显示两遍。
     */
    static Verification parse(JsonMapper jsonMapper, String content) {
        int start = content == null ? -1 : content.indexOf('{');
        int end = content == null ? -1 : content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("核对结果不是 JSON：" + content);
        }
        Verification v = jsonMapper.readValue(content.substring(start, end + 1), Verification.class);
        Set<String> unsupported = v.unsupported().stream().map(Verification.Issue::text).collect(Collectors.toSet());
        return new Verification(v.unsupported(),
                v.citationErrors().stream().filter(i -> !unsupported.contains(i.text())).toList());
    }
}
