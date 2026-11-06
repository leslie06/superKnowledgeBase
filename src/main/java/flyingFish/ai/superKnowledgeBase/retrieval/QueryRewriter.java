package flyingFish.ai.superKnowledgeBase.retrieval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import flyingFish.ai.superKnowledgeBase.common.SkbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 查询改写：用户的提问往往是口语化的（"10岁的孩子买游戏装备算数吗"），和文档的措辞（"八周岁以上的未成年人""民事法律行为"）
 * 对不上，向量召回、关键词召回和精排都会因此失效。检索前先请 LLM 把问题改写成文档中可能使用的术语，多轮对话时同时补全指代。
 * <p>
 * 检索文本是"原问题 + 换行 + 改写结果"：只用改写结果时，改写稍有偏差就会丢掉原意；两者拼接在评测中效果最好。
 * 改写用 temperature 0：同一个问题每次应得到相同的检索文本，否则检索结果会随机波动（问答默认的 0.3 在评测中会让结果时好时坏）。
 * 改写失败（未配置 Key、接口出错、超过 skb.retrieval.rewrite-timeout）时退回原问题，问答不会被改写拖住。
 * <p>
 * 多轮对话单独用一个提示词，先让模型判断最新问题是否依赖上文（{@link #MULTI_TURN_PROMPT}）。只有一个提示词时，
 * 前面几轮问的都是"康昱"，换话题问"毕业答辩论文的题目是什么"，改写也会补上"康昱"，把检索引向他的学历文件；
 * 用户说"不一定是康昱的，找找别人的"时，原问题里被否定的人名又被拼进检索文本。所以依赖上文时检索文本只用补全后的查询，
 * 不依赖时禁止带入历史中的人名、书名和话题。单轮提问仍用原来的提示词和"原问题 + 改写"，六份单轮题集按它校准过。
 */
@Service
public class QueryRewriter {

    /** 对话历史中的一条消息 */
    public record Turn(boolean fromUser, String content) {
    }

    private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);
    private static final int HISTORY_CHARS = 300;
    /** 模型偶尔不遵守长度要求，过长的改写结果会稀释原问题 */
    private static final int MAX_REWRITE_CHARS = 120;

    /** 多轮对话的改写结果：dependsOnHistory 为最新问题是否依赖上文 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MultiTurnRewrite(Boolean dependsOnHistory, String query) {
    }

    private static final String SYSTEM_PROMPT = """
            你是知识库检索的查询改写助手。知识库里是企业规章制度、法律法规、产品需求文档等正式文本，用户的提问往往是口语化的，和文档的措辞对不上。
            请把用户的最新问题改写成一条用于检索的查询：
            1. 有对话历史时，先补全最新问题中省略的主语和指代的内容，让查询脱离对话也能看懂；最新问题本身意思完整时，不要把历史中的文档名、编号、话题加进查询。
            2. 把口语说法换成正式文档中可能使用的书面语或专业术语，并补充问题涉及的关键概念。例如"外卖小哥送餐撞了人谁赔"可改写为"用人单位工作人员因执行工作任务造成他人损害 侵权责任承担"。
               问的是古籍、古典小说等文言或旧白话作品时，同时写出原文可能使用的旧式说法，例如妻子、老婆→浑家、娘子，原配、第一任→先头。
            3. 保留原问题中的专有名词、编号、数字和限定条件，不要改变问题的意思。
            4. 不要回答问题，不要编造具体的数字、期限或结论。
            5. 问题本身已经是正式表述时，原样输出。
            只输出改写后的查询，一行，不超过 60 字，不要解释。
            """;

    private static final String MULTI_TURN_PROMPT = """
            你是知识库检索的查询改写助手。知识库里是各类文档（规章制度、法律法规、技术书籍、合同证件等），用户的提问往往是口语化的，和文档的措辞对不上。
            给定对话历史和用户的最新问题，输出一个 JSON：{"dependsOnHistory": true 或 false, "query": "用于检索的查询"}。
            1. 先判断最新问题是否依赖对话历史。以下情况为 true：有指代（它、他、这个、上面说的）；省略了主语或对象（"那病假呢""男员工呢""多少钱一个月"）；
               承接上一轮继续追问；纠正或否定上一轮的理解（"不是……""不一定是……""换一本""找找别的"）。
               问题本身意思完整、换了新话题时为 false，即使和历史属于同一领域。
            2. dependsOnHistory 为 true：结合历史把最新问题补全成一个脱离对话也能看懂的完整问题，再改写成检索查询。
               用户在纠正时，去掉被否定的人名、书名、版本或范围，只保留真正要找的内容。
            3. dependsOnHistory 为 false：只改写最新问题本身，不要把历史中的人名、书名、编号、话题加进查询。
            4. 把口语说法换成正式文档中可能使用的书面语或专业术语，补充问题涉及的关键概念；保留专有名词、编号、数字和限定条件。
               问的是古籍、古典小说等文言或旧白话作品时，同时写出原文可能使用的旧式说法，例如妻子、老婆→浑家、娘子，原配、第一任→先头。
            5. 不要回答问题，不要编造具体的数字、期限或结论。
            query 一行，不超过 60 字。只输出 JSON，不要解释。
            """;

    private final ChatClient chatClient;
    private final JsonMapper jsonMapper;
    private final Duration timeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public QueryRewriter(ChatClient.Builder chatClientBuilder, JsonMapper jsonMapper, SkbProperties properties) {
        this.chatClient = chatClientBuilder.build();
        this.jsonMapper = jsonMapper;
        this.timeout = properties.retrieval().rewriteTimeout();
    }

    /**
     * @param history 最近的对话历史，按时间顺序；单轮提问时为空
     * @return 用于检索的文本
     */
    /** 交给 LLM 的"对话历史 + 最新问题"，历史中每条消息最多 HISTORY_CHARS 字；查询改写和问题规划共用 */
    static String dialogue(List<Turn> history, String question) {
        StringBuilder sb = new StringBuilder();
        if (!history.isEmpty()) {
            sb.append("对话历史：\n");
            for (Turn turn : history) {
                String content = turn.content().length() > HISTORY_CHARS
                        ? turn.content().substring(0, HISTORY_CHARS) + "…" : turn.content();
                sb.append(turn.fromUser() ? "用户：" : "助手：").append(content).append('\n');
            }
            sb.append('\n');
        }
        return sb.append("最新问题：").append(question).toString();
    }

    public String searchQuery(List<Turn> history, String question) {
        String prompt = dialogue(history, question);
        boolean multiTurn = !history.isEmpty();
        Future<String> call = executor.submit(() -> chatClient.prompt()
                .system(multiTurn ? MULTI_TURN_PROMPT : SYSTEM_PROMPT)
                .user(prompt)
                .options(ChatOptions.builder().temperature(0.0))
                .call().content());
        try {
            String rewritten = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!multiTurn) {
                log.debug("查询改写：{} → {}", question, rewritten);
                return combine(question, rewritten);
            }
            MultiTurnRewrite result = parseMultiTurn(jsonMapper, rewritten);
            log.debug("多轮查询改写（依赖上文：{}）：{} → {}", result.dependsOnHistory(), question, result.query());
            return combineMultiTurn(question, result);
        } catch (TimeoutException e) {
            call.cancel(true);
            log.warn("查询改写超过 {} 秒，使用原问题检索", timeout.toSeconds());
            return question;
        } catch (ExecutionException e) {
            log.warn("查询改写失败，使用原问题检索：{}", e.getCause().getMessage());
            return question;
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            return question;
        }
    }

    /**
     * 解析多轮改写的 JSON。模型偶尔会用代码块包裹或不按格式输出：取第一个 { 到最后一个 } 解析，
     * 解析不了时把整段当作查询、按不依赖上文处理（和单轮一样拼上原问题，最多退化成改写前的效果）。
     */
    static MultiTurnRewrite parseMultiTurn(JsonMapper jsonMapper, String content) {
        if (content != null) {
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    return jsonMapper.readValue(content.substring(start, end + 1), MultiTurnRewrite.class);
                } catch (RuntimeException e) {
                    log.debug("多轮查询改写不是合法的 JSON：{}", content);
                }
            }
        }
        return new MultiTurnRewrite(false, content);
    }

    /**
     * 依赖上文时只用补全后的查询：原问题"那字典呢""不一定是康昱的"脱离对话没有意义，还可能带着被否定的内容；
     * 不依赖上文时与单轮一样拼上原问题。
     */
    static String combineMultiTurn(String question, MultiTurnRewrite rewrite) {
        if (Boolean.TRUE.equals(rewrite.dependsOnHistory()) && StringUtils.hasText(rewrite.query())) {
            return firstLine(rewrite.query());
        }
        return combine(question, rewrite.query());
    }

    private static String firstLine(String text) {
        String line = text.strip().lines().findFirst().orElse("").strip();
        return line.length() > MAX_REWRITE_CHARS ? line.substring(0, MAX_REWRITE_CHARS) : line;
    }

    /** 拼接原问题和改写结果；改写结果为空或与原问题相同时只用原问题。 */
    static String combine(String question, String rewritten) {
        if (!StringUtils.hasText(rewritten)) {
            return question;
        }
        String line = firstLine(rewritten);
        return line.isEmpty() || line.equals(question) ? question : question + "\n" + line;
    }
}
