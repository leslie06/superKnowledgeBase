-- 章节摘要。文档摘要只有一段，回答不了"西门庆死在谁的床上""第七十九回讲了什么"这类情节问题：
-- 答案在某一回里，原文用词和问法对不上（"贪欲丧命""三丸药"），而回目和这一回的摘要能直接命中。
-- 为正文较长的章节（第X章 / 第X回 / 第X篇）各生成一段摘要，作为 kind = CHAPTER 的特殊片段参与检索；
-- 摘要原文按文件内容和章节路径缓存，重新处理（如修改切块规则）时直接复用，文件换成新版本后内容变了自然不再命中。

CREATE TABLE chapter_summary (
    sha256       CHAR(64)    NOT NULL,
    heading_path TEXT        NOT NULL,
    summary      TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (sha256, heading_path)
);

COMMENT ON TABLE chapter_summary IS 'LLM 生成的章节摘要缓存，按文件内容（SHA-256）和章节路径保存';
COMMENT ON COLUMN chapter_summary.sha256 IS '文件内容的 SHA-256，与 document.sha256 对应；同一文件出现在多个知识库时共用';
COMMENT ON COLUMN chapter_summary.heading_path IS '章节路径，截到第一个"第X章 / 第X回 / 第X篇"为止，如"第七十九回 西门庆贪欲丧命 吴月娘失偶生儿"';
COMMENT ON COLUMN chapter_summary.summary IS '章节摘要：人物、事件经过与因果、结局，或主要观点';
COMMENT ON COLUMN chapter_summary.created_at IS '生成时间';

COMMENT ON COLUMN chunk.kind IS '片段类型：TEXT 正文 / SUMMARY 文档摘要（每个文档最多一个，chunk_index 为 -1）/ CHAPTER 章节摘要（每个章节最多一个，chunk_index 从 -2 往下）';
