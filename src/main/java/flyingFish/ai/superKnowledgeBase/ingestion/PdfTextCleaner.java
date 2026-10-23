package flyingFish.ai.superKnowledgeBase.ingestion;

import flyingFish.ai.superKnowledgeBase.ingestion.TikaBlockHandler.Block;
import flyingFish.ai.superKnowledgeBase.ingestion.TikaBlockHandler.Kind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PDF 文本整理。PDFBox 通常一行输出一个段落，直接切块会带来两个问题：
 * <ul>
 *   <li>页眉页脚（公司名、"内部资料 第N页"）每页重复，混进正文片段</li>
 *   <li>句子被版面换行截断，片段里出现"……出示区级医院证"/"明并填写病假单"这样的断句</li>
 * </ul>
 * 这里先去掉在多数页面重复出现的短行和目录行，再把排满一整行、且没有以句末标点结尾的行与下一行接成段落。
 */
final class PdfTextCleaner {

    /** 至少有这么多页才判断页眉页脚，页数太少时重复行可能是正文 */
    private static final int MIN_PAGES = 3;
    /** 在超过这个比例的页面上重复出现的短行视为页眉页脚 */
    private static final double HEADER_FOOTER_PAGE_RATIO = 0.5;
    private static final int MAX_HEADER_FOOTER_CHARS = 40;
    /** 行长达到常见行长的这个比例才算"排满一行"，短行说明作者在这里主动换行 */
    private static final double FULL_LINE_RATIO = 0.7;
    private static final double TYPICAL_LINE_PERCENTILE = 0.8;

    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern HAS_TEXT = Pattern.compile("[\\p{IsHan}\\p{L}]");
    /** 目录行："第二章 职业礼仪 ........ 6"，对检索只有干扰 */
    private static final Pattern TOC_LINE = Pattern.compile(".*(?:[.．·…]\\s*){4,}\\d+$");
    private static final Pattern PARAGRAPH_END = Pattern.compile(".*[。！？!?；;：:]$");
    /** 项目符号（含 Wingdings 等符号字体映射到私用区的字符）和各类编号开头的行是新条目 */
    private static final Pattern ITEM_START = Pattern.compile(
            "^(?:[•·●○■□◆◇▪►✓√\\-–—*\\uF000-\\uF0FF]|\\d+[、.．)）]|[（(]\\d+[)）]|[a-zA-Z][、.．)）]|[（(]?[一二三四五六七八九十]+[、)）]).*");

    private PdfTextCleaner() {
    }

    static List<Block> clean(List<Block> blocks) {
        List<Block> body = removeHeadersAndFooters(blocks).stream()
                .filter(b -> !(b.kind() == Kind.PARAGRAPH && TOC_LINE.matcher(b.text()).matches()))
                .toList();
        return mergeWrappedLines(body);
    }

    static List<Block> removeHeadersAndFooters(List<Block> blocks) {
        Set<Integer> allPages = new HashSet<>();
        Map<String, Set<Integer>> pagesByLine = new HashMap<>();
        for (Block block : blocks) {
            if (block.unit() <= 0) {
                continue;
            }
            allPages.add(block.unit());
            if (isHeaderFooterCandidate(block)) {
                pagesByLine.computeIfAbsent(signature(block.text()), k -> new HashSet<>()).add(block.unit());
            }
        }
        if (allPages.size() < MIN_PAGES) {
            return blocks;
        }
        double minPages = Math.max(MIN_PAGES, allPages.size() * HEADER_FOOTER_PAGE_RATIO);
        Set<String> repeated = new HashSet<>();
        pagesByLine.forEach((line, pages) -> {
            if (pages.size() >= minPages) {
                repeated.add(line);
            }
        });
        if (repeated.isEmpty()) {
            return blocks;
        }
        return blocks.stream()
                .filter(b -> !(isHeaderFooterCandidate(b) && repeated.contains(signature(b.text()))))
                .toList();
    }

    static List<Block> mergeWrappedLines(List<Block> blocks) {
        int fullLine = (int) Math.ceil(typicalLineLength(blocks) * FULL_LINE_RATIO);
        List<Block> result = new ArrayList<>();
        // 段落合并后会变长，是否排满要看它的最后一行
        int lastLineLength = 0;
        for (Block block : blocks) {
            if (!result.isEmpty() && isContinuation(result.getLast(), lastLineLength, block, fullLine)) {
                Block last = result.removeLast();
                String text = TikaBlockHandler.normalize(last.text() + "\n" + block.text());
                result.add(new Block(Kind.PARAGRAPH, 0, text, null, last.unit()));
            } else {
                result.add(block);
            }
            lastLineLength = block.kind() == Kind.PARAGRAPH ? block.text().length() : 0;
        }
        return result;
    }

    private static boolean isContinuation(Block previous, int previousLineLength, Block next, int fullLine) {
        if (previous.kind() != Kind.PARAGRAPH || next.kind() != Kind.PARAGRAPH || previous.unit() != next.unit()) {
            return false;
        }
        return previousLineLength >= fullLine
                && !PARAGRAPH_END.matcher(previous.text()).matches()
                && !ITEM_START.matcher(next.text()).matches()
                && DocumentParser.guessHeadingLevel(next.text()) == 0;
    }

    /** 取段落长度的较高分位数作为常见行长；已经按段落输出的 PDF 行长很大，几乎不会触发合并。 */
    private static int typicalLineLength(List<Block> blocks) {
        int[] lengths = blocks.stream()
                .filter(b -> b.kind() == Kind.PARAGRAPH)
                .mapToInt(b -> b.text().length())
                .sorted()
                .toArray();
        if (lengths.length == 0) {
            return Integer.MAX_VALUE;
        }
        return lengths[(int) ((lengths.length - 1) * TYPICAL_LINE_PERCENTILE)];
    }

    private static boolean isHeaderFooterCandidate(Block block) {
        return block.kind() == Kind.PARAGRAPH && block.unit() > 0
                && block.text().length() <= MAX_HEADER_FOOTER_CHARS && HAS_TEXT.matcher(block.text()).find();
    }

    /** 页码等数字每页不同，比较前统一替换掉 */
    private static String signature(String text) {
        return DIGITS.matcher(text).replaceAll("#");
    }
}
