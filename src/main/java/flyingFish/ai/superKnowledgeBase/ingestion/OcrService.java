package flyingFish.ai.superKnowledgeBase.ingestion;

import flyingFish.ai.superKnowledgeBase.common.SkbProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扫描页文字识别：调用 skb.ocr.command（默认 scripts/ocr/vision-ocr，苹果 Vision），逐页取回文字。
 * 一本几百页的书要识别几分钟到几十分钟，所以每识别完一页就写入 ocr_page 缓存：处理中断后从缓存继续，
 * 修改切块规则后重新处理也不用再识别。命令不存在时不识别，扫描页取不到文字，和接入 OCR 之前一样。
 */
@Service
public class OcrService {

    /** 识别进度：已完成的页数（含缓存命中）、需要识别的总页数 */
    @FunctionalInterface
    public interface Progress {
        void update(int done, int total);
    }

    /** 识别命令每页输出的一行 JSON，字段见 scripts/ocr/vision_ocr.swift */
    record PageResult(int page, String engine, String text, String error, Long ms) {
    }

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);
    private static final int MAX_STDERR_CHARS = 500;

    private final OcrPageRepository cache;
    private final JsonMapper jsonMapper;
    private final boolean enabled;
    private final Path command;
    private final int dpi;
    private final int minPageChars;
    private final Set<Process> running = ConcurrentHashMap.newKeySet();

    public OcrService(OcrPageRepository cache, JsonMapper jsonMapper, SkbProperties properties) {
        this.cache = cache;
        this.jsonMapper = jsonMapper;
        this.enabled = properties.ocr().enabled();
        this.command = Path.of(properties.ocr().command()).toAbsolutePath();
        this.dpi = properties.ocr().dpi();
        this.minPageChars = properties.ocr().minPageChars();
        if (enabled && !Files.isExecutable(command)) {
            log.warn("文字识别命令不存在：{}，扫描版 PDF 将取不到文字。编译方法（仅 macOS）："
                    + "swiftc -O scripts/ocr/vision_ocr.swift -o scripts/ocr/vision-ocr", command);
        }
    }

    public boolean available() {
        return enabled && Files.isExecutable(command);
    }

    /** 文字层少于这么多个字（不含空白）的页需要识别 */
    public int minPageChars() {
        return minPageChars;
    }

    /**
     * @param sha256 文件内容的 SHA-256，作为缓存的键
     * @param pages  需要识别的页码（从 1 开始）
     * @return 页码 → 识别出的文字；识别失败的页不在结果中
     */
    public Map<Integer, String> recognize(String sha256, Path pdf, List<Integer> pages, Progress progress)
            throws IOException {
        Map<Integer, String> result = new HashMap<>(cache.findBySha256(sha256));
        result.keySet().retainAll(new HashSet<>(pages));
        List<Integer> missing = pages.stream().filter(p -> !result.containsKey(p)).toList();
        progress.update(result.size(), pages.size());
        if (missing.isEmpty()) {
            return result;
        }
        log.info("开始文字识别：{} 页（另有 {} 页使用缓存）", missing.size(), result.size());

        List<String> cmd = new ArrayList<>(List.of(command.toString(), pdf.toAbsolutePath().toString(), String.valueOf(dpi)));
        missing.forEach(p -> cmd.add(String.valueOf(p)));
        Process process = new ProcessBuilder(cmd).start();
        running.add(process);
        StringBuilder stderr = new StringBuilder();
        Thread stderrReader = Thread.ofVirtual().start(() -> {
            try {
                stderr.append(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // 进程被结束时流会关闭
            }
        });
        int done = result.size();
        int failed = 0;
        try (BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                PageResult page = jsonMapper.readValue(line, PageResult.class);
                done++;
                if (page.error() != null) {
                    failed++;
                    log.warn("第 {} 页文字识别失败：{}", page.page(), page.error());
                } else {
                    cache.save(sha256, page.page(), page.engine(), page.text());
                    result.put(page.page(), page.text());
                }
                progress.update(done, pages.size());
            }
            int exitCode = process.waitFor();
            stderrReader.join();
            if (exitCode != 0) {
                String message = stderr.toString().strip();
                throw new IOException("文字识别进程异常退出（退出码 " + exitCode + "）"
                        + (message.isEmpty() ? "" : "：" + message.substring(0, Math.min(message.length(), MAX_STDERR_CHARS))));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("文字识别被中断", e);
        } finally {
            process.destroyForcibly();
            running.remove(process);
        }
        if (failed > 0) {
            log.warn("文字识别完成，{} 页失败", failed);
        }
        return result;
    }

    /** 应用关闭时结束正在运行的识别进程；已识别的页都在缓存里，下次启动后接着识别 */
    @PreDestroy
    public void shutdown() {
        running.forEach(Process::destroyForcibly);
    }
}
