-- 文档摘要。片段检索只能回答落在某一段里的问题，"这本书讲了什么""哪些资料讲到了 MySQL"这类问题的答案分散在整份文档里。
-- 每个文档用 LLM 生成一段摘要，作为一个特殊片段（kind = SUMMARY）和正文片段一起参与检索；
-- 摘要原文另存在 document.summary，重新处理（如修改切块规则）时直接复用，文件换成新版本时清空重新生成。

ALTER TABLE document ADD COLUMN summary TEXT;
ALTER TABLE chunk ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'TEXT';

COMMENT ON COLUMN document.summary IS 'LLM 生成的文档摘要（主题、主要内容、关键词）；为空表示还没有生成或生成失败';
COMMENT ON COLUMN chunk.kind IS '片段类型：TEXT 正文 / SUMMARY 文档摘要（每个文档最多一个，chunk_index 为 -1）';
