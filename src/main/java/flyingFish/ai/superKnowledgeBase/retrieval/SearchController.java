package flyingFish.ai.superKnowledgeBase.retrieval;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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
     */
    public record SearchRequest(String query, Integer topK, String mode, Boolean rerank, Boolean rewrite,
                                List<String> folders, List<Long> documentIds, List<HistoryTurn> history) {
    }

    /** 一轮问答 */
    public record HistoryTurn(String question, String answer) {
    }

    /** 响应头：改写后的检索文本（URL 编码），评测多轮对话时用来查看改写是否正确 */
    static final String SEARCH_QUERY_HEADER = "X-Search-Query";

    private final Retriever retriever;
    private final QueryRewriter queryRewriter;

    public SearchController(Retriever retriever, QueryRewriter queryRewriter) {
        this.retriever = retriever;
        this.queryRewriter = queryRewriter;
    }

    @PostMapping
    public ResponseEntity<List<RetrievedChunk>> search(@PathVariable long kbId, @RequestBody SearchRequest request) {
        if (request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("查询内容不能为空");
        }
        int topK = request.topK() == null ? 10 : Math.clamp(request.topK(), 1, 50);
        String query = Boolean.FALSE.equals(request.rewrite())
                ? request.query() : queryRewriter.searchQuery(turns(request.history()), request.query());
        List<RetrievedChunk> chunks = retriever.search(kbId, query, SearchScope.of(request.folders(), request.documentIds()),
                topK, mode(request.mode()), request.rerank());
        return ResponseEntity.ok()
                .header(SEARCH_QUERY_HEADER, URLEncoder.encode(query, StandardCharsets.UTF_8))
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
