package flyingFish.ai.superKnowledgeBase.ingestion;

import flyingFish.ai.superKnowledgeBase.ingestion.Chunker.ChunkDraft;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentSummarizerTests {

    @Test
    void inputContainsOutlineOpeningAndSamplesWithinBudget() {
        List<ChunkDraft> chunks = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            chunks.add(new ChunkDraft("第" + i + "段正文。".repeat(40), "第" + (i / 10 + 1) + "章", "第" + i + "页"));
        }

        String input = DocumentSummarizer.buildInput("资料/书.pdf", chunks, 3000);

        assertThat(input).startsWith("文件：资料/书.pdf").contains("目录：\n第1章\n第2章", "开头：\n第1段正文", "正文节选：\n[1] ");
        assertThat(input).contains("[8] ").doesNotContain("[9] ");
        // 第 1 章重复出现在 10 个片段里，目录只列一次
        assertThat(input.split("第1章\n", -1)).hasSize(2);
        assertThat(input.length()).isLessThanOrEqualTo(3000 + 200);
    }

    @Test
    void shortDocumentGoesEntirelyIntoOpening() {
        List<ChunkDraft> chunks = List.of(new ChunkDraft("租金每月 5000 元。", null, null), new ChunkDraft("押一付三。", null, null));

        String input = DocumentSummarizer.buildInput("租房合同.pdf", chunks, 3000);

        assertThat(input).contains("开头：\n租金每月 5000 元。\n押一付三。").doesNotContain("目录：", "正文节选：");
    }
}
