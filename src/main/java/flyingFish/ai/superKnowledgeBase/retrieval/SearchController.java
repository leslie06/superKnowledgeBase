package flyingFish.ai.superKnowledgeBase.retrieval;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

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
     * @param followUp    资料不够时是否补充检索（与问答一致），不传时为 true
     */
    public record SearchRequest(String query, Integer topK, String mode, Boolean rerank, Boolean rewrite,
                                List<String> folders, List<Long> documentIds, List<HistoryTurn> history,
                                Boolean plan, Boolean followUp) {
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
    /** 响应头：补充检索用过的查询，换行分隔、URL 编码；没有补查时为空 */
    static final String FOLLOW_UPS_HEADER = "X-Follow-Ups";

    private final Retriever retriever;
    private final QueryRewriter queryRewriter;
    private final RetrievalPipeline pipeline;

    public SearchController(Retriever retriever, QueryRewriter queryRewriter, RetrievalPipeline pipeline) {
        this.retriever = retriever;
        this.queryRewriter = queryRewriter;
        this.pipeline = pipeline;
    }

    @PostMapping
    public ResponseEntity<List<RetrievedChunk>> search(@PathVariable long kbId, @RequestBody SearchRequest request) {
        if (request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("查询内容不能为空");
        }
        int topK = request.topK() == null ? 10 : Math.clamp(request.topK(), 1, 50);
        List<QueryRewriter.Turn> turns = turns(request.history());
        SearchScope scope = SearchScope.of(request.folders(), request.documentIds());
        boolean rewrite = !Boolean.FALSE.equals(request.rewrite());
        Retriever.Mode mode = mode(request.mode());
        // 关闭改写、单路召回或关闭精排时只检索一次，用于对比各环节的效果
        if (!rewrite || mode != Retriever.Mode.HYBRID || Boolean.FALSE.equals(request.rerank())) {
            String query = rewrite ? queryRewriter.searchQuery(turns, request.query()) : request.query();
            return response(query, QueryPlanner.Plan.SINGLE, List.of(),
                    retriever.search(kbId, query, scope, topK, mode, request.rerank()));
        }
        // 与问答一致：改写与规划并行、汇总类问题多路检索、资料不够时补充检索
        RetrievalPipeline.Result result = pipeline.run(kbId, turns, request.query(), scope, new RetrievalPipeline.Options(
                !Boolean.FALSE.equals(request.plan()), !Boolean.FALSE.equals(request.followUp()), topK));
        // 普通问题且没有补查时返回不过滤的原始结果（看得到分数和不相关的片段），否则返回问答实际使用的相关片段
        return response(result.searchQuery(), result.plan(), result.followUps(),
                result.plan().aggregate() || !result.followUps().isEmpty() ? result.hits()
                        : result.raw().stream().limit(topK).toList());
    }

    private static ResponseEntity<List<RetrievedChunk>> response(String query, QueryPlanner.Plan plan,
                                                                 List<RetrievalPipeline.FollowUp> followUps,
                                                                 List<RetrievedChunk> chunks) {
        String followUpQueries = followUps.stream().flatMap(f -> f.queries().stream()).collect(Collectors.joining("\n"));
        return ResponseEntity.ok()
                .header(SEARCH_QUERY_HEADER, URLEncoder.encode(query, StandardCharsets.UTF_8))
                .header(PLAN_HEADER, plan.aggregate() ? "AGGREGATE" : "SINGLE")
                .header(SUB_QUERIES_HEADER, URLEncoder.encode(String.join("\n", plan.subQueries()), StandardCharsets.UTF_8))
                .header(FOLLOW_UPS_HEADER, URLEncoder.encode(followUpQueries, StandardCharsets.UTF_8))
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
