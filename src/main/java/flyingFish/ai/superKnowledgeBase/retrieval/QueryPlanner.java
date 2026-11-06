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
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 问题规划：判断问题是否需要汇总多处资料，需要时拆成几个子问题分别检索。
 * 一次检索取 top-k 个片段，适合答案落在一两段里的问题；"哪些行为会被公司直接解除劳动合同"的答案分散在员工手册好几章，
 * "金瓶梅里的女性人物形象"要看每个人物各自的描写，只检索一次只能覆盖其中一部分，模型要么答不全、要么拒答。
 * <p>
 * 拆分时允许借助模型自己的知识点出要查的对象（如书中有哪些主要人物），但只用于检索，回答仍只依据检索到的资料。
 * 与查询改写同时调用，不增加普通问题的耗时；失败或超时按普通问题处理。
 */
@Service
public class QueryPlanner {

    /**
     * @param subQueries 汇总类问题拆出的子问题，每个都是脱离对话也能看懂的检索查询；普通问题为空
     * @param intent     问题的本意：口语、俗语、比喻的问法换成本意后的问题（"西门庆死在谁的床上"→"西门庆因哪个女人而死"）；
     *                   照字面理解就对时为 null
     */
    public record Plan(boolean aggregate, List<String> subQueries, String intent) {

        public static final Plan SINGLE = new Plan(false, List.of(), null);

        public Plan(boolean aggregate, List<String> subQueries) {
            this(aggregate, subQueries, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawPlan(Boolean aggregate, List<String> subQueries, String intent) {
    }

    private static final Logger log = LoggerFactory.getLogger(QueryPlanner.class);
    /** 4 个不够：西门庆的妻妾有 6 位，只能 3 个人挤在一个子问题里，其中李瓶儿没检索到 */
    static final int MAX_SUB_QUERIES = 6;
    private static final int MAX_SUB_QUERY_CHARS = 60;

    private static final String SYSTEM_PROMPT = """
            你是知识库检索的问题规划助手。理解用户最新问题的本意，并判断能否靠一次检索找到答案，输出 JSON：
            {"intent": "问题的本意", "aggregate": true 或 false, "subQueries": ["子问题1", "子问题2"]}
            0. intent：问题用了口语、俗语、比喻或反问，照字面理解会答偏时，用一句话写出用户真正想问什么，
               例如"西门庆死在谁的床上"是问"西门庆是因为哪个女人而死的"，"这事谁背锅"是问"这件事由谁承担责任"；
               照字面理解就对时 intent 为空字符串。
            1. aggregate 为 true 的情况：答案分散在多处、需要汇总，例如要求列举全部（"哪些""有什么""都有谁"）、
               比较多个对象（"区别""对比""各自"）、概括整本书或多个人物、统计次数或找"最多/最少"。
            2. 只问一个事实、定义、规定、步骤或原因的，即使回答很长也为 false，subQueries 为空数组。
            3. aggregate 为 true 时，拆成 2～6 个子问题，合起来能覆盖整个问题。每个子问题只针对一个对象：
               一个人物、一种结构、一类情形，不要把几个对象放进同一个子问题。按资料中会出现的具体名称来拆，
               例如问"Redis 有哪些底层数据结构"应拆成简单动态字符串、链表、字典、跳跃表、整数集合、压缩列表，
               而不是按"字符串和哈希""列表和集合"这样的对外类型合并。
               可以用你的知识点出要查的具体对象（如书中的主要人物、常见的几类情形），但只用于检索，不要写答案。
               每个子问题是脱离对话也能看懂的检索查询，带上书名、文档名等限定词，不超过 30 字。
            4. 有对话历史时，先结合历史理解最新问题指的是什么。
            只输出 JSON，不要解释。
            """;

    private final ChatClient chatClient;
    private final JsonMapper jsonMapper;
    private final boolean enabled;
    private final Duration timeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public QueryPlanner(ChatClient.Builder chatClientBuilder, JsonMapper jsonMapper, SkbProperties properties) {
        this.chatClient = chatClientBuilder.build();
        this.jsonMapper = jsonMapper;
        this.enabled = properties.retrieval().planQueries();
        this.timeout = properties.retrieval().rewriteTimeout();
    }

    public Plan plan(List<QueryRewriter.Turn> history, String question) {
        if (!enabled) {
            return Plan.SINGLE;
        }
        Future<String> call = executor.submit(() -> chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(QueryRewriter.dialogue(history, question))
                .options(ChatOptions.builder().temperature(0.0))
                .call().content());
        try {
            Plan plan = parse(jsonMapper, call.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
            if (plan.intent() != null) {
                log.info("问题的本意：{} → {}", question, plan.intent());
            }
            if (plan.aggregate()) {
                log.info("汇总类问题，拆成 {} 个子问题：{} → {}", plan.subQueries().size(), question, plan.subQueries());
            }
            return plan;
        } catch (Exception e) {
            call.cancel(true);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("问题规划失败，按普通问题检索：{}", e.getMessage());
            return Plan.SINGLE;
        }
    }

    /** 取第一个 { 到最后一个 } 解析；没有可用的子问题时按普通问题处理 */
    static Plan parse(JsonMapper jsonMapper, String content) {
        int start = content == null ? -1 : content.indexOf('{');
        int end = content == null ? -1 : content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Plan.SINGLE;
        }
        RawPlan raw;
        try {
            raw = jsonMapper.readValue(content.substring(start, end + 1), RawPlan.class);
        } catch (RuntimeException e) {
            return Plan.SINGLE;
        }
        String intent = StringUtils.hasText(raw.intent()) ? raw.intent().strip() : null;
        if (!Boolean.TRUE.equals(raw.aggregate()) || raw.subQueries() == null) {
            return new Plan(false, List.of(), intent);
        }
        List<String> subQueries = raw.subQueries().stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(StringUtils::hasText)
                .map(q -> q.length() > MAX_SUB_QUERY_CHARS ? q.substring(0, MAX_SUB_QUERY_CHARS) : q)
                .distinct()
                .limit(MAX_SUB_QUERIES)
                .toList();
        // 只拆出一个子问题等于没拆，按普通问题处理
        return subQueries.size() < 2 ? new Plan(false, List.of(), intent) : new Plan(true, subQueries, intent);
    }
}
