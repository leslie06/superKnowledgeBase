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
    /**
     * 章回小说的回目："第七十九回 西门庆贪欲丧命 吴月娘失偶生儿"，词话本写作"囬""囘"。回字后面是空格或行尾，
     * "第二回了"这样的正文不算；group(2) 是回目名（可能粘着正文，见 novelChapter）
     */
    private static final Pattern NOVEL_CHAPTER = Pattern.compile("^(第[一二三四五六七八九十百零〇\\d]+[回囘囬])(?:[\\s\\u3000]+(.*))?$");
    /** 一行里出现第二个回目的是目录 */
    private static final Pattern ANOTHER_CHAPTER = Pattern.compile("第[一二三四五六七八九十百零〇\\d]+[回囘囬]");
    /** 回目名后面粘着的正文开头：PDF 清理时把"詞曰："这类下一行接到了回目后面 */
    private static final Pattern CHAPTER_BODY_START = Pattern.compile("[，。；：！？、,.;:!?「“]|[詩诗詞词]曰|[詩诗詞词]云|[話话]說|[話话]说|[却卻且][說说]|看官");
    private static final int MAX_CHAPTER_TITLE_CHARS = 30;
    /** 回目与"章"同级 */
    private static final int CHAPTER_LEVEL = 3;
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
    /** 识别结果中与同页文字层某行相似度达到这个值的行视为重复（文字层的水印渲染后又被识别了一遍） */
    private static final double TEXT_LAYER_DUPLICATE_SIMILARITY = 0.6;
    /** 比较相似度时只保留文字、字母和数字，识别结果的空格、标点经常和原文不一致 */
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{IsHan}\\p{L}\\p{N}]");

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
        TikaBlockHandler handler = new TikaBlockHandler(type == DocType.TEXT);
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
        List<Integer> scanned = scannedPages(blocks, pageCount, ocr.minPageChars());
        if (scanned.isEmpty()) {
            return blocks;
        }
        Map<Integer, String> texts = ocr.recognize(sha256, file, scanned, progress);
        Map<Integer, List<String>> layerLines = blocks.stream()
                .filter(b -> b.kind() == Kind.PARAGRAPH && b.unit() > 0)
                .collect(Collectors.groupingBy(Block::unit, Collectors.mapping(Block::text, Collectors.toList())));
        List<Block> merged = new ArrayList<>(blocks);
        for (int page : scanned) {
            String text = texts.get(page);
            if (text != null) {
                List<String> layer = layerLines.getOrDefault(page, List.of());
                text.lines().map(TikaBlockHandler::normalize)
                        .filter(line -> !line.isEmpty() && !duplicatesTextLayer(line, layer))
                        .forEach(line -> merged.add(new Block(Kind.PARAGRAPH, 0, line, null, page)));
            }
        }
        // 稳定排序，同一页内保持原有顺序；不属于任何页的内容（如 PDF 书签）放在最后
        merged.sort(Comparator.comparingInt(b -> b.unit() > 0 ? b.unit() : Integer.MAX_VALUE));
        return merged;
    }

    /**
     * 文字层少于 minPageChars 个字的页。先去掉每页重复的页眉页脚再计数：有些扫描版每页都带一行文字水印
     * （"更多资源请访问……"），只按字数算会把整本书当成有文字层。
     */
    static List<Integer> scannedPages(List<Block> blocks, int pageCount, int minPageChars) {
        int[] chars = new int[pageCount + 1];
        for (Block block : PdfTextCleaner.removeHeadersAndFooters(blocks)) {
            if (block.unit() > 0 && block.unit() <= pageCount) {
                chars[block.unit()] += textLength(block);
            }
        }
        return IntStream.rangeClosed(1, pageCount)
                .filter(p -> chars[p] < minPageChars)
                .boxed()
                .toList();
    }

    /**
     * 扫描页上的少量文字层（如水印）渲染成图片后会被再识别一遍，识别结果带错字（"更多资源"识别成"雨多咨源"），
     * 每页写法都不同，PdfTextCleaner 按完全相同去重去不掉，所以这里按编辑距离和同页文字层比较。
     */
    static boolean duplicatesTextLayer(String ocrLine, List<String> layerLines) {
        String line = NON_WORD.matcher(ocrLine).replaceAll("");
        if (line.isEmpty()) {
            return false;
        }
        for (String layerLine : layerLines) {
            String other = NON_WORD.matcher(layerLine).replaceAll("");
            int longer = Math.max(line.length(), other.length());
            // 长度差太多时相似度不可能达标，省去计算
            if (Math.min(line.length(), other.length()) >= longer * TEXT_LAYER_DUPLICATE_SIMILARITY
                    && 1 - (double) editDistance(line, other) / longer >= TEXT_LAYER_DUPLICATE_SIMILARITY) {
                return true;
            }
        }
        return false;
    }

    private static int editDistance(String a, String b) {
        int[] previous = IntStream.rangeClosed(0, b.length()).toArray();
        int[] current = new int[b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitute = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitute, Math.min(previous[j], current[j - 1]) + 1);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
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

    /**
     * 回目：heading 是"第七十九回 西门庆贪欲丧命 吴月娘失偶生儿"，body 是粘在回目后面的正文（没有时为空），
     * titleMissing 表示这一行只有"第一回"，回目名在下一行
     */
    record NovelChapter(String heading, String body, boolean titleMissing) {
    }

    /** Word / PDF / 文本：按标题层级维护章节路径；没有样式标题时用编号规则识别标题。 */
    private List<TextUnit> flowUnits(List<Block> blocks, DocType type) {
        boolean hasStyledHeadings = blocks.stream().anyMatch(b -> b.kind() == Kind.HEADING);
        String[] headings = new String[MAX_HEADING_LEVEL + 1];
        List<TextUnit> units = new ArrayList<>();
        // 上一行只有"第一回"，这一行可能是回目名
        String pendingChapter = null;
        for (Block block : blocks) {
            String location = type == DocType.PDF && block.unit() > 0 ? "第" + block.unit() + "页" : null;
            switch (block.kind()) {
                case HEADING -> pushHeading(headings, block.level(), stripHeadingNumber(block.text()));
                case PARAGRAPH -> {
                    if (NOISE_LINE.matcher(block.text()).matches()) {
                        continue;
                    }
                    if (!hasStyledHeadings) {
                        String chapter = pendingChapter;
                        pendingChapter = null;
                        if (chapter != null && isChapterTitle(block.text())) {
                            pushHeading(headings, CHAPTER_LEVEL, chapter + " " + block.text());
                            continue;
                        }
                        NovelChapter novel = novelChapter(block.text());
                        if (novel != null) {
                            pushHeading(headings, CHAPTER_LEVEL, novel.heading());
                            if (novel.titleMissing()) {
                                pendingChapter = novel.heading();
                            }
                            if (!novel.body().isEmpty()) {
                                units.add(new TextUnit(novel.body(), headingPath(headings), block.unit(), location));
                            }
                            continue;
                        }
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

    /**
     * 识别回目并切开粘在后面的正文。回目通常是上下两联，用空格分开；下联后面粘着正文时（PDF 清理把下一行接了上来），
     * 在"詞曰""話說"或标点处切开，找不到时按上联的字数切（"西門慶梳籠李桂姐婦人嫉妒非常，"→"西門慶梳籠李桂姐"）。
     * 目录行（一行有多个回目）、回目名过长的返回 null，按正文处理。
     */
    static NovelChapter novelChapter(String text) {
        Matcher m = NOVEL_CHAPTER.matcher(text);
        if (!m.matches()) {
            return null;
        }
        String number = m.group(1);
        String rest = m.group(2) == null ? "" : m.group(2).strip();
        if (rest.isEmpty()) {
            return new NovelChapter(number, "", true);
        }
        if (ANOTHER_CHAPTER.matcher(rest).find()) {
            return null;
        }
        String[] halves = rest.split("[\\s\\u3000]+", 3);
        String first = halves[0];
        Matcher firstBody = CHAPTER_BODY_START.matcher(first);
        if (firstBody.find()) {
            // 只有一联、后面直接是正文
            String title = first.substring(0, firstBody.start());
            return title.isEmpty() || title.length() > MAX_CHAPTER_TITLE_CHARS ? null
                    : new NovelChapter(number + " " + title, rest.substring(firstBody.start()).strip(), false);
        }
        if (halves.length == 1) {
            return first.length() > MAX_CHAPTER_TITLE_CHARS ? null : new NovelChapter(number + " " + first, "", false);
        }
        String second = halves[1];
        Matcher secondBody = CHAPTER_BODY_START.matcher(second);
        int end = secondBody.find() ? secondBody.start() : second.length();
        if (end > first.length() + 2) {
            end = first.length();
        }
        String title = first + " " + second.substring(0, end);
        String body = (second.substring(end) + (halves.length > 2 ? " " + halves[2] : "")).strip();
        return title.length() > MAX_CHAPTER_TITLE_CHARS ? null : new NovelChapter(number + " " + title, body, false);
    }

    /** "第一回"单独一行时，下一行是否是回目名：不长、没有标点、不是另一个编号标题 */
    static boolean isChapterTitle(String text) {
        return text.length() <= MAX_CHAPTER_TITLE_CHARS && !CHAPTER_BODY_START.matcher(text).find()
                && !text.startsWith("第") && guessHeadingLevel(text) == 0;
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
