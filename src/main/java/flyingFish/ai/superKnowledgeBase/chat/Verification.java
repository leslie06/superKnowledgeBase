package flyingFish.ai.superKnowledgeBase.chat;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * 回答核对结果。
 *
 * @param unsupported    参考资料无法支持的内容（编造、常识补充、资料之外的推断）
 * @param citationErrors 标注了 [n] 但第 n 条资料并不支持的句子
 */
public record Verification(List<Issue> unsupported, List<Issue> citationErrors) {

    /** @param text 回答中的原句（或片段），reason 一句话说明 */
    public record Issue(String text, String reason) {
    }

    public Verification {
        unsupported = unsupported == null ? List.of() : unsupported;
        citationErrors = citationErrors == null ? List.of() : citationErrors;
    }

    @JsonIgnore
    public boolean passed() {
        return unsupported.isEmpty() && citationErrors.isEmpty();
    }
}
