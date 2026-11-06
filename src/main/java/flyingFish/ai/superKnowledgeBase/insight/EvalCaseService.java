package flyingFish.ai.superKnowledgeBase.insight;

import flyingFish.ai.superKnowledgeBase.common.NotFoundException;
import flyingFish.ai.superKnowledgeBase.kb.KnowledgeBaseService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class EvalCaseService {

    /** 与 scripts/eval_retrieval.py 读取的列名一致 */
    private static final List<String> CSV_COLUMNS =
            List.of("question", "expected_file", "expected_section", "expected_text", "note", "scope");

    private final EvalCaseRepository evalCases;
    private final KnowledgeBaseService knowledgeBaseService;

    public EvalCaseService(EvalCaseRepository evalCases, KnowledgeBaseService knowledgeBaseService) {
        this.evalCases = evalCases;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    public List<EvalCase> list(long kbId) {
        knowledgeBaseService.get(kbId);
        return evalCases.findByKb(kbId);
    }

    public EvalCase create(long kbId, EvalCase draft) {
        knowledgeBaseService.get(kbId);
        if (!StringUtils.hasText(draft.question())) {
            throw new IllegalArgumentException("问题不能为空");
        }
        long id = evalCases.insert(kbId, draft.question().strip(), trim(draft.expectedFile()),
                trim(draft.expectedSection()), trim(draft.expectedText()), trim(draft.scope()), trim(draft.note()),
                draft.messageId());
        return evalCases.findByKb(kbId).stream().filter(c -> c.id() == id).findFirst().orElseThrow();
    }

    public void delete(long id) {
        if (evalCases.delete(id) == 0) {
            throw new NotFoundException("评测题不存在：" + id);
        }
    }

    /** 导出成评测脚本的题集 CSV（带 BOM，Excel 直接打开不乱码） */
    public String csv(long kbId) {
        StringBuilder sb = new StringBuilder("﻿").append(String.join(",", CSV_COLUMNS)).append("\r\n");
        for (EvalCase c : list(kbId)) {
            sb.append(Stream.of(c.question(), c.expectedFile(), c.expectedSection(), c.expectedText(), c.note(), c.scope())
                    .map(EvalCaseService::csvField)
                    .collect(Collectors.joining(","))).append("\r\n");
        }
        return sb.toString();
    }

    static String csvField(String value) {
        if (value == null) {
            return "";
        }
        return value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")
                ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    private static String trim(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}
