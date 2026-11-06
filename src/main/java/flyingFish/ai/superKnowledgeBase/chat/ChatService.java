package flyingFish.ai.superKnowledgeBase.chat;

import flyingFish.ai.superKnowledgeBase.common.NotFoundException;
import flyingFish.ai.superKnowledgeBase.common.SkbProperties;
import flyingFish.ai.superKnowledgeBase.kb.KnowledgeBaseService;
import flyingFish.ai.superKnowledgeBase.retrieval.QueryPlanner;
import flyingFish.ai.superKnowledgeBase.retrieval.QueryRewriter;
import flyingFish.ai.superKnowledgeBase.retrieval.RetrievedChunk;
import flyingFish.ai.superKnowledgeBase.retrieval.Retriever;
import flyingFish.ai.superKnowledgeBase.retrieval.SearchScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RAG 问答：查询改写（{@link QueryRewriter}）与问题规划（{@link QueryPlanner}）并行 → 检索（汇总类问题拆成子问题多路检索）
 * → 扩展相邻片段（{@link Retriever#expand}）→ 组装带编号的参考资料
 * → LLM 流式生成（要求标注 [n] 引用）。
 * <p>
 * SSE 事件：citations（检索到的引用）→ delta（增量文本）* → done（消息 id 和各环节耗时）→ verification（回答核对结果，
 * 开启 skb.chat.verify-answers 且核对成功时才有）；出错时发送 error。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final String NOT_FOUND_ANSWER =
            "知识库中没有找到与这个问题相关的内容。可以换个问法试试，或者确认相关文件已经上传并处理完成。";
    private static final String NOT_FOUND_IN_SCOPE_ANSWER =
            "在当前选择的检索范围内没有找到与这个问题相关的内容。可以换个问法，或者在输入框上方扩大检索范围后再试。";
    /** 与 application.yaml 中的占位默认值一致：未配置 Key 时应用仍能启动，只是问答不可用 */
    private static final String NO_API_KEY = "not-configured";
    private static final String DEFAULT_TITLE = "新对话";
    private static final int TITLE_MAX_CHARS = 30;

    private static final String ANSWER_SYSTEM_PROMPT = """
            你是一个严谨的知识库问答助手，根据用户提供的【参考资料】回答问题。
            规则：
            1. 只依据参考资料作答，不要编造资料中没有的信息；资料不足以回答时，直接说明"知识库中没有找到相关信息"，可以给出资料中已有的相关部分。
            2. 不要用自己的知识补充资料之外的内容：资料没有写明的数字、期限、计算标准、法律后果、编号或术语的含义，不要推断或解释；
               确实需要提及时，写明"资料中没有说明"。
            3. 要基于资料做合理的理解和推理：问题的说法和资料不同（口语、近义词、古籍的旧式说法，如"先头浑家"就是原配妻子）时，
               按问题的本意在资料中找答案；资料能推出答案时，直接给出结论并说明依据，不要因为资料没有逐字写出问题里的说法就说没有找到。
               只有资料中确实没有相关内容时才说没有找到。
            4. 在使用了资料的句子末尾标注来源编号，格式为 [1]、[2]；一句话来自多个来源时写成 [1][3]。不要在末尾单独列出参考文献。
            5. 用中文回答，先给出结论，再展开说明；内容较多时使用 Markdown 列表或表格。
            """;

    /** 汇总类问题附在参考资料后面的说明：资料是多路检索合起来的，可能只覆盖一部分 */
    private static final String AGGREGATE_HINT = """
            【说明】这个问题需要汇总多处资料，以上参考资料来自几次检索。请逐项整理资料中涉及的内容，分别标注来源；
            资料只覆盖了一部分时，在回答中说明"以下根据检索到的资料整理，可能不完整"（已提供全文的文档不需要这样说明），
            资料中没有的项不要自行补充。

            """;

    /**
     * @param hits    检索命中的片段及分数，写入 retrieval_trace 便于排查 bad case
     * @param timings 各环节耗时（毫秒），同样写入 retrieval_trace，用于排查"回答慢"
     */
    private record PreparedChat(Conversation conversation, List<ChatMessage> history, String question,
                                String searchQuery, QueryPlanner.Plan plan, List<String> fullTextDocuments,
                                List<Citation> citations,
                                List<Map<String, Object>> hits, Map<String, Long> timings) {
    }

    private final ConversationRepository conversations;
    private final KnowledgeBaseService knowledgeBaseService;
    private final Retriever retriever;
    private final QueryRewriter queryRewriter;
    private final QueryPlanner queryPlanner;
    private final ExecutorService planExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final AnswerVerifier answerVerifier;
    private final ChatClient chatClient;
    private final JsonMapper jsonMapper;
    private final int historyMessages;
    private final int maxContextChars;
    private final int aggregateContextChars;
    private final int fullTextChars;
    private final int expandChars;
    private final boolean verifyAnswers;
    private final boolean apiKeyConfigured;

    public ChatService(ConversationRepository conversations, KnowledgeBaseService knowledgeBaseService,
                       Retriever retriever, QueryRewriter queryRewriter, QueryPlanner queryPlanner,
                       AnswerVerifier answerVerifier,
                       ChatClient.Builder chatClientBuilder,
                       JsonMapper jsonMapper,
                       SkbProperties properties, @Value("${spring.ai.deepseek.api-key:}") String apiKey) {
        this.conversations = conversations;
        this.knowledgeBaseService = knowledgeBaseService;
        this.retriever = retriever;
        this.queryRewriter = queryRewriter;
        this.queryPlanner = queryPlanner;
        this.answerVerifier = answerVerifier;
        this.chatClient = chatClientBuilder.build();
        this.jsonMapper = jsonMapper;
        this.historyMessages = properties.chat().historyMessages();
        this.maxContextChars = properties.chat().maxContextChars();
        this.aggregateContextChars = properties.chat().aggregateContextChars();
        this.fullTextChars = properties.chat().fullTextChars();
        this.expandChars = properties.chat().expandChars();
        this.verifyAnswers = properties.chat().verifyAnswers();
        this.apiKeyConfigured = StringUtils.hasText(apiKey) && !NO_API_KEY.equals(apiKey) && !"sk-xxxx".equals(apiKey);
    }

    // ---------- 会话管理 ----------

    public List<Conversation> list(long kbId) {
        knowledgeBaseService.get(kbId);
        return conversations.findByKb(kbId);
    }

    public Conversation create(long kbId, String title, SearchScope scope) {
        knowledgeBaseService.get(kbId);
        return get(conversations.insert(kbId, StringUtils.hasText(title) ? title.trim() : DEFAULT_TITLE, scope));
    }

    public Conversation get(long id) {
        return conversations.findById(id).orElseThrow(() -> new NotFoundException("会话不存在：" + id));
    }

    public Conversation rename(long id, String title) {
        get(id);
        if (!StringUtils.hasText(title)) {
            throw new IllegalArgumentException("标题不能为空");
        }
        conversations.updateTitle(id, title.trim());
        return get(id);
    }

    /** 修改检索范围，之后的提问按新范围检索；已有的回答不受影响 */
    public Conversation updateScope(long id, SearchScope scope) {
        get(id);
        conversations.updateScope(id, scope);
        return get(id);
    }

    public void delete(long id) {
        get(id);
        conversations.delete(id);
    }

    /** 评价回答；rating 为空时清除评价。没用时必须给出原因 */
    public Feedback feedback(long messageId, String rating, String reason, String note) {
        String role = conversations.findMessageRole(messageId)
                .orElseThrow(() -> new NotFoundException("消息不存在：" + messageId));
        if (!ChatMessage.ASSISTANT.equals(role)) {
            throw new IllegalArgumentException("只能评价回答");
        }
        if (!StringUtils.hasText(rating)) {
            conversations.updateFeedback(messageId, null);
            return null;
        }
        Feedback feedback;
        if (Feedback.UP.equals(rating)) {
            feedback = new Feedback(Feedback.UP, null, StringUtils.hasText(note) ? note.strip() : null);
        } else if (Feedback.DOWN.equals(rating)) {
            // Set.of 的 contains(null) 会抛空指针，先判空
            if (reason == null || !Feedback.REASONS.contains(reason)) {
                throw new IllegalArgumentException("请选择原因：" + String.join("、", Feedback.REASONS));
            }
            feedback = new Feedback(Feedback.DOWN, reason, StringUtils.hasText(note) ? note.strip() : null);
        } else {
            throw new IllegalArgumentException("rating 只能是 UP 或 DOWN");
        }
        conversations.updateFeedback(messageId, feedback);
        return feedback;
    }

    public List<ChatMessage> messages(long id) {
        get(id);
        return conversations.findMessages(id);
    }

    // ---------- 问答 ----------

    public Flux<ServerSentEvent<String>> chat(long conversationId, String question) {
        if (!StringUtils.hasText(question)) {
            return Flux.just(event("error", Map.of("message", "问题不能为空")));
        }
        if (!apiKeyConfigured) {
            return Flux.just(event("error", Map.of("message", "未配置 DeepSeek API Key，请设置环境变量 DEEPSEEK_API_KEY 后重启服务")));
        }
        return Mono.fromCallable(() -> prepare(conversationId, question.trim()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(this::answer)
                .onErrorResume(e -> {
                    log.warn("问答失败，conversationId={}", conversationId, e);
                    return Flux.just(event("error", Map.of("message", "回答生成失败：" + e.getMessage())));
                });
    }

    private PreparedChat prepare(long conversationId, String question) {
        Conversation conversation = get(conversationId);
        List<ChatMessage> history = conversations.findRecentMessages(conversationId, historyMessages);
        conversations.insertMessage(conversationId, ChatMessage.USER, question, null, null);
        if (history.isEmpty() && DEFAULT_TITLE.equals(conversation.title())) {
            conversations.updateTitle(conversationId,
                    question.length() > TITLE_MAX_CHARS ? question.substring(0, TITLE_MAX_CHARS) + "…" : question);
        }
        List<QueryRewriter.Turn> turns = history.stream()
                .map(m -> new QueryRewriter.Turn(ChatMessage.USER.equals(m.role()), m.content()))
                .toList();
        long start = System.nanoTime();
        // 问题规划和查询改写各是一次 LLM 调用，并行进行，普通问题的耗时不变
        CompletableFuture<QueryPlanner.Plan> planning =
                CompletableFuture.supplyAsync(() -> queryPlanner.plan(turns, question), planExecutor);
        String searchQuery = queryRewriter.searchQuery(turns, question);
        QueryPlanner.Plan plan = planning.join();
        long rewrittenAt = System.nanoTime();
        List<RetrievedChunk> chunks = plan.aggregate()
                ? retriever.retrieveAggregate(conversation.kbId(), searchQuery, plan.subQueries(), conversation.scope())
                : retriever.retrieve(conversation.kbId(), searchQuery, conversation.scope());
        // 小文档读全文，其余扩展相邻片段（相邻的命中片段会并成一条）；trace 仍按原始命中记录
        Retriever.Context context = retriever.context(chunks, expandChars, fullTextChars);
        Map<String, Long> timings = new LinkedHashMap<>();
        timings.put("rewriteMs", (rewrittenAt - start) / 1_000_000);
        timings.put("retrieveMs", (System.nanoTime() - rewrittenAt) / 1_000_000);
        log.info("问答准备完成：查询改写 {} ms，检索 {} ms{}{}", timings.get("rewriteMs"), timings.get("retrieveMs"),
                plan.aggregate() ? "（汇总类问题，" + plan.subQueries().size() + " 个子问题）" : "",
                context.fullTextDocuments().isEmpty() ? "" : "，读全文：" + context.fullTextDocuments());
        int budget = (plan.aggregate() ? aggregateContextChars : maxContextChars) + fullTextChars;
        return new PreparedChat(conversation, history, question, searchQuery, plan, context.fullTextDocuments(),
                toCitations(context.chunks(), budget), trace(chunks), timings);
    }

    private Flux<ServerSentEvent<String>> answer(PreparedChat chat) {
        Flux<ServerSentEvent<String>> head = Flux.just(event("citations", chat.citations()));
        if (chat.citations().isEmpty()) {
            String notFound = chat.conversation().scope().isAll() ? NOT_FOUND_ANSWER : NOT_FOUND_IN_SCOPE_ANSWER;
            return Flux.concat(head,
                    Flux.just(event("delta", Map.of("text", notFound))),
                    saveAnswer(chat, notFound));
        }

        StringBuilder answer = new StringBuilder();
        AtomicBoolean completed = new AtomicBoolean();
        long start = System.nanoTime();
        AtomicLong firstToken = new AtomicLong();
        Flux<ServerSentEvent<String>> body = chatClient.prompt()
                .system(ANSWER_SYSTEM_PROMPT)
                .messages(historyMessages(chat.history()))
                .user(userPrompt(chat))
                .stream()
                .content()
                .doOnNext(text -> {
                    if (firstToken.compareAndSet(0, System.nanoTime())) {
                        chat.timings().put("firstTokenMs", (firstToken.get() - start) / 1_000_000);
                    }
                    answer.append(text);
                })
                .doOnComplete(() -> {
                    completed.set(true);
                    chat.timings().put("answerMs", (System.nanoTime() - start) / 1_000_000);
                    log.info("回答生成完成：首字 {} ms，共 {} ms，{} 字", chat.timings().get("firstTokenMs"),
                            chat.timings().get("answerMs"), answer.length());
                })
                .doOnCancel(() -> {
                    // 用户中途停止生成：保留已生成的部分
                    if (!completed.get() && !answer.isEmpty()) {
                        saveAssistantMessage(chat, answer + "\n\n（已停止生成）");
                    }
                })
                .map(text -> event("delta", Map.of("text", text)));
        return Flux.concat(head, body, Flux.defer(() -> {
            Mono<Long> saved = Mono.fromCallable(() -> saveAssistantMessage(chat, answer.toString()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .cache();
            Flux<ServerSentEvent<String>> done = saved.map(id -> doneEvent(id, chat)).flux();
            return verifyAnswers ? Flux.concat(done, verify(chat, answer.toString(), saved)) : done;
        }));
    }

    /** 回答保存后再核对，结果写回消息并推给前端；核对失败时不发送事件，回答本身不受影响 */
    private Flux<ServerSentEvent<String>> verify(PreparedChat chat, String answer, Mono<Long> saved) {
        return saved.flatMap(id -> Mono.fromCallable(() -> {
                            long start = System.nanoTime();
                            Optional<Verification> result = answerVerifier.verify(chat.citations(), answer);
                            result.ifPresent(v -> {
                                conversations.updateVerification(id, v);
                                log.info("回答核对完成：{} ms，无依据 {} 处，引用错误 {} 处", (System.nanoTime() - start) / 1_000_000,
                                        v.unsupported().size(), v.citationErrors().size());
                            });
                            return result;
                        })
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(Mono::justOrEmpty)
                .map(v -> event("verification", v))
                .flux();
    }

    private Flux<ServerSentEvent<String>> saveAnswer(PreparedChat chat, String answer) {
        return Mono.fromCallable(() -> saveAssistantMessage(chat, answer))
                .subscribeOn(Schedulers.boundedElastic())
                .map(id -> doneEvent(id, chat))
                .flux();
    }

    /** done 事件带上各环节耗时，前端显示在回答下方 */
    private ServerSentEvent<String> doneEvent(long messageId, PreparedChat chat) {
        return event("done", Map.of("messageId", messageId, "timings", chat.timings(),
                "subQueries", chat.plan().subQueries(), "fullTextDocuments", chat.fullTextDocuments()));
    }

    private long saveAssistantMessage(PreparedChat chat, String answer) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("searchQuery", chat.searchQuery());
        if (!chat.conversation().scope().isAll()) {
            trace.put("scope", chat.conversation().scope());
        }
        if (chat.plan().aggregate()) {
            trace.put("subQueries", chat.plan().subQueries());
        }
        if (!chat.fullTextDocuments().isEmpty()) {
            trace.put("fullTextDocuments", chat.fullTextDocuments());
        }
        trace.put("hits", chat.hits());
        trace.put("timings", chat.timings());
        return conversations.insertMessage(chat.conversation().id(), ChatMessage.ASSISTANT, answer,
                chat.citations(), trace);
    }

    private static List<Map<String, Object>> trace(List<RetrievedChunk> chunks) {
        return chunks.stream().map(c -> {
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("chunkId", c.chunkId());
            hit.put("score", c.score());
            if (c.rerankScore() != null) {
                hit.put("rerankScore", c.rerankScore());
            }
            return hit;
        }).toList();
    }

    /** 按相关度从高到低放入上下文，超过字符预算即停止。 */
    private static List<Citation> toCitations(List<RetrievedChunk> chunks, int maxContextChars) {
        List<Citation> citations = new ArrayList<>();
        int used = 0;
        for (RetrievedChunk c : chunks) {
            if (!citations.isEmpty() && used + c.content().length() > maxContextChars) {
                break;
            }
            used += c.content().length();
            citations.add(new Citation(citations.size() + 1, c.chunkId(), c.documentId(), c.filename(),
                    c.headingPath(), c.location(), c.content(), c.score()));
        }
        return citations;
    }

    private static List<Message> historyMessages(List<ChatMessage> history) {
        List<Message> messages = new ArrayList<>();
        for (ChatMessage m : history) {
            messages.add(ChatMessage.USER.equals(m.role()) ? new UserMessage(m.content()) : new AssistantMessage(m.content()));
        }
        return messages;
    }

    private static String userPrompt(PreparedChat chat) {
        StringBuilder sb = new StringBuilder("【参考资料】\n");
        for (Citation c : chat.citations()) {
            sb.append('[').append(c.index()).append("] 来源：").append(c.filename());
            if (c.headingPath() != null) {
                sb.append(" ｜ ").append(c.headingPath());
            }
            if (c.location() != null) {
                sb.append(" ｜ ").append(c.location());
            }
            sb.append('\n').append(c.content()).append("\n\n");
        }
        if (!chat.fullTextDocuments().isEmpty()) {
            sb.append("【说明】以上参考资料包含").append(chat.fullTextDocuments().stream().map(n -> "《" + n + "》")
                    .collect(Collectors.joining())).append("的全文（按原文顺序分段），可以通读后综合判断；其余是检索到的片段。\n\n");
        }
        if (chat.plan().aggregate()) {
            sb.append(AGGREGATE_HINT);
        }
        sb.append("【问题】\n").append(chat.question());
        return sb.toString();
    }

    private ServerSentEvent<String> event(String name, Object data) {
        return ServerSentEvent.builder(jsonMapper.writeValueAsString(data)).event(name).build();
    }
}
