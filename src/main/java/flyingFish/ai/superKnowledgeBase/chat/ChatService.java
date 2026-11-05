package flyingFish.ai.superKnowledgeBase.chat;

import flyingFish.ai.superKnowledgeBase.common.NotFoundException;
import flyingFish.ai.superKnowledgeBase.common.SkbProperties;
import flyingFish.ai.superKnowledgeBase.kb.KnowledgeBaseService;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RAG 问答：查询改写（{@link QueryRewriter}） → 检索 → 扩展相邻片段（{@link Retriever#expand}）→ 组装带编号的参考资料
 * → LLM 流式生成（要求标注 [n] 引用）。
 * <p>
 * SSE 事件：citations（检索到的引用）→ delta（增量文本）* → done（消息 id）；出错时发送 error。
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
            2. 在使用了资料的句子末尾标注来源编号，格式为 [1]、[2]；一句话来自多个来源时写成 [1][3]。不要在末尾单独列出参考文献。
            3. 用中文回答，先给出结论，再展开说明；内容较多时使用 Markdown 列表或表格。
            """;

    /**
     * @param hits    检索命中的片段及分数，写入 retrieval_trace 便于排查 bad case
     * @param timings 各环节耗时（毫秒），同样写入 retrieval_trace，用于排查"回答慢"
     */
    private record PreparedChat(Conversation conversation, List<ChatMessage> history, String question,
                                String searchQuery, List<Citation> citations, List<Map<String, Object>> hits,
                                Map<String, Long> timings) {
    }

    private final ConversationRepository conversations;
    private final KnowledgeBaseService knowledgeBaseService;
    private final Retriever retriever;
    private final QueryRewriter queryRewriter;
    private final ChatClient chatClient;
    private final JsonMapper jsonMapper;
    private final int historyMessages;
    private final int maxContextChars;
    private final int expandChars;
    private final boolean apiKeyConfigured;

    public ChatService(ConversationRepository conversations, KnowledgeBaseService knowledgeBaseService,
                       Retriever retriever, QueryRewriter queryRewriter, ChatClient.Builder chatClientBuilder,
                       JsonMapper jsonMapper,
                       SkbProperties properties, @Value("${spring.ai.deepseek.api-key:}") String apiKey) {
        this.conversations = conversations;
        this.knowledgeBaseService = knowledgeBaseService;
        this.retriever = retriever;
        this.queryRewriter = queryRewriter;
        this.chatClient = chatClientBuilder.build();
        this.jsonMapper = jsonMapper;
        this.historyMessages = properties.chat().historyMessages();
        this.maxContextChars = properties.chat().maxContextChars();
        this.expandChars = properties.chat().expandChars();
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
        String searchQuery = queryRewriter.searchQuery(turns, question);
        long rewrittenAt = System.nanoTime();
        List<RetrievedChunk> chunks = retriever.retrieve(conversation.kbId(), searchQuery, conversation.scope());
        // 扩展后相邻的命中片段会并成一条，trace 仍按原始命中记录
        List<RetrievedChunk> context = retriever.expand(chunks, expandChars);
        Map<String, Long> timings = new LinkedHashMap<>();
        timings.put("rewriteMs", (rewrittenAt - start) / 1_000_000);
        timings.put("retrieveMs", (System.nanoTime() - rewrittenAt) / 1_000_000);
        log.info("问答准备完成：查询改写 {} ms，检索 {} ms", timings.get("rewriteMs"), timings.get("retrieveMs"));
        return new PreparedChat(conversation, history, question, searchQuery, toCitations(context), trace(chunks),
                timings);
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
        return Flux.concat(head, body, Flux.defer(() -> saveAnswer(chat, answer.toString())));
    }

    private Flux<ServerSentEvent<String>> saveAnswer(PreparedChat chat, String answer) {
        return Mono.fromCallable(() -> saveAssistantMessage(chat, answer))
                .subscribeOn(Schedulers.boundedElastic())
                .map(id -> event("done", Map.of("messageId", id)))
                .flux();
    }

    private long saveAssistantMessage(PreparedChat chat, String answer) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("searchQuery", chat.searchQuery());
        if (!chat.conversation().scope().isAll()) {
            trace.put("scope", chat.conversation().scope());
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
    private List<Citation> toCitations(List<RetrievedChunk> chunks) {
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
        sb.append("【问题】\n").append(chat.question());
        return sb.toString();
    }

    private ServerSentEvent<String> event(String name, Object data) {
        return ServerSentEvent.builder(jsonMapper.writeValueAsString(data)).event(name).build();
    }
}
