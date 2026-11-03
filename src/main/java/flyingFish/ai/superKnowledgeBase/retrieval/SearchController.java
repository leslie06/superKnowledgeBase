package flyingFish.ai.superKnowledgeBase.retrieval;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;

/**
 * 检索调试接口：只看召回结果、各路排名和分数，不做相关性过滤（结果中的 relevant 标出问答时会不会用到），也不调用 LLM。
 */
@RestController
@RequestMapping("/api/knowledge-bases/{kbId}/search")
public class SearchController {

    /**
     * @param mode   召回方式：hybrid（默认，向量 + 关键词融合）、vector、keyword，用于对比各路效果
     * @param rerank  是否精排，不传时按配置；只在 hybrid 模式下生效
     * @param rewrite 是否先做查询改写（与问答一致，会调用 LLM），不传时为 true
     */
    public record SearchRequest(String query, Integer topK, String mode, Boolean rerank, Boolean rewrite) {
    }

    private final Retriever retriever;
    private final QueryRewriter queryRewriter;

    public SearchController(Retriever retriever, QueryRewriter queryRewriter) {
        this.retriever = retriever;
        this.queryRewriter = queryRewriter;
    }

    @PostMapping
    public List<RetrievedChunk> search(@PathVariable long kbId, @RequestBody SearchRequest request) {
        if (request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("查询内容不能为空");
        }
        int topK = request.topK() == null ? 10 : Math.clamp(request.topK(), 1, 50);
        String query = Boolean.FALSE.equals(request.rewrite())
                ? request.query() : queryRewriter.searchQuery(List.of(), request.query());
        return retriever.search(kbId, query, topK, mode(request.mode()), request.rerank());
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
