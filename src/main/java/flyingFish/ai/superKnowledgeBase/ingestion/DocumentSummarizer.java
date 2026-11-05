package flyingFish.ai.superKnowledgeBase.ingestion;

import flyingFish.ai.superKnowledgeBase.common.SkbProperties;
import flyingFish.ai.superKnowledgeBase.ingestion.Chunker.ChunkDraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 文档摘要：用 LLM 为整份文档写一段摘要，作为特殊片段参与检索。片段检索只能回答落在某一段里的问题，
 * "这本书讲了什么""哪些资料讲到了 MySQL"的答案分散在整份文档里，任何一个正文片段都答不全。
 * <p>
 * 交给 LLM 的是文件路径、目录（章节标题）、开头和均匀抽取的几段正文，总字数受 skb.summary.input-chars 限制：
 * 几百页的书不可能全文输入，目录最能概括结构，开头通常有前言或简介，抽样补上正文的实际内容。
 * 生成失败（未配置 Key、接口出错）时返回空，文档照常处理，只是没有摘要片段。
 */
@Service
public class DocumentSummarizer {

    private static final Logger log = LoggerFactory.getLogger(DocumentSummarizer.class);
    private static final String NO_API_KEY = "not-configured";
    /** 正文抽样的段数 */
    private static final int SAMPLES = 8;
    private static final int MAX_HEADING_CHARS = 80;
    /** 模型偶尔不遵守长度要求，过长的摘要会稀释检索 */
    private static final int MAX_SUMMARY_CHARS = 600;

    private static final String SYSTEM_PROMPT = """
            你是文档摘要助手。根据给出的文档信息（文件路径、目录、开头和正文节选）写一段摘要，
            用于知识库回答"这份文档讲什么""哪些资料涉及某个主题"这类问题。
            要求：
            1. 第一句说明这是什么文档：类型、主题，资料中写明了作者、发布方或适用对象的也写上；
            2. 接着概括主要内容，覆盖目录中的主要章节或方面；
            3. 最后单独一行，以"关键词："开头，列出 5～10 个关键词，用顿号分隔；
            4. 只依据给出的内容，不要编造；总长度不超过 300 字，不要使用 Markdown。
            """;

    private final ChatClient chatClient;
    private final boolean enabled;
    private final int inputChars;

    public DocumentSummarizer(ChatClient.Builder chatClientBuilder, SkbProperties properties,
                              @Value("${spring.ai.deepseek.api-key:}") String apiKey) {
        this.chatClient = chatClientBuilder.build();
        this.inputChars = properties.summary().inputChars();
        boolean keyConfigured = StringUtils.hasText(apiKey) && !NO_API_KEY.equals(apiKey) && !"sk-xxxx".equals(apiKey);
        this.enabled = properties.summary().enabled() && keyConfigured;
        if (properties.summary().enabled() && !keyConfigured) {
            log.info("未配置 DeepSeek Key，不生成文档摘要");
        }
    }

    public boolean available() {
        return enabled;
    }

    /**
     * @param source 文件出处：文件夹导入时是相对路径，否则是文件名
     * @param chunks 正文片段，按顺序
     */
    public Optional<String> summarize(String source, List<ChunkDraft> chunks) {
        if (!enabled || chunks.isEmpty()) {
            return Optional.empty();
        }
        try {
            String summary = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(buildInput(source, chunks, inputChars))
                    .options(ChatOptions.builder().temperature(0.2))
                    .call()
                    .content();
            if (!StringUtils.hasText(summary)) {
                return Optional.empty();
            }
            summary = summary.strip();
            return Optional.of(summary.length() > MAX_SUMMARY_CHARS ? summary.substring(0, MAX_SUMMARY_CHARS) : summary);
        } catch (Exception e) {
            log.warn("生成文档摘要失败：{}：{}", source, e.getMessage());
            return Optional.empty();
        }
    }

    /** 目录占三分之一、开头占三分之一、均匀抽样的正文占三分之一；目录或开头用不完的份额留给后面 */
    static String buildInput(String source, List<ChunkDraft> chunks, int maxChars) {
        int share = maxChars / 3;
        StringBuilder sb = new StringBuilder("文件：").append(source).append('\n');

        Set<String> headings = new LinkedHashSet<>();
        chunks.stream().map(ChunkDraft::headingPath).filter(StringUtils::hasText)
                .map(h -> h.length() > MAX_HEADING_CHARS ? h.substring(0, MAX_HEADING_CHARS) : h)
                .forEach(headings::add);
        int outlineStart = sb.length();
        if (!headings.isEmpty()) {
            sb.append("\n目录：\n");
            for (String heading : headings) {
                if (sb.length() - outlineStart + heading.length() > share) {
                    sb.append("……\n");
                    break;
                }
                sb.append(heading).append('\n');
            }
        }

        int openingBudget = share + Math.max(0, share - (sb.length() - outlineStart));
        sb.append("\n开头：\n");
        int used = 0;
        int next = 0;
        while (next < chunks.size() && used < openingBudget) {
            String text = chunks.get(next++).content();
            String part = text.length() > openingBudget - used ? text.substring(0, openingBudget - used) : text;
            sb.append(part).append('\n');
            used += part.length();
        }

        int remaining = chunks.size() - next;
        if (remaining > 0) {
            int sampleBudget = Math.max(0, maxChars - sb.length());
            int samples = Math.min(SAMPLES, remaining);
            int each = sampleBudget / samples;
            if (each > 0) {
                sb.append("\n正文节选：\n");
                for (int i = 0; i < samples; i++) {
                    String text = chunks.get(next + (int) ((long) i * remaining / samples)).content();
                    sb.append('[').append(i + 1).append("] ")
                            .append(text.length() > each ? text.substring(0, each) : text).append('\n');
                }
            }
        }
        return sb.toString();
    }
}
