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
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 判断已检索到的资料够不够回答问题，不够时给出新的检索查询（迭代检索的一轮）。
 * 只检索一次时，问法和原文对不上就找不到：问"西门庆死在谁的床上"，"床上"在书里出现上百次，检索到的全是生前的情节，
 * 原文写的是"贪欲丧命""断气身亡""三丸药"。让模型先看一遍检索结果，发现缺的是什么，再换原文可能用的说法去查；
 * 找到了事件本身而原因在前文时（死去的那一段找到了，死因在之前的情节里），请求补看那段资料的前文或后文。
 * <p>
 * 失败或超时时当作资料足够，不再补查，问答照常进行。
 */
@Service
public class EvidenceChecker {

    /**
     * @param missing 缺少的信息（一句话）
     * @param queries 新的检索查询
     * @param context 要补看原文前后文的资料：已找到事件本身，答案在它的前文（如原因）或后文（如结果）
     */
    public record Verdict(boolean sufficient, String missing, List<String> queries, List<ContextRequest> context) {

        static final Verdict SUFFICIENT = new Verdict(true, null, List.of(), List.of());
    }

    /**
     * @param index  资料编号，从 1 开始，对应交给判断的资料顺序
     * @param before true 补看前文，false 补看后文
     */
    public record ContextRequest(int index, boolean before) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawVerdict(Boolean sufficient, String missing, List<String> queries, List<RawContext> context) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawContext(Integer index, String direction) {
    }

    private static final Logger log = LoggerFactory.getLogger(EvidenceChecker.class);
    static final int MAX_QUERIES = 2;
    static final int MAX_CONTEXT_REQUESTS = 2;
    private static final int MAX_QUERY_CHARS = 60;
    /** 每段资料交给判断的字数：判断只需要知道资料讲了什么，不必全文 */
    private static final int EVIDENCE_CHARS = 800;

    private static final String SYSTEM_PROMPT = """
            你是知识库问答的检索助手。给定用户问题、已经试过的检索查询和检索到的资料，判断资料是否足以回答问题，输出 JSON：
            {"sufficient": true 或 false, "missing": "还缺什么信息，一句话", "queries": ["新的检索查询"],
             "context": [{"index": 资料编号, "direction": "before" 或 "after"}]}
            1. 资料中有能直接回答、或能据此推出答案的内容时为 true，queries 为空数组。
            2. 资料只是话题相关、却没有问题要问的那件事时为 false（例如问人物怎么死的，资料只有他生前的情节；
               问某条规定，资料只有别的条款）。这时给出 1～2 个新的检索查询：换用原文可能使用的说法
               （如"死"→"断气身亡""丧命""临终"，"老婆"→"浑家""娘子"），或者指向答案可能所在的情节、章节、条款。
               可以用你对这份资料的了解点出位置和原文用词，但只用于检索，不要把答案写进查询。
            3. 资料里已经有问题相关的那件事本身，但答案在它的前文或后文时（例如找到了人物死去的段落，死因写在之前的情节里；
               找到了某条规定，例外情形写在后面），在 context 中给出这段资料的编号和方向：before 补看前文，after 补看后文，
               最多 2 处，系统会把原文中前后约五千字补进来。可以同时给出 queries。
            4. 新查询不要和已经试过的查询重复，每个不超过 30 字。
            只输出 JSON，不要解释。
            """;

    private final ChatClient chatClient;
    private final JsonMapper jsonMapper;
    private final Duration timeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public EvidenceChecker(ChatClient.Builder chatClientBuilder, JsonMapper jsonMapper, SkbProperties properties) {
        this.chatClient = chatClientBuilder.build();
        this.jsonMapper = jsonMapper;
        this.timeout = properties.retrieval().rewriteTimeout();
    }

    /**
     * @param question 用户问题（多轮时为改写后补全的检索文本更合适，由调用方决定）
     * @param tried    已经试过的检索查询
     * @param evidence 已检索到的资料，按排名
     */
    public Verdict check(String question, Collection<String> tried, List<RetrievedChunk> evidence) {
        Future<String> call = executor.submit(() -> chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(prompt(question, tried, evidence))
                .options(ChatOptions.builder().temperature(0.0))
                .call().content());
        try {
            Verdict verdict = parse(jsonMapper, call.get(timeout.toMillis(), TimeUnit.MILLISECONDS), tried, evidence.size());
            if (!verdict.sufficient()) {
                log.info("资料不足以回答：{}，缺少：{}，补充检索：{}，补看前后文：{}", question, verdict.missing(),
                        verdict.queries(), verdict.context());
            }
            return verdict;
        } catch (Exception e) {
            call.cancel(true);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("判断资料是否足够失败，不再补充检索：{}", e.getMessage());
            return Verdict.SUFFICIENT;
        }
    }

    static String prompt(String question, Collection<String> tried, List<RetrievedChunk> evidence) {
        StringBuilder sb = new StringBuilder("【问题】\n").append(question).append("\n\n【已经试过的检索查询】\n");
        tried.forEach(q -> sb.append("- ").append(q.replace('\n', ' ')).append('\n'));
        sb.append("\n【检索到的资料】\n");
        if (evidence.isEmpty()) {
            sb.append("（没有检索到相关资料）\n");
        }
        for (int i = 0; i < evidence.size(); i++) {
            RetrievedChunk c = evidence.get(i);
            String content = c.content().length() > EVIDENCE_CHARS ? c.content().substring(0, EVIDENCE_CHARS) + "…" : c.content();
            sb.append('[').append(i + 1).append("] ").append(c.filename());
            if (c.headingPath() != null) {
                sb.append(" ｜ ").append(c.headingPath());
            }
            if (c.location() != null) {
                sb.append(" ｜ ").append(c.location());
            }
            sb.append('\n').append(content).append("\n\n");
        }
        return sb.toString();
    }

    /** 取第一个 { 到最后一个 } 解析；不是 false、或既没有可用的新查询也没有可用的前后文请求时都当作资料足够 */
    static Verdict parse(JsonMapper jsonMapper, String content, Collection<String> tried, int evidenceCount) {
        int start = content == null ? -1 : content.indexOf('{');
        int end = content == null ? -1 : content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Verdict.SUFFICIENT;
        }
        RawVerdict raw;
        try {
            raw = jsonMapper.readValue(content.substring(start, end + 1), RawVerdict.class);
        } catch (RuntimeException e) {
            return Verdict.SUFFICIENT;
        }
        if (!Boolean.FALSE.equals(raw.sufficient())) {
            return Verdict.SUFFICIENT;
        }
        List<String> queries = Objects.requireNonNullElse(raw.queries(), List.<String>of()).stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(StringUtils::hasText)
                .map(q -> q.length() > MAX_QUERY_CHARS ? q.substring(0, MAX_QUERY_CHARS) : q)
                .filter(q -> !tried.contains(q))
                .distinct()
                .limit(MAX_QUERIES)
                .toList();
        List<ContextRequest> context = Objects.requireNonNullElse(raw.context(), List.<RawContext>of()).stream()
                .filter(c -> c != null && c.index() != null && c.index() >= 1 && c.index() <= evidenceCount
                        && ("before".equals(c.direction()) || "after".equals(c.direction())))
                .map(c -> new ContextRequest(c.index(), "before".equals(c.direction())))
                .distinct()
                .limit(MAX_CONTEXT_REQUESTS)
                .toList();
        return queries.isEmpty() && context.isEmpty() ? Verdict.SUFFICIENT
                : new Verdict(false, Optional.ofNullable(raw.missing()).map(String::strip).orElse(null), queries, context);
    }
}
