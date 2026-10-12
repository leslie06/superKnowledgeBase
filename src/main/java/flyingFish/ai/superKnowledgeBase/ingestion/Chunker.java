package flyingFish.ai.superKnowledgeBase.ingestion;

import flyingFish.ai.superKnowledgeBase.common.SkbProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构感知切块：
 * <ul>
 *   <li>不跨章节（标题路径变化即切分）、不跨幻灯片 / 工作表；PDF 允许跨页，记录页码范围</li>
 *   <li>在章节内把段落合并到目标长度，超长段落按句子拆分</li>
 *   <li>因长度切分时，下一块以上一块末尾的若干完整句子开头（overlap）</li>
 * </ul>
 */
@Component
public class Chunker {

    public record ChunkDraft(String content, String headingPath, String location) {
    }

    private static final Pattern SENTENCE = Pattern.compile("[^。！？!?；;\\n]+[。！？!?；;\\n]*");

    private final int targetChars;
    private final int maxChars;
    private final int overlapChars;

    public Chunker(SkbProperties properties) {
        this.targetChars = properties.chunk().targetChars();
        this.maxChars = properties.chunk().maxChars();
        this.overlapChars = properties.chunk().overlapChars();
    }

    public List<ChunkDraft> chunk(List<TextUnit> units, DocType type) {
        boolean breakOnUnit = type == DocType.PPT || type == DocType.EXCEL;
        List<ChunkDraft> chunks = new ArrayList<>();
        List<TextUnit> current = new ArrayList<>();
        int currentLength = 0;

        for (TextUnit unit : split(units)) {
            if (!current.isEmpty()) {
                TextUnit last = current.getLast();
                boolean boundary = !Objects.equals(last.headingPath(), unit.headingPath())
                        || (breakOnUnit && last.unit() != unit.unit());
                boolean full = currentLength >= targetChars || currentLength + unit.text().length() > maxChars;
                if (boundary || full) {
                    chunks.add(toDraft(current, type));
                    current = boundary ? new ArrayList<>() : overlapTail(last);
                    currentLength = current.stream().mapToInt(u -> u.text().length()).sum();
                }
            }
            current.add(unit);
            currentLength += unit.text().length();
        }
        if (!current.isEmpty()) {
            chunks.add(toDraft(current, type));
        }
        return chunks;
    }

    /** 把超过 maxChars 的单元按句子拆成不超过 targetChars 的片段。 */
    private List<TextUnit> split(List<TextUnit> units) {
        List<TextUnit> result = new ArrayList<>();
        for (TextUnit unit : units) {
            if (unit.text().length() <= maxChars) {
                result.add(unit);
                continue;
            }
            StringBuilder piece = new StringBuilder();
            for (String sentence : sentences(unit.text())) {
                if (!piece.isEmpty() && piece.length() + sentence.length() > targetChars) {
                    result.add(withText(unit, piece.toString()));
                    piece.setLength(0);
                }
                // 没有标点的超长句子只能硬切
                while (sentence.length() > maxChars) {
                    result.add(withText(unit, sentence.substring(0, targetChars)));
                    sentence = sentence.substring(targetChars);
                }
                piece.append(sentence);
            }
            if (!piece.isEmpty()) {
                result.add(withText(unit, piece.toString()));
            }
        }
        return result;
    }

    private List<TextUnit> overlapTail(TextUnit last) {
        List<String> sentences = sentences(last.text());
        StringBuilder tail = new StringBuilder();
        for (int i = sentences.size() - 1; i >= 0; i--) {
            String s = sentences.get(i);
            if (tail.length() + s.length() > overlapChars) {
                break;
            }
            tail.insert(0, s);
        }
        List<TextUnit> result = new ArrayList<>();
        // 整个单元都能放进 overlap 时说明它本身很短（如表格行），重复它意义不大
        if (!tail.isEmpty() && tail.length() < last.text().length()) {
            result.add(withText(last, tail.toString()));
        }
        return result;
    }

    private static ChunkDraft toDraft(List<TextUnit> units, DocType type) {
        String content = String.join("\n", units.stream().map(TextUnit::text).toList());
        TextUnit first = units.getFirst();
        String location = first.location();
        if (type == DocType.PDF && first.unit() > 0) {
            int from = first.unit();
            int to = units.getLast().unit();
            location = from == to ? "第" + from + "页" : "第" + from + "-" + to + "页";
        }
        return new ChunkDraft(content, first.headingPath(), location);
    }

    private static List<String> sentences(String text) {
        List<String> result = new ArrayList<>();
        Matcher m = SENTENCE.matcher(text);
        while (m.find()) {
            result.add(m.group());
        }
        return result;
    }

    private static TextUnit withText(TextUnit unit, String text) {
        return new TextUnit(text, unit.headingPath(), unit.unit(), unit.location());
    }
}
