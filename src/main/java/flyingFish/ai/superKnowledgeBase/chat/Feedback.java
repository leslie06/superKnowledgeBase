package flyingFish.ai.superKnowledgeBase.chat;

import java.util.Set;

/**
 * 用户对回答的评价。
 *
 * @param rating UP 有用 / DOWN 没用
 * @param reason 没用的原因：NOT_FOUND 没找到 / WRONG 答错了 / INCOMPLETE 不完整 / CITATION 引用不对 / OTHER 其他；有用时为 null
 * @param note   补充说明，如正确答案在哪个文件
 */
public record Feedback(String rating, String reason, String note) {

    public static final String UP = "UP";
    public static final String DOWN = "DOWN";
    public static final Set<String> REASONS = Set.of("NOT_FOUND", "WRONG", "INCOMPLETE", "CITATION", "OTHER");
}
