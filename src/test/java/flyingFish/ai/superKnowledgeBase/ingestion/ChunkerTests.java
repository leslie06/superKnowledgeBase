package flyingFish.ai.superKnowledgeBase.ingestion;

import flyingFish.ai.superKnowledgeBase.common.SkbProperties;
import flyingFish.ai.superKnowledgeBase.ingestion.Chunker.ChunkDraft;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkerTests {

    private final Chunker chunker = new Chunker(new SkbProperties(null, null,
            new SkbProperties.Chunk(100, 150, 30), null, null, null));

    @Test
    void splitsOnHeadingChange() {
        List<ChunkDraft> chunks = chunker.chunk(List.of(
                new TextUnit("年假规定。", "第二章 > 2.2 年假", 0, null),
                new TextUnit("病假规定。", "第二章 > 2.3 病假", 0, null)), DocType.WORD);

        assertThat(chunks).extracting(ChunkDraft::headingPath).containsExactly("第二章 > 2.2 年假", "第二章 > 2.3 病假");
    }

    @Test
    void splitsOnSlideButNotOnPdfPage() {
        List<TextUnit> units = List.of(new TextUnit("第一页内容。", null, 1, "第1页"),
                new TextUnit("第二页内容。", null, 2, "第2页"));

        assertThat(chunker.chunk(units, DocType.PDF)).singleElement()
                .extracting(ChunkDraft::location).isEqualTo("第1-2页");
        assertThat(chunker.chunk(units, DocType.PPT)).hasSize(2);
    }

    @Test
    void longParagraphIsSplitBySentenceWithOverlap() {
        String sentence = "这是一个用于测试切块逻辑的句子。"; // 16 字
        String paragraph = sentence.repeat(20);
        List<ChunkDraft> chunks = chunker.chunk(List.of(new TextUnit(paragraph, "章节", 0, null)), DocType.WORD);

        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.content().length()).isLessThanOrEqualTo(150 + 30 + 1);
            assertThat(c.content()).startsWith("这是");
        });
        // 第二块以第一块末尾的完整句子开头
        String firstEnd = chunks.get(0).content().substring(chunks.get(0).content().length() - sentence.length());
        assertThat(chunks.get(1).content()).startsWith(firstEnd);
    }

    @Test
    void sentenceWithoutPunctuationIsHardSplit() {
        List<ChunkDraft> chunks = chunker.chunk(List.of(new TextUnit("字".repeat(400), null, 0, null)), DocType.TEXT);

        assertThat(chunks).allSatisfy(c -> assertThat(c.content().length()).isLessThanOrEqualTo(150));
        assertThat(String.join("", chunks.stream().map(ChunkDraft::content).toList())).hasSize(400);
    }

    @Test
    void tableRowsCarryHeaderSemantics() {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("2026 价格表", "", ""));
        rows.add(List.of("型号", "价格", ""));
        rows.add(List.of("FY-100", "299", "备注A"));

        assertThat(DocumentParser.tableLines(rows))
                .containsExactly("2026 价格表", "型号: FY-100；价格: 299；列3: 备注A");
    }

    @Test
    void guessesHeadingLevels() {
        assertThat(DocumentParser.guessHeadingLevel("第三章 报销制度")).isEqualTo(1);
        assertThat(DocumentParser.guessHeadingLevel("一、总体要求")).isEqualTo(2);
        assertThat(DocumentParser.guessHeadingLevel("2.1 工作时间")).isEqualTo(3);
        assertThat(DocumentParser.guessHeadingLevel("## 安装步骤")).isEqualTo(2);
        assertThat(DocumentParser.guessHeadingLevel("1. 员工密码长度不少于12位。")).isZero();
        assertThat(DocumentParser.guessHeadingLevel("第三章规定的内容如下：")).isZero();
    }

    @Test
    void normalizeJoinsPdfLineBreaksButKeepsRealSpaces() {
        assertThat(TikaBlockHandler.normalize("员工密码长度\n不少于12位")).isEqualTo("员工密码长度不少于12位");
        assertThat(TikaBlockHandler.normalize("第一章 总则")).isEqualTo("第一章 总则");
        assertThat(TikaBlockHandler.normalize("Hello\n  world　！")).isEqualTo("Hello world ！");
    }
}
