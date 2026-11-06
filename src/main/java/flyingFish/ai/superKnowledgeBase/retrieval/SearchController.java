package flyingFish.ai.superKnowledgeBase.retrieval;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 检索调试接口：只看召回结果、各路排名和分数，不做相关性过滤（结果中的 relevant 标出问答时会不会用到），也不调用 LLM。
 */
@RestController
@RequestMapping("/api/knowledge-bases/{kbId}/search")
public class SearchController {

    /**
     * @param mode        召回方式：hybrid（默认，向量 + 关键词融合）、vector、keyword，用于对比各路效果
     * @param rerank      是否精排，不传时按配置；只在 hybrid 模式下生效
     * @param rewrite     是否先做查询改写（与问答一致，会调用 LLM），不传时为 true
     * @param folders     检索范围的文件夹，与 documentIds 一起见 {@link SearchScope}；都不传时在整个知识库里检索
     * @param history     之前几轮的问答，按时间顺序；与问答一样交给查询改写，用于评测多轮对话
     * @param plan        是否先做问题规划（与问答一致，汇总类问题拆成子问题多路检索），不传时为 true；rewrite 为 false 时不规划
     */
    public record SearchRequest(String query, Integer topK, String mode, Boolean rerank, Boolean rewrite,
                                List<String> folders, List<Long> documentIds, List<HistoryTurn> history,
                                Boolean plan) {
    }

    /** 一轮问答 */
    public record HistoryTurn(String question, String answer) {
    }

    /** 响应头：改写后的检索文本（URL 编码），评测多轮对话时用来查看改写是否正确 */
    static final String SEARCH_QUERY_HEADER = "X-Search-Query";
    /** 响应头：问题类型，AGGREGATE 为汇总类问题（结果是多路检索合并后的相关片段），否则为 SINGLE */
    static final String PLAN_HEADER = "X-Plan";
    /** 响应头：汇总类问题拆出的子问题，换行分隔、URL 编码 */
    static final String SUB_QUERIES_HEADER = "X-Sub-Queries";

    private final Retriever retriever;
    private final QueryRewriter queryRewriter;
    private final QueryPlanner queryPlanner;
    private final ExecutorService planExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public SearchController(Retriever retriever, QueryRewriter queryRewriter, QueryPlanner queryPlanner) {
        this.retriever = retriever;
        this.queryRewriter = queryRewriter;
        this.queryPlanner = queryPlanner;
    }

    @PostMapping
    public ResponseEntity<List<RetrievedChunk>> search(@PathVariable long kbId, @RequestBody SearchRequest request) {
        if (request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("查询内容不能为空");
        }
        int topK = request.topK() == null ? 10 : Math.clamp(request.topK(), 1, 50);
        List<QueryRewriter.Turn> turns = turns(request.history());
        boolean rewrite = !Boolean.FALSE.equals(request.rewrite());
        // 与问答一致：问题规划和查询改写并行
        CompletableFuture<QueryPlanner.Plan> planning = rewrite && !Boolean.FALSE.equals(request.plan())
                ? CompletableFuture.supplyAsync(() -> queryPlanner.plan(turns, request.query()), planExecutor)
                : CompletableFuture.completedFuture(QueryPlanner.Plan.SINGLE);
        String query = rewrite ? queryRewriter.searchQuery(turns, request.query()) : request.query();
        QueryPlanner.Plan plan = planning.join();
        SearchScope scope = SearchScope.of(request.folders(), request.documentIds());
        // 汇总类问题返回问答实际使用的多路合并结果（都是相关片段）；单路模式和关闭精排时仍按普通检索对比
        List<RetrievedChunk> chunks = plan.aggregate() && mode(request.mode()) == Retriever.Mode.HYBRID
                && !Boolean.FALSE.equals(request.rerank())
                ? retriever.retrieveAggregate(kbId, query, plan.subQueries(), scope)
                : retriever.search(kbId, query, scope, topK, mode(request.mode()), request.rerank());
        return ResponseEntity.ok()
                .header(SEARCH_QUERY_HEADER, URLEncoder.encode(query, StandardCharsets.UTF_8))
                .header(PLAN_HEADER, plan.aggregate() ? "AGGREGATE" : "SINGLE")
                .header(SUB_QUERIES_HEADER, URLEncoder.encode(String.join("\n", plan.subQueries()), StandardCharsets.UTF_8))
                .body(chunks);
    }

    private static List<QueryRewriter.Turn> turns(List<HistoryTurn> history) {
        if (history == null) {
            return List.of();
        }
        List<QueryRewriter.Turn> turns = new ArrayList<>();
        for (HistoryTurn turn : history) {
            turns.add(new QueryRewriter.Turn(true, Objects.requireNonNullElse(turn.question(), "")));
            if (turn.answer() != null) {
                turns.add(new QueryRewriter.Turn(false, turn.answer()));
            }
        }
        return turns;
    }

    private static Retriever.Mode mode(String mode) {
        if (mode == null || mode.isBlank()) {
            return Retriever.Mode.HYBRID;
        }
        try {
            return Retriever.Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("mode 只能是 hybrid、vector 或 keyword");
        }
    }
}
