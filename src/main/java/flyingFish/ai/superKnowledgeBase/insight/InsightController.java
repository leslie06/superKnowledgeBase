package flyingFish.ai.superKnowledgeBase.insight;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api")
public class InsightController {

    private static final int MAX_DAYS = 365;

    private final InsightService insightService;
    private final EvalCaseService evalCaseService;

    public InsightController(InsightService insightService, EvalCaseService evalCaseService) {
        this.insightService = insightService;
        this.evalCaseService = evalCaseService;
    }

    /** 最近 days 天的问答统计和坏案例 */
    @GetMapping("/knowledge-bases/{kbId}/insights")
    public InsightService.Overview overview(@PathVariable long kbId, @RequestParam(defaultValue = "30") int days) {
        return insightService.overview(kbId, Math.clamp(days, 1, MAX_DAYS));
    }

    @GetMapping("/knowledge-bases/{kbId}/eval-cases")
    public List<EvalCase> evalCases(@PathVariable long kbId) {
        return evalCaseService.list(kbId);
    }

    @PostMapping("/knowledge-bases/{kbId}/eval-cases")
    @ResponseStatus(HttpStatus.CREATED)
    public EvalCase createEvalCase(@PathVariable long kbId, @RequestBody EvalCase draft) {
        return evalCaseService.create(kbId, draft);
    }

    @DeleteMapping("/eval-cases/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEvalCase(@PathVariable long id) {
        evalCaseService.delete(id);
    }

    /** 评测脚本可以直接读取：python3 scripts/eval_retrieval.py --kb-id 12 --csv http://localhost:8080/api/knowledge-bases/12/eval-cases.csv */
    @GetMapping("/knowledge-bases/{kbId}/eval-cases.csv")
    public ResponseEntity<byte[]> evalCasesCsv(@PathVariable long kbId) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"eval-cases-kb" + kbId + ".csv\"")
                .body(evalCaseService.csv(kbId).getBytes(StandardCharsets.UTF_8));
    }
}
