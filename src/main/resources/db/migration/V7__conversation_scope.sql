-- 会话的检索范围。知识库里资料很杂时，用户可以把一次对话限定在某几个文件夹或文档里，追问时沿用同一范围。

ALTER TABLE conversation ADD COLUMN scope JSONB;

COMMENT ON COLUMN conversation.scope IS '检索范围：{"folders": [文件夹路径，对应 document.source_path 的前缀], "documentIds": [文档 ID]}，两者取并集；为空表示整个知识库';
