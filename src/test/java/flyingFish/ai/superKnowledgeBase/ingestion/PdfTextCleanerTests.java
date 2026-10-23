package flyingFish.ai.superKnowledgeBase.ingestion;

import flyingFish.ai.superKnowledgeBase.ingestion.TikaBlockHandler.Block;
import flyingFish.ai.superKnowledgeBase.ingestion.TikaBlockHandler.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PdfTextCleanerTests {

    private static final String FULL_LINE = "这是一行排满版面宽度的正文内容，句子在行末被换行截断后续还有";

    @Test
    void removesHeaderAndFooterRepeatedOnMostPages() {
        List<String> bodies = List.of("考勤规定。", "假期规定。", "报销流程。", "信息安全。");
        List<Block> blocks = new ArrayList<>();
        for (int page = 1; page <= bodies.size(); page++) {
            blocks.add(line("彩讯科技股份有限公司员工手册", page));
            blocks.add(line("内部资料，严禁对外传阅和翻印 " + page, page));
            blocks.add(line(bodies.get(page - 1), page));
        }

        assertThat(PdfTextCleaner.clean(blocks)).extracting(Block::text).containsExactlyElementsOf(bodies);
    }

    @Test
    void keepsRepeatedLinesWhenDocumentIsTooShort() {
        List<Block> blocks = List.of(line("公司名称", 1), line("公司名称", 2));

        assertThat(PdfTextCleaner.removeHeadersAndFooters(blocks)).hasSize(2);
    }

    @Test
    void dropsTableOfContentsLines() {
        List<Block> blocks = List.of(line("第二章 职业礼仪 .................... 6", 1), line("正文。", 1));

        assertThat(PdfTextCleaner.clean(blocks)).extracting(Block::text).containsExactly("正文。");
    }

    @Test
    void joinsWrappedLinesIntoParagraph() {
        List<Block> blocks = List.of(
                line(FULL_LINE, 1),
                line("这里是同一句话的结尾。", 1),
                line("短行标题", 1),
                line("下一段。", 1));

        assertThat(PdfTextCleaner.mergeWrappedLines(blocks)).extracting(Block::text)
                .containsExactly(FULL_LINE + "这里是同一句话的结尾。", "短行标题", "下一段。");
    }

    @Test
    void doesNotJoinListItemsHeadingsOrAcrossPages() {
        List<Block> blocks = List.of(
                line(FULL_LINE, 1), line("2、下一个条目", 1),
                line(FULL_LINE, 1), line("三、下一节", 1),
                line(FULL_LINE, 1), line(" 项目符号开头", 1),
                line(FULL_LINE, 1), line("下一页的内容", 2),
                line(FULL_LINE + "：", 2), line("冒号后面另起一行", 2));

        assertThat(PdfTextCleaner.mergeWrappedLines(blocks)).hasSameSizeAs(blocks);
    }

    private static Block line(String text, int page) {
        return new Block(Kind.PARAGRAPH, 0, text, null, page);
    }
}
