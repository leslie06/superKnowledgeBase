package flyingFish.ai.superKnowledgeBase.chat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * @param verification 回答核对结果，没有核对时为 null
 * @param feedback     用户评价，没有评价时为 null
 * @param timings      回答的各环节耗时（毫秒）：rewriteMs 查询改写、retrieveMs 召回与精排、firstTokenMs 首字、answerMs 生成完成；
 *                     用户消息和记录耗时之前的回答为空
 * @param subQueries   汇总类问题拆出的子问题（多步检索），普通问题为空
 */
public record ChatMessage(Long id, Long conversationId, String role, String content, List<Citation> citations,
                          Verification verification, Feedback feedback, Map<String, Long> timings,
                          List<String> subQueries, OffsetDateTime createdAt) {

    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";
}
