package flyingFish.ai.superKnowledgeBase.retrieval;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 关键词检索的分词规则，建索引和查询共用，保证两边切出的词项一致：
 * <ul>
 *   <li>中文按二字组切分（"年假天数" → 年假 / 假天 / 天数），单字连续段保留单字</li>
 *   <li>英文和数字按整词保留并转小写（Q5 → q5），带下划线的再拆出各段（rw_2026 → rw_2026 / rw / 2026）</li>
 *   <li>条款编号统一成阿拉伯数字（第一千零七十九条 / 第1079条 / 1079条 → 第1079条），向量检索对这类编号不敏感；
 *       编号本身不再切二字组，否则"三十""九条"会命中所有"第三十X条"</li>
 *   <li>查询时先去掉"是什么""怎么""哪些"等疑问词，它们在制度、法规正文里很少出现，IDF 反而很高</li>
 * </ul>
 * 词项只含汉字、小写字母、数字和下划线，可以直接拼进 tsvector / tsquery 字面量。
 */
public final class KeywordAnalyzer {

    private static final int MAX_WORD_LENGTH = 64;
    private static final String CN_DIGITS = "零〇一二两三四五六七八九十百千万";
    private static final Pattern ARTICLE = Pattern.compile(
            "(?:第\\s*([0-9]+|[" + CN_DIGITS + "]+)|(?<![0-9a-z_.])([0-9]+))\\s*([条章节编款])");
    private static final Pattern HAN_RUN = Pattern.compile("\\p{IsHan}+");
    private static final Pattern WORD = Pattern.compile("[a-z0-9_]+");
    private static final Pattern LETTER_AND_DIGIT = Pattern.compile("(?=.*[a-z])(?=.*[0-9]).+");
    private static final Pattern LONG_NUMBER = Pattern.compile("[0-9]{5,}");
    private static final Pattern QUESTION_WORDS = Pattern.compile(
            "是什么|什么样|什么|怎么样|怎么办|怎么|怎样|如何|哪些|哪个|哪里|哪儿|多少|多久|多长|几个|是否|有没有|能不能|可不可以|请问|吗|呢|吧");

    private KeywordAnalyzer() {
    }

    /** 切出全部词项（含重复），用于建索引和统计词频。 */
    public static List<String> analyze(String text) {
        List<String> terms = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return terms;
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);

        Matcher article = ARTICLE.matcher(normalized);
        StringBuilder rest = new StringBuilder();
        while (article.find()) {
            String number = article.group(1) != null ? article.group(1) : article.group(2);
            long value = Character.isDigit(number.charAt(0)) ? parseArabic(number) : parseChinese(number);
            if (value > 0) {
                terms.add("第" + value + article.group(3));
                article.appendReplacement(rest, " ");
            }
        }
        article.appendTail(rest);
        String remaining = rest.toString();

        Matcher han = HAN_RUN.matcher(remaining);
        while (han.find()) {
            String run = han.group();
            if (run.length() == 1) {
                terms.add(run);
            }
            for (int i = 0; i + 2 <= run.length(); i++) {
                terms.add(run.substring(i, i + 2));
            }
        }

        Matcher word = WORD.matcher(remaining);
        while (word.find()) {
            addWord(terms, word.group());
            if (word.group().indexOf('_') >= 0) {
                for (String part : word.group().split("_")) {
                    addWord(terms, part);
                }
            }
        }
        return terms;
    }

    /** 查询词项：去掉疑问词后切分，去重并保持顺序。 */
    public static List<String> queryTerms(String query) {
        String stripped = query == null ? null : QUESTION_WORDS.matcher(query).replaceAll(" ");
        return List.copyOf(new LinkedHashSet<>(analyze(stripped)));
    }

    /**
     * 精确词项：条款编号、字母数字混合的编号（q5、rw_202607050001）、5 位以上的数字（商品编码等）。
     * 片段包含问题里的精确词项时，即使向量相似度低于阈值也视为相关。
     */
    public static boolean isExactTerm(String term) {
        if (term.startsWith("第") && term.length() > 2 && Character.isDigit(term.charAt(1))) {
            return true;
        }
        return LETTER_AND_DIGIT.matcher(term).matches() || LONG_NUMBER.matcher(term).matches();
    }

    /** 拼成 tsvector 字面量，如 'q5':1 '年假':2,7；位置用于统计词频。 */
    public static String toTsvector(List<String> terms) {
        Map<String, List<Integer>> positions = new LinkedHashMap<>();
        for (int i = 0; i < terms.size(); i++) {
            // tsvector 的位置上限是 16383，超出部分只影响词频统计，不影响能否命中
            positions.computeIfAbsent(terms.get(i), k -> new ArrayList<>()).add(Math.min(i + 1, 16383));
        }
        return positions.entrySet().stream()
                .map(e -> quote(e.getKey()) + ":" + e.getValue().stream().distinct().map(String::valueOf)
                        .collect(Collectors.joining(",")))
                .collect(Collectors.joining(" "));
    }

    /** 拼成"任一词项命中"的 tsquery 字面量，如 'q5' | '系统'。 */
    public static String toTsquery(Collection<String> terms) {
        return terms.stream().map(KeywordAnalyzer::quote).collect(Collectors.joining(" | "));
    }

    private static void addWord(List<String> terms, String word) {
        String w = word.replaceAll("^_+|_+$", "");
        // 单个字母或数字（列表编号 a、1 等）区分度太低
        if (w.length() >= 2) {
            terms.add(w.length() > MAX_WORD_LENGTH ? w.substring(0, MAX_WORD_LENGTH) : w);
        }
    }

    private static String quote(String term) {
        return "'" + term.replace("'", "''") + "'";
    }

    private static long parseArabic(String digits) {
        return digits.length() > 9 ? -1 : Long.parseLong(digits);
    }

    /** 解析"一千零七十九""十七""两万"这类中文数字，无法解析时返回 -1。 */
    static long parseChinese(String text) {
        long total = 0;
        long section = 0;
        long number = 0;
        for (char c : text.toCharArray()) {
            int digit = "零一二三四五六七八九".indexOf(c);
            if (c == '〇') {
                digit = 0;
            } else if (c == '两') {
                digit = 2;
            }
            if (digit >= 0) {
                number = digit;
                continue;
            }
            int unit = switch (c) {
                case '十' -> 10;
                case '百' -> 100;
                case '千' -> 1000;
                case '万' -> 10000;
                default -> -1;
            };
            if (unit < 0) {
                return -1;
            }
            if (unit == 10000) {
                total += (section + number) * unit;
                section = 0;
            } else {
                // "十七"省略了开头的"一"
                section += (number == 0 ? 1 : number) * unit;
            }
            number = 0;
        }
        return total + section + number;
    }

    /** 从查询词项中挑出精确词项。 */
    public static Set<String> exactTerms(Collection<String> terms) {
        return terms.stream().filter(KeywordAnalyzer::isExactTerm).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
