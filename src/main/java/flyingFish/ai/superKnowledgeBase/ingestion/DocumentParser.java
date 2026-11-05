package flyingFish.ai.superKnowledgeBase.ingestion;

import flyingFish.ai.superKnowledgeBase.ingestion.TikaBlockHandler.Block;
import flyingFish.ai.superKnowledgeBase.ingestion.TikaBlockHandler.Kind;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 用 Tika 解析 doc/docx/xls/xlsx/ppt/pptx/pdf，并按格式特点整理成带章节路径和位置信息的文本单元。
 */
@Component
public class DocumentParser {

    private static final int MAX_HEADING_LEVEL = 8;
    private static final int MAX_HEURISTIC_HEADING_CHARS = 40;

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    /** 法律的"编""分编"在"章"之上，如民法典"第二编 物权 > 第一分编 通则 > 第一章 一般规定" */
    private static final Pattern BOOK_HEADING = Pattern.compile("^第[一二三四五六七八九十百零〇\\d]+编\\s*.*");
    private static final Pattern SUB_BOOK_HEADING = Pattern.compile("^第[一二三四五六七八九十百零〇\\d]+分编\\s*.*");
    private static final Pattern APPENDIX_HEADING = Pattern.compile("^附\\s*则$");
    private static final Pattern CHAPTER_HEADING = Pattern.compile("^第[一二三四五六七八九十百零〇\\d]+(?:章|篇|部分)\\s*.*");
    private static final Pattern SECTION_HEADING = Pattern.compile("^第[一二三四五六七八九十百零〇\\d]+节\\s*.*");
    private static final Pattern CN_NUMBER_HEADING = Pattern.compile("^[一二三四五六七八九十]+、.+");
    private static final Pattern CN_PAREN_HEADING = Pattern.compile("^[（(][一二三四五六七八九十]+[)）].+");
    /** 首段编号限两位数，避免把"300 高工、主管"这类表格残行、"2026 年计划"这类年份误认成标题 */
    private static final Pattern DECIMAL_HEADING = Pattern.compile("^(\\d{1,2}(?:\\.\\d+){0,4})\\s+\\S.*");
    private static final Pattern SENTENCE_END = Pattern.compile(".*[。；;：:，,！!？?]$");
    /**
     * 样式标题前的自动编号。Tika 计算 Word 多级列表编号时经常出错（如"1.4.1.4.1.1.1. 查看活动"），
     * 错误的编号进入章节路径后会干扰向量和精排，章节路径只保留标题文字
     */
    private static final Pattern STYLED_HEADING_NUMBER = Pattern.compile("^\\d{1,2}(?:\\.\\d{1,2})*\\.?\\s+(?=\\S)");
    /** 没有检索价值的独立行：页码（"－1－"、"- 2 -"）、目录标题 */
    private static final Pattern NOISE_LINE = Pattern.compile("^(?:[－\\-—–]\\s*\\d+\\s*[－\\-—–]|目\\s*录)$");
    /**
     * 编码后的二进制内容：200 个字符以上、含 a~f 字母的连续十六进制串。PDF 里嵌入的元数据（如 Adobe 导出设置中
     * 几十种语言的说明，以 UTF-16 十六进制存放）会被 Tika 当成正文提取出来。这类片段彼此几乎相同，向量聚成一团，
     * 会把 HNSW 检索引偏。要求含字母，是为了保留教材里纯 0/1 的二进制示例；带空格的十六进制转储也不受影响
     */
    private static final Pattern HEX_RUN = Pattern.compile("[0-9A-Fa-f]{200,}");
    /** PDF / PostScript 设置字典中的"/设置名 true|false"，一段里出现多次说明整段都是设置 */
    private static final Pattern PDF_SETTING = Pattern.compile("/[A-Z][A-Za-z0-9]+ (?:true|false)\\b");
    private static final int MIN_PDF_SETTINGS = 3;

    private final AutoDetectParser parser = new AutoDetectParser();
    private final OcrService ocr;

    public DocumentParser(OcrService ocr) {
        this.ocr = ocr;
    }

    /**
     * @param sha256      文件内容的 SHA-256，作为文字识别结果的缓存键
     * @param ocrProgress 扫描版 PDF 文字识别的进度回调；不需要识别时不会调用
     */
    public List<TextUnit> parse(Path file, String filename, String sha256, OcrService.Progress ocrProgress)
            throws IOException {
        DocType type = DocType.fromFilename(filename);
        TikaBlockHandler handler = new TikaBlockHandler();
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
        try (InputStream in = Files.newInputStream(file)) {
            parser.parse(in, handler, metadata, parseContext());
        } catch (Exception e) {
            throw new IOException("文件解析失败：" + e.getMessage(), e);
        }
        List<Block> blocks = type == DocType.PDF
                ? PdfTextCleaner.clean(withOcr(handler.blocks(), handler.units(), file, sha256, ocrProgress))
                : handler.blocks();
        List<TextUnit> units = switch (type) {
            case EXCEL -> excelUnits(blocks);
            case PPT -> pptUnits(blocks);
            case WORD, PDF, TEXT -> flowUnits(blocks, type);
        };
        return units.stream()
                .map(u -> new TextUnit(stripEncoded(u.text()), u.headingPath(), u.unit(), u.location()))
                .filter(u -> !u.text().isBlank())
                .toList();
    }

    /**
     * 去掉编码后的二进制内容。整段是设置字典，或编码内容超过一半（剩下的多是"> /CZE <FEFF"这类语法残片）时返回空串，整段丢弃。
     */
    static String stripEncoded(String text) {
        if (PDF_SETTING.matcher(text).results().limit(MIN_PDF_SETTINGS).count() >= MIN_PDF_SETTINGS) {
            return "";
        }
        Matcher m = HEX_RUN.matcher(text);
        StringBuilder kept = new StringBuilder();
        int removed = 0;
        while (m.find()) {
            // 纯数字（如 0/1 二进制示例）不算编码内容；没有调用 appendReplacement 的匹配会由后续 append 原样保留
            if (m.group().chars().anyMatch(Character::isLetter)) {
                removed += m.end() - m.start();
                m.appendReplacement(kept, "");
            }
        }
        if (removed == 0) {
            return text;
        }
        m.appendTail(kept);
        return removed * 2 > text.length() ? "" : kept.toString().strip();
    }

    /**
     * 文字层太少的页（扫描页）交给 OCR。识别结果是一行一行的，每行作为一个段落和其他页的文字一起按页码排序，
     * 之后由 PdfTextCleaner 去掉页眉页脚、把被换行截断的句子接回去，和普通 PDF 的处理方式相同。
     */
    private List<Block> withOcr(List<Block> blocks, int pageCount, Path file, String sha256,
                                OcrService.Progress progress) throws IOException {
        if (!ocr.available() || pageCount == 0) {
            return blocks;
        }
        int[] chars = new int[pageCount + 1];
        for (Block block : blocks) {
            if (block.unit() > 0 && block.unit() <= pageCount) {
                chars[block.unit()] += textLength(block);
            }
        }
        List<Integer> scanned = IntStream.rangeClosed(1, pageCount)
                .filter(p -> chars[p] < ocr.minPageChars())
                .boxed()
                .toList();
        if (scanned.isEmpty()) {
            return blocks;
        }
        Map<Integer, String> texts = ocr.recognize(sha256, file, scanned, progress);
        List<Block> merged = new ArrayList<>(blocks);
        for (int page : scanned) {
            String text = texts.get(page);
            if (text != null) {
                text.lines().map(TikaBlockHandler::normalize).filter(line -> !line.isEmpty())
                        .forEach(line -> merged.add(new Block(Kind.PARAGRAPH, 0, line, null, page)));
            }
        }
        // 稳定排序，同一页内保持原有顺序；不属于任何页的内容（如 PDF 书签）放在最后
        merged.sort(Comparator.comparingInt(b -> b.unit() > 0 ? b.unit() : Integer.MAX_VALUE));
        return merged;
    }

    private static int textLength(Block block) {
        String text = block.kind() == Kind.TABLE
                ? block.rows().stream().flatMap(List::stream).collect(Collectors.joining())
                : block.text();
        return (int) text.codePoints().filter(c -> !Character.isWhitespace(c)).count();
    }

    private static ParseContext parseContext() {
        ParseContext context = new ParseContext();
        PDFParserConfig pdfConfig = new PDFParserConfig();
        // 不按坐标排序：按坐标排序会把双栏 PDF 同一高度的左右两栏拼成一行；
        // 保持内容流顺序时，常见排版工具生成的双栏 PDF 能先读完左栏再读右栏。扫描件 OCR 留到后续版本
        pdfConfig.setSortByPosition(false);
        pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        pdfConfig.setExtractInlineImages(false);
        context.set(PDFParserConfig.class, pdfConfig);
        return context;
    }

    /** Word / PDF / 文本：按标题层级维护章节路径；没有样式标题时用编号规则识别标题。 */
    private List<TextUnit> flowUnits(List<Block> blocks, DocType type) {
        boolean hasStyledHeadings = blocks.stream().anyMatch(b -> b.kind() == Kind.HEADING);
        String[] headings = new String[MAX_HEADING_LEVEL + 1];
        List<TextUnit> units = new ArrayList<>();
        for (Block block : blocks) {
            String location = type == DocType.PDF && block.unit() > 0 ? "第" + block.unit() + "页" : null;
            switch (block.kind()) {
                case HEADING -> pushHeading(headings, block.level(), stripHeadingNumber(block.text()));
                case PARAGRAPH -> {
                    if (NOISE_LINE.matcher(block.text()).matches()) {
                        continue;
                    }
                    int level = hasStyledHeadings ? 0 : guessHeadingLevel(block.text());
                    if (level > 0) {
                        Matcher md = MARKDOWN_HEADING.matcher(block.text());
                        pushHeading(headings, level, md.matches() ? md.group(2).trim() : block.text());
                    } else {
                        units.add(new TextUnit(block.text(), headingPath(headings), block.unit(), location));
                    }
                }
                case TABLE -> {
                    for (String line : tableLines(block.rows())) {
                        units.add(new TextUnit(line, headingPath(headings), block.unit(), location));
                    }
                }
            }
        }
        return units;
    }

    /** Excel：每个工作表是一个单元，Tika 会把工作表名输出为 h1。 */
    private List<TextUnit> excelUnits(List<Block> blocks) {
        List<TextUnit> units = new ArrayList<>();
        String sheetName = null;
        int sheetUnit = -1;
        for (Block block : blocks) {
            if (block.unit() != sheetUnit) {
                sheetUnit = block.unit();
                sheetName = null;
            }
            if (block.kind() == Kind.HEADING && sheetName == null) {
                sheetName = block.text();
                continue;
            }
            String location = sheetName == null ? null : "工作表：" + sheetName;
            if (block.kind() == Kind.TABLE) {
                for (String line : tableLines(block.rows())) {
                    units.add(new TextUnit(line, null, block.unit(), location));
                }
            } else {
                units.add(new TextUnit(block.text(), null, block.unit(), location));
            }
        }
        return units;
    }

    /** PPT：每张幻灯片是一个单元，第一段文字视为幻灯片标题；幻灯片之外的内容是演讲备注。 */
    private List<TextUnit> pptUnits(List<Block> blocks) {
        List<TextUnit> units = new ArrayList<>();
        String slideTitle = null;
        int slideUnit = -1;
        for (Block block : blocks) {
            if (block.unit() != slideUnit) {
                slideUnit = block.unit();
                slideTitle = null;
            }
            boolean inSlide = block.unit() > 0;
            String location = inSlide ? "第" + block.unit() + "张幻灯片" : "演讲备注";
            if (inSlide && slideTitle == null && block.kind() != Kind.TABLE) {
                slideTitle = block.text();
            }
            List<String> texts = block.kind() == Kind.TABLE ? tableLines(block.rows()) : List.of(block.text());
            for (String text : texts) {
                units.add(new TextUnit(text, inSlide ? slideTitle : null, block.unit(), location));
            }
        }
        return units;
    }

    /**
     * 表格转成"列名: 值"的行文本，每行自带表头语义，切块后仍可独立理解。
     * 开头只有一个非空单元格的行（合并单元格标题）作为表格标题保留。
     */
    static List<String> tableLines(List<List<String>> rows) {
        List<String> lines = new ArrayList<>();
        int width = rows.stream().mapToInt(List::size).max().orElse(0);
        int start = 0;
        while (start < rows.size() - 1 && width > 2 && nonEmptyCount(rows.get(start)) == 1) {
            lines.add(String.join("", rows.get(start)).trim());
            start++;
        }
        if (rows.size() - start < 2 || width < 2) {
            for (int i = start; i < rows.size(); i++) {
                lines.add(rows.get(i).stream().filter(c -> !c.isEmpty()).collect(Collectors.joining(" | ")));
            }
            return lines;
        }
        List<String> header = rows.get(start);
        for (int i = start + 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < row.size(); c++) {
                String value = row.get(c);
                if (value.isEmpty()) {
                    continue;
                }
                String key = c < header.size() && !header.get(c).isEmpty() ? header.get(c) : "列" + (c + 1);
                if (!sb.isEmpty()) {
                    sb.append("；");
                }
                sb.append(key).append(": ").append(value);
            }
            if (!sb.isEmpty()) {
                lines.add(sb.toString());
            }
        }
        return lines;
    }

    private static long nonEmptyCount(List<String> row) {
        return row.stream().filter(c -> !c.isEmpty()).count();
    }

    static int guessHeadingLevel(String text) {
        Matcher md = MARKDOWN_HEADING.matcher(text);
        if (md.matches()) {
            return md.group(1).length();
        }
        if (text.length() > MAX_HEURISTIC_HEADING_CHARS || SENTENCE_END.matcher(text).matches()) {
            return 0;
        }
        if (BOOK_HEADING.matcher(text).matches() || APPENDIX_HEADING.matcher(text).matches()) {
            return 1;
        }
        if (SUB_BOOK_HEADING.matcher(text).matches()) {
            return 2;
        }
        if (CHAPTER_HEADING.matcher(text).matches()) {
            return 3;
        }
        if (SECTION_HEADING.matcher(text).matches() || CN_NUMBER_HEADING.matcher(text).matches()) {
            return 4;
        }
        if (CN_PAREN_HEADING.matcher(text).matches()) {
            return 5;
        }
        Matcher decimal = DECIMAL_HEADING.matcher(text);
        if (decimal.matches()) {
            int depth = decimal.group(1).split("\\.").length;
            return Math.min(depth + 3, MAX_HEADING_LEVEL);
        }
        return 0;
    }

    static String stripHeadingNumber(String text) {
        String stripped = STYLED_HEADING_NUMBER.matcher(text).replaceFirst("");
        return stripped.isEmpty() ? text : stripped;
    }

    private static void pushHeading(String[] headings, int level, String text) {
        headings[level] = text;
        Arrays.fill(headings, level + 1, headings.length, null);
    }

    private static String headingPath(String[] headings) {
        String path = Arrays.stream(headings).filter(Objects::nonNull).collect(Collectors.joining(" > "));
        return path.isEmpty() ? null : path;
    }
}
