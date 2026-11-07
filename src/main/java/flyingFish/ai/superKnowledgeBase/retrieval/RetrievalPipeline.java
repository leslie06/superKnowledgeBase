package flyingFish.ai.superKnowledgeBase.retrieval;

import flyingFish.ai.superKnowledgeBase.common.SkbProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 问答的检索流程，问答和 /search 调试接口共用：查询改写与问题规划并行 → 检索（汇总类问题拆成子问题多路检索，
 * 口语、俗语的问法另按本意检索一路）
 * → 迭代检索（{@link EvidenceChecker} 判断资料不够时换说法补查，最多 skb.retrieval.max-follow-ups 轮）。
 */
@Service
public class RetrievalPipeline {

    /**
     * @param plan     是否做问题规划
     * @param followUp 是否在资料不够时补充检索
     * @param rawTopK  大于 0 时额外返回不过滤相关性的原始结果（调试接口用于查看分数），问答传 0
     */
    public record Options(boolean plan, boolean followUp, int rawTopK) {

        public static final Options CHAT = new Options(true, true, 0);
    }

    /**
     * 一轮补充检索：缺什么、查了什么、补看了哪些资料的前后文、补进几个新片段
     *
     * @param context 补看前后文的资料，如"《金瓶梅》第655-656页的前文"
     */
    public record FollowUp(int round, String missing, List<String> queries, List<String> context, int added) {
    }

    /** 精排不可用、补看前后文按距离取时，每处最多补进这么多字；也是问答为每处前后文预留的参考资料预算 */
    public static final int SURROUNDING_CHARS = 5_000;

    /**
     * @param hits      交给问答的相关片段：普通问题最多 top-k 个，汇总类问题和补充检索后更多
     * @param raw       调试接口用的结果：普通问题且没有补充检索时是不过滤的原始结果，否则同 hits
     * @param timings   rewriteMs 改写与规划、retrieveMs 首轮检索、followUpMs 补充检索（有补查时）
     */
    public record Result(String searchQuery, QueryPlanner.Plan plan, List<RetrievedChunk> hits, List<RetrievedChunk> raw,
                         List<FollowUp> followUps, Map<String, Long> timings) {
    }

    private final Retriever retriever;
    private final QueryRewriter queryRewriter;
    private final QueryPlanner queryPlanner;
    private final EvidenceChecker evidenceChecker;
    private final int maxFollowUps;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public RetrievalPipeline(Retriever retriever, QueryRewriter queryRewriter, QueryPlanner queryPlanner,
                             EvidenceChecker evidenceChecker, SkbProperties properties) {
        this.retriever = retriever;
        this.queryRewriter = queryRewriter;
        this.queryPlanner = queryPlanner;
        this.evidenceChecker = evidenceChecker;
        this.maxFollowUps = properties.retrieval().maxFollowUps();
    }

    /**
     * 首轮检索的结果，以及判断资料、补查时要用的上下文
     *
     * @param tried            已经试过的检索查询，补查不重复
     * @param checkQuestion    判断资料够不够时的问题：有本意时附上本意，多轮对话时用补全后的检索文本
     * @param surroundingQuery 挑选前后文时的问题
     * @param timings          rewriteMs、retrieveMs
     */
    public record FirstRound(long kbId, SearchScope scope, String searchQuery, QueryPlanner.Plan plan,
                             List<RetrievedChunk> hits, List<RetrievedChunk> raw, Set<String> tried, String checkQuestion,
                             String surroundingQuery, Map<String, Long> timings) {
    }

    /** 完整流程：首轮检索 → 判断资料够不够 → 补查。/search 调试接口用；问答把判断和生成回答并行（见 ChatService） */
    public Result run(long kbId, List<QueryRewriter.Turn> history, String question, SearchScope scope, Options options) {
        FirstRound first = first(kbId, history, question, scope, options);
        if (!options.followUp()) {
            return finish(first, EvidenceChecker.Verdict.SUFFICIENT);
        }
        long start = System.nanoTime();
        EvidenceChecker.Verdict verdict = check(first);
        first.timings().put("checkMs", (System.nanoTime() - start) / 1_000_000);
        return finish(first, verdict);
    }

    /** 查询改写与问题规划并行，然后首轮检索（汇总类问题拆成子问题多路检索，口语的问法另按本意检索一路） */
    public FirstRound first(long kbId, List<QueryRewriter.Turn> history, String question, SearchScope scope,
                            Options options) {
        long start = System.nanoTime();
        // 问题规划和查询改写各是一次 LLM 调用，并行进行，普通问题的耗时不变
        CompletableFuture<QueryPlanner.Plan> planning = options.plan()
                ? CompletableFuture.supplyAsync(() -> queryPlanner.plan(history, question), executor)
                : CompletableFuture.completedFuture(QueryPlanner.Plan.SINGLE);
        String searchQuery = queryRewriter.searchQuery(history, question);
        QueryPlanner.Plan plan = planning.join();
        long rewrittenAt = System.nanoTime();

        List<RetrievedChunk> hits;
        List<RetrievedChunk> raw;
        if (plan.aggregate()) {
            hits = retriever.retrieveAggregate(kbId, searchQuery, plan.subQueries(), scope);
            raw = hits;
        } else if (plan.intent() != null) {
            // 口语、俗语的问法照字面检索会找偏（"死在谁的床上"检索到的全是"床上"的情节），本意也检索一路，轮流合并
            hits = retriever.retrieveAggregate(kbId, searchQuery, List.of(plan.intent()), scope);
            raw = hits;
        } else {
            raw = retriever.search(kbId, searchQuery, scope, Math.max(options.rawTopK(), retriever.topK()),
                    Retriever.Mode.HYBRID, null);
            hits = raw.stream().filter(RetrievedChunk::relevant).limit(retriever.topK()).toList();
        }
        long retrievedAt = System.nanoTime();

        Set<String> tried = new LinkedHashSet<>(Stream.concat(Stream.of(searchQuery), plan.subQueries().stream()).toList());
        if (plan.intent() != null) {
            tried.add(plan.intent());
        }
        // 有本意时按本意判断；多轮对话时原问题可能是"那字典呢"，判断用补全后的检索文本
        String checkQuestion = plan.intent() != null ? question + "（意思是：" + plan.intent() + "）"
                : history.isEmpty() ? question : searchQuery;
        // 挑前后文用的问题：有本意时用本意，"西门庆因哪个女人而死"比"死在谁的床上"更能挑出死因那几段
        String surroundingQuery = plan.intent() != null ? plan.intent() : history.isEmpty() ? question : searchQuery;
        Map<String, Long> timings = new LinkedHashMap<>();
        timings.put("rewriteMs", (rewrittenAt - start) / 1_000_000);
        timings.put("retrieveMs", (retrievedAt - rewrittenAt) / 1_000_000);
        return new FirstRound(kbId, scope, searchQuery, plan, hits, raw, tried, checkQuestion, surroundingQuery, timings);
    }

    /** 判断首轮资料够不够回答 */
    public EvidenceChecker.Verdict check(FirstRound first) {
        return evidenceChecker.check(first.checkQuestion(), first.tried(), first.hits());
    }

    /**
     * 按判断结果补查：资料足够时直接返回首轮结果；不够时换说法补查、补看前后文，
     * 还有轮数时再判断一次，最多 maxFollowUps 轮。补查耗时记入 followUpMs
     */
    public Result finish(FirstRound first, EvidenceChecker.Verdict verdict) {
        List<RetrievedChunk> hits = first.hits();
        List<RetrievedChunk> raw = first.raw();
        Set<String> tried = new LinkedHashSet<>(first.tried());
        List<FollowUp> followUps = new ArrayList<>();
        long start = System.nanoTime();
        for (int round = 1; round <= maxFollowUps && !verdict.sufficient(); round++) {
            tried.addAll(verdict.queries());
            List<RetrievedChunk> merged = new ArrayList<>(hits);
            // 前后文插在那段资料的前面或后面，模型读到的是连贯的原文
            List<String> context = new ArrayList<>();
            int before = merged.size();
            for (EvidenceChecker.ContextRequest request : verdict.context()) {
                RetrievedChunk anchor = hits.get(request.index() - 1);
                Set<Long> known = merged.stream().map(RetrievedChunk::chunkId).collect(Collectors.toSet());
                List<RetrievedChunk> around = retriever.surrounding(anchor, request.before(), first.surroundingQuery(),
                        SURROUNDING_CHARS).stream().filter(c -> !known.contains(c.chunkId())).toList();
                int position = merged.indexOf(anchor);
                merged.addAll(request.before() ? position : position + 1, around);
                context.add("《" + anchor.filename() + "》" + Objects.requireNonNullElse(anchor.location(), "")
                        + (request.before() ? "的前文" : "的后文"));
            }
            if (!verdict.queries().isEmpty()) {
                merged.addAll(retriever.retrieveMore(first.kbId(), verdict.queries(), first.scope(), merged));
            }
            int added = merged.size() - before;
            followUps.add(new FollowUp(round, verdict.missing(), verdict.queries(), context, added));
            if (added == 0) {
                break;
            }
            hits = List.copyOf(merged);
            raw = hits;
            if (round < maxFollowUps) {
                verdict = evidenceChecker.check(first.checkQuestion(), tried, hits);
            }
        }
        Map<String, Long> timings = new LinkedHashMap<>(first.timings());
        if (!followUps.isEmpty()) {
            timings.put("followUpMs", (System.nanoTime() - start) / 1_000_000);
        }
        return new Result(first.searchQuery(), first.plan(), hits, raw, followUps, timings);
    }
}
