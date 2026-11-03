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
 *   <li>先把语义上不可分的段落归成一组：法律条文（"第X条"及其各款、各项）、引导句（以冒号结尾）与其后的列举项、
 *       连续的列举项。切块只在组之间进行，避免"有下列情形之一的："和"（一）（二）（三）"被切到两个片段</li>
 *   <li>在章节内把组合并到目标长度；组之间切分时不重叠，因为组边界本身就是语义边界</li>
 *   <li>超过上限的组只能在组内切分：后续片段以组内最近的引导句开头，没有引导句时以上一片段末尾的若干完整句子开头（overlap）</li>
 * </ul>
 */
@Component
public class Chunker {

    public record ChunkDraft(String content, String headingPath, String location) {
    }

    private static final Pattern SENTENCE = Pattern.compile("[^。！？!?；;\\n]+[。！？!?；;\\n]*");
    private static final Pattern ARTICLE_START = Pattern.compile("^第[一二三四五六七八九十百千零〇\\d]+条");
    /** 列举项："（一）"、"1、"、"1."、"a)"、"一、"、项目符号；"1.5倍"这类小数不算 */
    private static final Pattern LIST_ITEM = Pattern.compile(
            "^(?:[（(][一二三四五六七八九十\\d]+[)）]|\\d{1,2}(?:[、)）]|[.．](?!\\d))|[a-zA-Z][、.．)）]|[一二三四五六七八九十]+、"
                    + "|[•·●○■□◆◇▪►✓√\\-–—*\\uF000-\\uF0FF])");
    /** 以冒号结尾的引导句，后面的段落是它的展开 */
    private static final Pattern LEAD_IN = Pattern.compile("[：:]$");
    /** 至少有这么多段落以"第X条"开头才按法律条文分组，避免正文偶尔引用"第X条"时误判 */
    private static final int MIN_ARTICLES = 3;

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

        for (List<TextUnit> group : groups(units, breakOnUnit)) {
            int groupLength = length(group);
            if (!current.isEmpty()) {
                TextUnit last = current.getLast();
                TextUnit first = group.getFirst();
                boolean boundary = !Objects.equals(last.headingPath(), first.headingPath())
                        || (breakOnUnit && last.unit() != first.unit());
                boolean full = currentLength >= targetChars || currentLength + groupLength > maxChars;
                if (boundary || full) {
                    chunks.add(toDraft(current, type));
                    current = new ArrayList<>();
                    currentLength = 0;
                }
            }
            if (groupLength > maxChars) {
                // 走到这里 current 一定已清空；最后一段留在 current 里，可以和后面的短组合并
                List<List<TextUnit>> pieces = splitGroup(group);
                pieces.subList(0, pieces.size() - 1).forEach(piece -> chunks.add(toDraft(piece, type)));
                current = pieces.getLast();
                currentLength = length(current);
                continue;
            }
            current.addAll(group);
            currentLength += groupLength;
        }
        if (!current.isEmpty()) {
            chunks.add(toDraft(current, type));
        }
        return chunks;
    }

    /** 把不可分的连续单元归成组，组内超长单元按句子拆开（拆出的片段仍属于同一组）。 */
    private List<List<TextUnit>> groups(List<TextUnit> units, boolean breakOnUnit) {
        boolean articles = units.stream()
                .filter(u -> ARTICLE_START.matcher(u.text()).find())
                .limit(MIN_ARTICLES)
                .count() >= MIN_ARTICLES;
        List<List<TextUnit>> groups = new ArrayList<>();
        TextUnit previous = null;
        for (TextUnit unit : units) {
            if (previous == null || breakOnUnit || !continues(previous, unit, articles)) {
                groups.add(new ArrayList<>());
            }
            groups.getLast().addAll(split(unit));
            previous = unit;
        }
        return groups;
    }

    private static boolean continues(TextUnit previous, TextUnit unit, boolean articles) {
        if (!Objects.equals(previous.headingPath(), unit.headingPath()) || ARTICLE_START.matcher(unit.text()).find()) {
            return false;
        }
        // 法律文本中不以"第X条"开头的段落都是上一条的款或项
        return articles || LIST_ITEM.matcher(unit.text()).find() || LEAD_IN.matcher(previous.text()).find();
    }

    /** 把超过 maxChars 的单元按句子拆成不超过 targetChars 的片段。 */
    private List<TextUnit> split(TextUnit unit) {
        if (unit.text().length() <= maxChars) {
            return List.of(unit);
        }
        List<TextUnit> result = new ArrayList<>();
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
        return result;
    }

    /** 超长组在组内切分：后续片段以最近的引导句开头，让列举项不脱离题干；没有引导句时用句子重叠衔接。 */
    private List<List<TextUnit>> splitGroup(List<TextUnit> group) {
        List<List<TextUnit>> pieces = new ArrayList<>();
        List<TextUnit> piece = new ArrayList<>();
        int pieceLength = 0;
        // 片段开头为衔接而重复的单元数，只有这些内容时不能单独成块
        int seeded = 0;
        TextUnit leadIn = null;
        for (TextUnit unit : group) {
            if (piece.size() > seeded && (pieceLength >= targetChars || pieceLength + unit.text().length() > maxChars)) {
                pieces.add(piece);
                piece = leadIn != null ? new ArrayList<>(List.of(leadIn)) : overlapTail(piece.getLast());
                seeded = piece.size();
                pieceLength = length(piece);
            }
            piece.add(unit);
            pieceLength += unit.text().length();
            if (LEAD_IN.matcher(unit.text()).find() && unit.text().length() <= targetChars / 2) {
                leadIn = unit;
            }
        }
        pieces.add(piece);
        return pieces;
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

    private static int length(List<TextUnit> units) {
        return units.stream().mapToInt(u -> u.text().length()).sum();
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
