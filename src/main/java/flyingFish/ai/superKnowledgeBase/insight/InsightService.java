package flyingFish.ai.superKnowledgeBase.insight;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import flyingFish.ai.superKnowledgeBase.chat.Citation;
import flyingFish.ai.superKnowledgeBase.chat.Feedback;
import flyingFish.ai.superKnowledgeBase.chat.Verification;
import flyingFish.ai.superKnowledgeBase.insight.InsightRepository.AnswerRow;
import flyingFish.ai.superKnowledgeBase.kb.KnowledgeBaseService;
import flyingFish.ai.superKnowledgeBase.retrieval.SearchScope;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/**
 * 问答分析：统计最近一段时间的回答，并列出需要关注的回答（坏案例）。评测题集已经接近满分，
 * 真实使用中哪些问题答不好要从这里找：差评、拒答、核对发现问题、精排未生效、慢。
 * 数据都来自 message 表已有的检索记录（retrieval_trace）、核对结果和用户评价，不需要额外埋点。
 */
@Service
public class InsightService {

    /** 坏案例类型；一条回答可以同时属于多类 */
    public enum Flag {
        /** 用户点了"没用" */
        DOWN,
        /** 用户点了"有用" */
        UP,
        /** 没有参考资料直接拒答，或模型看过资料后说"知识库中没有找到" */
        REFUSED,
        /** 核对发现无依据内容或引用错误 */
        VERIFY_ISSUE,
        /** 有命中片段但都没有精排分数：精排服务没启动或超时，退回了 RRF 排序 */
        UNRERANKED,
        /** 从提问到出第一个字（改写 + 检索 + 首字）超过 SLOW_MS */
        SLOW
    }

    /** @param p50 中位数，p90 第 90 百分位（毫秒）；没有数据时为 null */
    public record Timing(Long p50, Long p90) {
    }

    public record Stats(int answers, int up, int down, int refused, int verifyIssues, int unreranked, int slow,
                        Timing rewrite, Timing retrieve, Timing firstToken) {
    }

    public record Daily(LocalDate date, int answers, int down, int refused, int verifyIssues) {
    }

    /** 交给模型的一条参考资料；score 为余弦相似度，rerankScore 为精排分数（未精排为 null） */
    public record Hit(int index, Long chunkId, Long documentId, String filename, String headingPath, String location,
                      String snippet, Double score, Double rerankScore) {
    }

    /** @param subQueries 汇总类问题拆出的子问题，普通问题为空 */
    public record Case(Long messageId, Long conversationId, OffsetDateTime createdAt, String question, String answer,
                       String searchQuery, List<String> subQueries, SearchScope scope, List<Hit> hits, Map<String, Long> timings,
                       Verification verification, Feedback feedback, Set<Flag> flags) {
    }

    public record Overview(int days, Stats stats, List<Daily> daily, List<Case> cases) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Trace(String searchQuery, List<String> subQueries, List<TraceHit> hits, Map<String, Long> timings) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TraceHit(Long chunkId, Double score, Double rerankScore) {
    }

    static final long SLOW_MS = 15_000;
    private static final int MAX_ANSWER_CHARS = 1500;
    private static final int MAX_SNIPPET_CHARS = 200;
    private static final List<String> REFUSAL_MARKERS = List.of("知识库中没有找到", "在当前选择的检索范围内没有找到");
    private static final TypeReference<List<Citation>> CITATIONS = new TypeReference<>() {
    };

    private final InsightRepository insights;
    private final KnowledgeBaseService knowledgeBaseService;
    private final JsonMapper jsonMapper;

    public InsightService(InsightRepository insights, KnowledgeBaseService knowledgeBaseService, JsonMapper jsonMapper) {
        this.insights = insights;
        this.knowledgeBaseService = knowledgeBaseService;
        this.jsonMapper = jsonMapper;
    }

    public Overview overview(long kbId, int days) {
        knowledgeBaseService.get(kbId);
        List<Case> cases = insights.findAnswers(kbId, OffsetDateTime.now().minusDays(days)).stream()
                .map(this::toCase)
                .toList();
        return new Overview(days, stats(cases), daily(cases), cases);
    }

    private Case toCase(AnswerRow row) {
        List<Citation> citations = row.citations() == null ? List.of() : jsonMapper.readValue(row.citations(), CITATIONS);
        Trace trace = row.trace() == null ? new Trace(null, List.of(), List.of(), Map.of())
                : jsonMapper.readValue(row.trace(), Trace.class);
        List<TraceHit> traceHits = Objects.requireNonNullElse(trace.hits(), List.of());
        Map<String, Long> timings = Objects.requireNonNullElse(trace.timings(), Map.of());
        Map<Long, TraceHit> scores = traceHits.stream()
                .collect(Collectors.toMap(TraceHit::chunkId, Function.identity(), (a, b) -> a));
        List<Hit> hits = citations.stream().map(c -> {
            TraceHit s = scores.get(c.chunkId());
            return new Hit(c.index(), c.chunkId(), c.documentId(), c.filename(), c.headingPath(), c.location(),
                    truncate(c.content(), MAX_SNIPPET_CHARS), s != null ? s.score() : c.score(),
                    s != null ? s.rerankScore() : null);
        }).toList();
        Verification verification = row.verification() == null ? null
                : jsonMapper.readValue(row.verification(), Verification.class);
        Feedback feedback = row.feedback() == null ? null
                : new Feedback(row.feedback(), row.feedbackReason(), row.feedbackNote());
        SearchScope scope = row.scope() == null ? SearchScope.ALL : jsonMapper.readValue(row.scope(), SearchScope.class);
        return new Case(row.messageId(), row.conversationId(), row.createdAt(), row.question(),
                truncate(row.answer(), MAX_ANSWER_CHARS), trace.searchQuery(),
                Objects.requireNonNullElse(trace.subQueries(), List.of()), scope, hits, timings, verification,
                feedback, flags(row.answer(), citations, traceHits, timings, verification, feedback));
    }

    static Set<Flag> flags(String answer, List<Citation> citations, List<TraceHit> traceHits, Map<String, Long> timings,
                           Verification verification, Feedback feedback) {
        Set<Flag> flags = new LinkedHashSet<>();
        if (feedback != null) {
            flags.add(Feedback.DOWN.equals(feedback.rating()) ? Flag.DOWN : Flag.UP);
        }
        if (citations.isEmpty() || REFUSAL_MARKERS.stream().anyMatch(m -> answer != null && answer.contains(m))) {
            flags.add(Flag.REFUSED);
        }
        if (verification != null && !verification.passed()) {
            flags.add(Flag.VERIFY_ISSUE);
        }
        if (!traceHits.isEmpty() && traceHits.stream().allMatch(h -> h.rerankScore() == null)) {
            flags.add(Flag.UNRERANKED);
        }
        long waited = timings.getOrDefault("rewriteMs", 0L) + timings.getOrDefault("retrieveMs", 0L)
                + timings.getOrDefault("firstTokenMs", 0L);
        if (waited > SLOW_MS) {
            flags.add(Flag.SLOW);
        }
        return flags;
    }

    private static Stats stats(List<Case> cases) {
        ToIntFunction<Flag> count = flag -> (int) cases.stream().filter(c -> c.flags().contains(flag)).count();
        return new Stats(cases.size(), count.applyAsInt(Flag.UP), count.applyAsInt(Flag.DOWN),
                count.applyAsInt(Flag.REFUSED), count.applyAsInt(Flag.VERIFY_ISSUE), count.applyAsInt(Flag.UNRERANKED),
                count.applyAsInt(Flag.SLOW), timing(cases, "rewriteMs"), timing(cases, "retrieveMs"),
                timing(cases, "firstTokenMs"));
    }

    static Timing timing(List<Case> cases, String key) {
        long[] values = cases.stream().map(c -> c.timings().get(key)).filter(Objects::nonNull)
                .mapToLong(Long::longValue).sorted().toArray();
        if (values.length == 0) {
            return new Timing(null, null);
        }
        return new Timing(values[(values.length - 1) / 2], values[(int) ((values.length - 1) * 0.9)]);
    }

    /** 按天汇总（服务器所在时区），日期从新到旧 */
    private static List<Daily> daily(List<Case> cases) {
        Map<LocalDate, List<Case>> byDate = new TreeMap<>(Comparator.reverseOrder());
        for (Case c : cases) {
            byDate.computeIfAbsent(c.createdAt().atZoneSameInstant(ZoneId.systemDefault()).toLocalDate(),
                    d -> new ArrayList<>()).add(c);
        }
        return byDate.entrySet().stream().map(e -> {
            List<Case> day = e.getValue();
            ToIntFunction<Flag> count = flag -> (int) day.stream().filter(c -> c.flags().contains(flag)).count();
            return new Daily(e.getKey(), day.size(), count.applyAsInt(Flag.DOWN), count.applyAsInt(Flag.REFUSED),
                    count.applyAsInt(Flag.VERIFY_ISSUE));
        }).toList();
    }

    private static String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
