package flyingFish.ai.superKnowledgeBase.ingestion;

/**
 * 解析后的最小文本单元（一个段落、一行表格等），由 {@link Chunker} 合并成 chunk。
 *
 * @param headingPath 所属章节路径，如 "第二章 考勤与假期 > 2.2 年假"；无标题结构时为 null
 * @param unit        所属物理单元序号（PDF 页 / 幻灯片 / 工作表），从 1 开始；无则为 0
 * @param location    物理单元的展示名，如 "第3页"、"第2张幻灯片"、"工作表：价格"
 */
public record TextUnit(String text, String headingPath, int unit, String location) {
}
