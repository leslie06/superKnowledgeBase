package flyingFish.ai.superKnowledgeBase.insight;

import java.time.OffsetDateTime;

/**
 * 从真实问答中整理出的检索评测题，字段与 scripts/eval_retrieval.py 的题集 CSV 列一一对应。
 *
 * @param scope     检索范围：文件夹路径，| 分隔；为空表示整个知识库
 * @param messageId 来源回答，回答被删除后为 null
 */
public record EvalCase(Long id, Long kbId, String question, String expectedFile, String expectedSection,
                       String expectedText, String scope, String note, Long messageId, OffsetDateTime createdAt) {
}
