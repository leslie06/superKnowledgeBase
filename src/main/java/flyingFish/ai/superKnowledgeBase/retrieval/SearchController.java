package flyingFish.ai.superKnowledgeBase.retrieval;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;

/**
 * 检索调试接口：只看召回结果、各路排名和分数，不做相关性过滤，也不调用 LLM。
 */
@RestController
@RequestMapping("/api/knowledge-bases/{kbId}/search")
public class SearchController {

    /**
     * @param mode 召回方式：hybrid（默认，向量 + 关键词融合）、vector、keyword，用于对比各路效果
     */
    public record SearchRequest(String query, Integer topK, String mode) {
    }

    private final Retriever retriever;

    public SearchController(Retriever retriever) {
        this.retriever = retriever;
    }

    @PostMapping
    public List<RetrievedChunk> search(@PathVariable long kbId, @RequestBody SearchRequest request) {
        if (request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("查询内容不能为空");
        }
        int topK = request.topK() == null ? 10 : Math.clamp(request.topK(), 1, 50);
        return retriever.search(kbId, request.query(), topK, mode(request.mode()));
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
