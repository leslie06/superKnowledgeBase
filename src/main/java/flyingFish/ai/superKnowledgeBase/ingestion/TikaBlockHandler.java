package flyingFish.ai.superKnowledgeBase.ingestion;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把 Tika 输出的 XHTML 事件流整理成标题 / 段落 / 表格块，同时记录每块所在的物理单元
 * （PDF 的 div.page、Excel 的 div.sheet 或 div.page、PPT 的 div.slide-content）。
 */
class TikaBlockHandler extends DefaultHandler {

    enum Kind {HEADING, PARAGRAPH, TABLE}

    record Block(Kind kind, int level, String text, List<List<String>> rows, int unit) {
    }

    private static final Set<String> UNIT_CLASSES = Set.of("page", "sheet", "slide-content");
    private static final Set<String> SKIP_CLASSES = Set.of("outside", "embedded", "header", "footer");
    private static final Set<String> SKIP_ELEMENTS = Set.of("head", "script", "style");
    private static final Set<String> BLOCK_ELEMENTS = Set.of("p", "li", "div", "blockquote", "pre", "dd", "dt", "ul", "ol");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0\\u3000]+");
    /** PDF 按行输出，中文句子中间的换行不是真正的空格 */
    private static final Pattern LINE_BREAK_BETWEEN_CJK = Pattern.compile("(?<=[\\u4e00-\\u9fff，。；：！？、）】》”])[ \\t]*\\R\\s*(?=[\\u4e00-\\u9fff，。；：！？、（【《“])");

    private final List<Block> blocks = new ArrayList<>();
    private final StringBuilder buffer = new StringBuilder();
    /** 每个打开的 div 是否是物理单元，用于在 div 结束时判断是否离开单元 */
    private final Deque<Boolean> divStack = new ArrayDeque<>();

    private int skipDepth;
    private int headingLevel;
    private int unitCounter;
    private int currentUnit;

    private int tableDepth;
    private List<List<String>> rows;
    private List<String> row;
    private StringBuilder cell;

    List<Block> blocks() {
        return blocks;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) {
        String name = name(localName, qName);
        if (skipDepth > 0) {
            skipDepth++;
            return;
        }
        String cssClass = atts.getValue("class");
        if (SKIP_ELEMENTS.contains(name) || ("div".equals(name) && cssClass != null && SKIP_CLASSES.contains(cssClass))) {
            skipDepth = 1;
            return;
        }
        if ("table".equals(name)) {
            if (tableDepth++ == 0) {
                flushParagraph();
                rows = new ArrayList<>();
            }
            return;
        }
        if (tableDepth > 0) {
            switch (name) {
                case "tr" -> row = new ArrayList<>();
                case "td", "th" -> cell = new StringBuilder();
                case "p", "br" -> appendSeparator(cell);
                default -> {
                }
            }
            return;
        }
        if (isHeading(name)) {
            flushParagraph();
            headingLevel = name.charAt(1) - '0';
            return;
        }
        if ("br".equals(name)) {
            buffer.append('\n');
            return;
        }
        if ("div".equals(name)) {
            boolean isUnit = cssClass != null && UNIT_CLASSES.contains(cssClass);
            divStack.push(isUnit);
            if (isUnit) {
                flushParagraph();
                currentUnit = ++unitCounter;
            }
        }
        if (BLOCK_ELEMENTS.contains(name)) {
            flushParagraph();
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
        String name = name(localName, qName);
        if (skipDepth > 0) {
            skipDepth--;
            return;
        }
        if ("table".equals(name)) {
            if (--tableDepth == 0) {
                if (!rows.isEmpty()) {
                    blocks.add(new Block(Kind.TABLE, 0, null, rows, currentUnit));
                }
                rows = null;
            }
            return;
        }
        if (tableDepth > 0) {
            if (("td".equals(name) || "th".equals(name)) && row != null && cell != null) {
                row.add(normalize(cell.toString()));
                cell = null;
            } else if ("tr".equals(name) && row != null) {
                if (row.stream().anyMatch(c -> !c.isEmpty())) {
                    rows.add(row);
                }
                row = null;
            }
            return;
        }
        if (isHeading(name)) {
            flushParagraph();
            headingLevel = 0;
            return;
        }
        if ("div".equals(name)) {
            flushParagraph();
            Boolean wasUnit = divStack.poll();
            if (Boolean.TRUE.equals(wasUnit)) {
                currentUnit = 0;
            }
            return;
        }
        if (BLOCK_ELEMENTS.contains(name)) {
            flushParagraph();
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) {
        if (skipDepth > 0) {
            return;
        }
        if (tableDepth > 0) {
            if (cell != null) {
                cell.append(ch, start, length);
            }
            return;
        }
        buffer.append(ch, start, length);
    }

    @Override
    public void endDocument() {
        flushParagraph();
    }

    private void flushParagraph() {
        String text = normalize(buffer.toString());
        buffer.setLength(0);
        if (text.isEmpty()) {
            return;
        }
        Kind kind = headingLevel > 0 ? Kind.HEADING : Kind.PARAGRAPH;
        blocks.add(new Block(kind, headingLevel, text, null, currentUnit));
    }

    static String normalize(String s) {
        String joined = LINE_BREAK_BETWEEN_CJK.matcher(s).replaceAll("");
        return WHITESPACE.matcher(joined).replaceAll(" ").trim();
    }

    private static void appendSeparator(StringBuilder sb) {
        if (sb != null && !sb.isEmpty()) {
            sb.append(' ');
        }
    }

    private static boolean isHeading(String name) {
        return name.length() == 2 && name.charAt(0) == 'h' && name.charAt(1) >= '1' && name.charAt(1) <= '6';
    }

    private static String name(String localName, String qName) {
        return localName == null || localName.isEmpty() ? qName : localName;
    }
}
