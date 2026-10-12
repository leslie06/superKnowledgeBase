-- 表和字段注释（COMMENT ON 可重复执行）

COMMENT ON TABLE knowledge_base IS '知识库：文档的容器，问答只在单个知识库内检索';
COMMENT ON COLUMN knowledge_base.id IS '主键';
COMMENT ON COLUMN knowledge_base.name IS '知识库名称';
COMMENT ON COLUMN knowledge_base.description IS '知识库描述';
COMMENT ON COLUMN knowledge_base.embedding_model IS '生成向量所用的 embedding 模型（如 bge-m3）；更换模型后必须重建整个库的向量';
COMMENT ON COLUMN knowledge_base.created_at IS '创建时间';

COMMENT ON TABLE document IS '文档：上传到知识库的原始文件及其处理状态';
COMMENT ON COLUMN document.id IS '主键';
COMMENT ON COLUMN document.kb_id IS '所属知识库 ID';
COMMENT ON COLUMN document.filename IS '上传时的原始文件名';
COMMENT ON COLUMN document.file_path IS '原文件存储路径，相对于 skb.storage-dir，格式为 {kbId}/{sha256}.{扩展名}';
COMMENT ON COLUMN document.sha256 IS '文件内容的 SHA-256，用于同一知识库内去重';
COMMENT ON COLUMN document.mime_type IS '文件 MIME 类型';
COMMENT ON COLUMN document.size_bytes IS '文件大小（字节）';
COMMENT ON COLUMN document.status IS '处理状态：PENDING 排队中 / PARSING 解析中 / EMBEDDING 向量化中 / READY 已就绪 / FAILED 失败';
COMMENT ON COLUMN document.error_msg IS '处理失败的原因（status 为 FAILED 时有值）';
COMMENT ON COLUMN document.chunk_count IS '切分出的片段数量';
COMMENT ON COLUMN document.created_at IS '上传时间';
COMMENT ON COLUMN document.updated_at IS '最后更新时间（状态变化时更新）';

COMMENT ON TABLE chunk IS '知识片段：文档切块后的文本及其向量，是检索的基本单位';
COMMENT ON COLUMN chunk.id IS '主键';
COMMENT ON COLUMN chunk.document_id IS '所属文档 ID';
COMMENT ON COLUMN chunk.kb_id IS '所属知识库 ID（冗余字段，检索时按知识库过滤，省去关联查询）';
COMMENT ON COLUMN chunk.chunk_index IS '片段在文档中的顺序，从 0 开始';
COMMENT ON COLUMN chunk.content IS '片段原文';
COMMENT ON COLUMN chunk.heading_path IS '所属章节路径，如"第二章 考勤与假期 > 2.2 年假"；PPT 为幻灯片标题；无标题结构时为空';
COMMENT ON COLUMN chunk.location IS '在原文件中的位置：PDF 页码（第3页 / 第3-4页）、PPT 幻灯片序号、Excel 工作表名';
COMMENT ON COLUMN chunk.embedding IS '1024 维向量（bge-m3 生成，输入为"文件名 + 章节 + 位置 + 正文"），HNSW 索引，按余弦距离检索';

COMMENT ON TABLE conversation IS '对话：一个知识库下的一次多轮问答会话';
COMMENT ON COLUMN conversation.id IS '主键';
COMMENT ON COLUMN conversation.kb_id IS '所属知识库 ID';
COMMENT ON COLUMN conversation.title IS '对话标题，默认取第一个问题的前 30 个字';
COMMENT ON COLUMN conversation.created_at IS '创建时间';
COMMENT ON COLUMN conversation.updated_at IS '最后活跃时间（每条新消息都会更新，用于对话列表排序）';

COMMENT ON TABLE message IS '对话消息：用户的提问和助手的回答';
COMMENT ON COLUMN message.id IS '主键';
COMMENT ON COLUMN message.conversation_id IS '所属对话 ID';
COMMENT ON COLUMN message.role IS '角色：user 用户 / assistant 助手';
COMMENT ON COLUMN message.content IS '消息正文；助手回答中的 [n] 对应 citations 中 index 为 n 的引用';
COMMENT ON COLUMN message.citations IS '助手回答引用的知识片段（JSON 数组），包含文件名、章节、位置、原文和相似度；用户消息为空';
COMMENT ON COLUMN message.retrieval_trace IS '检索追踪（JSON）：searchQuery 为改写后实际用于检索的问题，hits 为命中片段 ID 和分数，用于排查回答不准的问题';
COMMENT ON COLUMN message.created_at IS '创建时间';
