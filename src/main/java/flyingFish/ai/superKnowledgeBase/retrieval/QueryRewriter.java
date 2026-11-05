package flyingFish.ai.superKnowledgeBase.retrieval;

import flyingFish.ai.superKnowledgeBase.common.SkbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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

    private static final String SYSTEM_PROMPT = """
            你是知识库检索的查询改写助手。知识库里是企业规章制度、法律法规、产品需求文档等正式文本，用户的提问往往是口语化的，和文档的措辞对不上。
            请把用户的最新问题改写成一条用于检索的查询：
            1. 有对话历史时，先补全最新问题中省略的主语和指代的内容，让查询脱离对话也能看懂；最新问题本身意思完整时，不要把历史中的文档名、编号、话题加进查询。
            2. 把口语说法换成正式文档中可能使用的书面语或专业术语，并补充问题涉及的关键概念。例如"外卖小哥送餐撞了人谁赔"可改写为"用人单位工作人员因执行工作任务造成他人损害 侵权责任承担"。
            3. 保留原问题中的专有名词、编号、数字和限定条件，不要改变问题的意思。
            4. 不要回答问题，不要编造具体的数字、期限或结论。
            5. 问题本身已经是正式表述时，原样输出。
            只输出改写后的查询，一行，不超过 60 字，不要解释。
            """;

    private final ChatClient chatClient;
    private final Duration timeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public QueryRewriter(ChatClient.Builder chatClientBuilder, SkbProperties properties) {
        this.chatClient = chatClientBuilder.build();
        this.timeout = properties.retrieval().rewriteTimeout();
    }

    /**
     * @param history 最近的对话历史，按时间顺序；单轮提问时为空
     * @return 用于检索的文本
     */
    public String searchQuery(List<Turn> history, String question) {
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
        sb.append("最新问题：").append(question);
        Future<String> call = executor.submit(() -> chatClient.prompt().system(SYSTEM_PROMPT).user(sb.toString())
                .options(ChatOptions.builder().temperature(0.0))
                .call().content());
        try {
            String rewritten = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            log.debug("查询改写：{} → {}", question, rewritten);
            return combine(question, rewritten);
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

    /** 拼接原问题和改写结果；改写结果为空或与原问题相同时只用原问题。 */
    static String combine(String question, String rewritten) {
        if (!StringUtils.hasText(rewritten)) {
            return question;
        }
        String line = rewritten.strip().lines().findFirst().orElse("").strip();
        if (line.length() > MAX_REWRITE_CHARS) {
            line = line.substring(0, MAX_REWRITE_CHARS);
        }
        return line.isEmpty() || line.equals(question) ? question : question + "\n" + line;
    }
}
