package flyingFish.ai.superKnowledgeBase.retrieval;

import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 检索调试接口：只看召回结果和分数，不调用 LLM。
 */
@RestController
@RequestMapping("/api/knowledge-bases/{kbId}/search")
public class SearchController {

    public record SearchRequest(String query, Integer topK) {
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
        return retriever.search(kbId, request.query(), topK);
    }
}
