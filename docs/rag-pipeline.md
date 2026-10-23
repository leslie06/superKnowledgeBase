# 超级知识库：文档摄入与检索问答实现说明

2026-10-15 · [在线版](https://claude.ai/code/artifact/65ce178b-e780-41b2-9bfe-34d933a50508)

## 概述

系统有两条核心链路：**摄入**把上传的 Word / Excel / PPT / PDF 变成带出处信息的向量片段，存进 PostgreSQL；**检索问答**把用户问题变成向量，在同一个库里召回最相关的片段，拼成带编号的参考资料交给 DeepSeek 流式回答，并要求标注 `[n]` 引用。

两条链路共用同一个 embedding 模型（Ollama 本地 bge-m3，1024 维）和同一张 `chunk` 表：摄入写、检索读。向量和业务数据都在 PostgreSQL（pgvector 扩展）里，没有独立的向量数据库。

![两条核心链路 · 摄入 5 步、检索问答 6 步](images/rag-overview.png)

上排每篇文档执行一次，下排每次提问执行一次；两条链路通过 `chunk` 表和同一个 bge-m3 模型衔接。

| 环节 | 代码位置 | 外部依赖 |
| --- | --- | --- |
| 上传、去重、文件存储 | `document/DocumentController`、`DocumentService`、`FileStorage` | 本地磁盘 `./data/files` |
| 异步调度、向量化、入库 | `ingestion/IngestionService`、`ChunkRepository` | Ollama bge-m3、PostgreSQL |
| 解析 | `ingestion/DocumentParser`、`TikaBlockHandler` | Apache Tika 3 |
| 切分 | `ingestion/Chunker` | 无 |
| 问题改写、拼接 Prompt、流式生成 | `chat/ChatController`、`ChatService` | DeepSeek API |
| 向量检索与召回过滤 | `retrieval/Retriever` | Ollama bge-m3、PostgreSQL |

技术栈：Spring Boot 4 + Spring AI 2、Apache Tika 3、PostgreSQL 17 + pgvector；持久层用 `JdbcClient` 手写 SQL，表结构由 Flyway 管理。下文代码均摘自仓库当前版本，略去了与主流程无关的部分。

## 摄入一：上传、去重与异步调度

上传接口只负责「落盘 + 登记」，立即返回；解析、切分、向量化都在后台线程池里异步完成，前端通过轮询文档状态看进度。

**入口**：`POST /api/knowledge-bases/{kbId}/documents`，multipart 字段名 `files`，可一次传多个文件。单文件上限 200MB、单次请求 1GB（`spring.servlet.multipart`）。每个文件单独返回结果：`ACCEPTED` / `DUPLICATE` / `REJECTED`，一个文件失败不影响同批其他文件。

`DocumentService.uploadOne` 的处理顺序：

1. **校验类型**：`DocType.fromFilename` 按扩展名判断，支持 doc/docx、xls/xlsx、ppt/pptx、pdf、txt/md；其他类型和空文件直接 `REJECTED`。
2. **边写边算哈希**：`FileStorage.store` 把上传流写进 `tmp/` 下的临时文件，同时用 `DigestOutputStream` 算 SHA-256，文件只读一遍。
3. **按哈希命名落盘**：移动到 `{storageDir}/{kbId}/{sha256}.{扩展名}`。用内容哈希命名，相同内容只存一份，也避开了文件名里的特殊字符；原始文件名只记在数据库里。
4. **去重**：同一知识库内已有相同 `sha256` 的文档就返回 `DUPLICATE`。表上还有 `UNIQUE (kb_id, sha256)` 约束兜底，并发上传同一文件时由 `DuplicateKeyException` 捕获。
5. **登记并投递任务**：在 `document` 表插入一行，状态 `PENDING`，然后调用 `IngestionService.submit(id)`。

```java
// FileStorage.store：一遍读流同时得到文件和哈希
try (InputStream in = file.getInputStream();
     OutputStream out = new DigestOutputStream(Files.newOutputStream(temp), digest)) {
    in.transferTo(out);
}
String sha = HexFormat.of().formatHex(digest.digest());
String relative = kbId + "/" + sha + "." + DocType.extension(file.getOriginalFilename());
Files.move(temp, resolve(relative), StandardCopyOption.REPLACE_EXISTING);
```

**异步调度**（`IngestionService`）：

- 线程池是固定大小的虚拟线程池，大小取 `skb.ingestion.concurrency`（默认 2）。限制并发是为了不压垮本地 Ollama：批量上传几十个文件时，其余的在队列里等。
- `inFlight` 集合记录正在处理的文档 ID，同一篇文档不会被重复提交；处理中点「重新处理」会得到 409。
- 状态依次变为 `PENDING → PARSING → EMBEDDING → READY`，任一步抛异常则为 `FAILED`，错误信息截取前 500 字存进 `error_msg`，前端直接展示。
- **断点恢复**：应用启动完成时（`ApplicationReadyEvent`），把状态仍是 `PENDING` / `PARSING` / `EMBEDDING` 的文档重新提交。服务中途重启不会留下「永远在处理中」的文档。
- **重新处理**：`POST /api/documents/{id}/reindex` 把状态置回 `PENDING` 再提交，原始文件从磁盘重新读取。调整切分参数或换解析逻辑后用它刷新。

```java
// IngestionService.process：一篇文档的完整摄入过程
documents.updateStatus(documentId, DocumentStatus.PARSING, null);
List<TextUnit> units = parser.parse(fileStorage.resolve(doc.filePath()), doc.filename());
List<ChunkDraft> drafts = chunker.chunk(units, DocType.fromFilename(doc.filename()));
if (drafts.isEmpty()) {
    throw new IllegalStateException("未从文件中提取到文字内容（扫描件或纯图片需要 OCR，当前版本暂不支持）");
}
documents.updateStatus(documentId, DocumentStatus.EMBEDDING, null);
List<float[]> embeddings = embed(doc.filename(), drafts);
transactionTemplate.executeWithoutResult(status -> {
    chunks.deleteByDocument(documentId);
    chunks.insertAll(documentId, doc.kbId(), drafts, embeddings);
    documents.markReady(documentId, drafts.size());
});
```

## 摄入二：解析

解析的产出不是一整段纯文本，而是一串 `TextUnit`：每个单元是一个段落或一行表格，带着它所属的**章节路径**和**物理位置**。这两样信息后面会决定切分边界、参与向量化，并最终显示在引用出处里。

```java
// TextUnit：解析与切分之间的接口
record TextUnit(String text,        // 段落或一行表格
                String headingPath, // 如 "第二章 考勤与假期 > 2.2 年假"
                int unit,           // PDF 页 / 幻灯片 / 工作表序号，从 1 开始；无则为 0
                String location)    // 如 "第3页"、"第2张幻灯片"、"工作表：价格"
```

解析分两层：

1. **Tika 转成统一的块**：`AutoDetectParser` 把任意格式输出成 XHTML 事件流，`TikaBlockHandler`（SAX 处理器）把它整理成三类 `Block`：标题（`h1`–`h6`）、段落、表格（二维单元格）。处理器同时做三件事：
    - 遇到 `div.page` / `div.sheet` / `div.slide-content` 时把物理单元计数加一，记到之后每个块上；
    - 跳过页眉、页脚、嵌入对象和 `script` / `style`；
    - 清理空白：PDF 是按行输出的，中文句子中间的换行会被直接删掉而不是变成空格。
2. **按格式整理成 `TextUnit`**（`DocumentParser`），规则见下表。

| 格式 | 章节路径怎么来 | 位置信息 | 特殊处理 |
| --- | --- | --- | --- |
| Word / txt / md | 优先用标题样式；整篇没有标题样式时用编号规则推断 | 无 | Markdown `#` 标题按 `#` 的个数定级 |
| PDF | 同上（PDF 通常没有样式，靠编号规则） | `第N页` | 按内容流顺序抽取；去页眉页脚和目录、接回被换行截断的句子（见下文）；不做 OCR，不抽取图片 |
| PPT | 每张幻灯片的第一段文字当作标题 | `第N张幻灯片` | 幻灯片之外的内容记为「演讲备注」 |
| Excel | 无 | `工作表：{名称}` | 每行转成「列名: 值」 |

**PDF 文本整理**（`PdfTextCleaner`）。PDFBox 通常一行输出一个段落，直接切块会把版面噪声带进片段，所以 PDF 的块在推断标题前先做三步整理：

1. **去页眉页脚**：不超过 40 字、把数字替换掉后在超过一半页面（且至少 3 页）上重复出现的行，视为页眉页脚删除，如「彩讯科技股份有限公司员工手册」「内部资料，严禁对外传阅和翻印 12」。
2. **去目录行**：以连续点号加页码结尾的行，如「第二章 职业礼仪 ........ 6」。
3. **接回换行截断的句子**：上一行排满版面（长度达到全文 80 分位行长的 70%）且不以句末标点或冒号结尾，下一行也不是编号、项目符号或标题开头，就把两行接成一段。只在同一页内合并。

抽取时没有开启 Tika 的 `sortByPosition`。按坐标排序会把双栏排版中同一高度的左右两栏拼成一行，例如员工手册考勤页会变成「1、迟到　年度考核为"S"的员工……」；保持内容流顺序时，常见排版工具生成的双栏 PDF 会先输出左栏再输出右栏。

**编号规则推断标题**（`guessHeadingLevel`）。很多制度文件用手打的编号代替标题样式，所以按下面的顺序匹配，超过 40 字或以句末标点结尾的段落一律不当标题：

| 形式 | 示例 | 层级 |
| --- | --- | --- |
| 第X章 / 篇 / 部分 | 第三章 请假制度 | 1 |
| 第X节、中文数字加顿号 | 第二节 / 一、总则 | 2 |
| 括号中文数字 | （一）适用范围 | 3 |
| 小数编号（首段最多两位数，排除年份、金额） | 2.1 年假 → 3；2.1.3 → 4 | 段数 + 1 |

章节路径用一个按层级索引的数组维护：遇到 N 级标题就写入第 N 格并清空更深的层级，每个段落取当前数组里的非空项，用 ` > ` 连起来。

**表格转成「列名: 值」**（`tableLines`）。表格如果按行直接拼成文本，切分后中间几行会丢掉表头，变成无法理解的数字串。现在每行都自带表头语义：

```
输入：  | 岗位 | 城市 | 住宿标准 |
        | 经理 | 北京 | 600   |
输出：  岗位: 经理；城市: 北京；住宿标准: 600
```

表格开头只有一个非空单元格的行（合并单元格写的表名）单独保留为一行；不足两行或不足两列的表格退化为用 ` | ` 连接单元格。

解析失败（文件损坏、加密等）会抛出 `IOException("文件解析失败：...")`；解析成功但提不出任何文字（扫描版 PDF、纯图片）会在切分后被拦下，两种情况文档都会变为 `FAILED`。

## 摄入三：切分

`Chunker` 把 `TextUnit` 合并成片段（`ChunkDraft`），原则是**先尊重结构边界，再控制长度**：一个片段只属于一个章节，字数尽量接近 600、不超过 1000。这样召回的片段主题单一，引用出处也能精确到章节。

```java
record ChunkDraft(String content, String headingPath, String location) {}
```

**第一步：拆超长单元**（`split`）。单个段落超过 `max-chars`（1000）时，按句子拆成不超过 `target-chars`（600）的小单元。句子边界是 `。！？!?；;` 和换行；没有任何标点的超长句只能每 600 字硬切。

**第二步：顺序合并**（`chunk`）。依次把单元放进当前片段，放入前先判断是否要先把当前片段结束掉：

| 触发条件 | 类型 | 新片段怎么开头 |
| --- | --- | --- |
| 章节路径变了 | 结构边界（所有格式） | 从空白开始，不重叠 |
| 幻灯片 / 工作表变了 | 结构边界（仅 PPT、Excel） | 从空白开始，不重叠 |
| 当前已达 600 字，或放入后会超 1000 字 | 长度已满 | 带上一块最后一个单元末尾不超过 100 字的完整句子 |

```java
// Chunker.chunk 主循环
boolean boundary = !Objects.equals(last.headingPath(), unit.headingPath())
        || (breakOnUnit && last.unit() != unit.unit());   // breakOnUnit = PPT || EXCEL
boolean full = currentLength >= targetChars || currentLength + unit.text().length() > maxChars;
if (boundary || full) {
    chunks.add(toDraft(current, type));
    current = boundary ? new ArrayList<>() : overlapTail(last);
}
```

**重叠** 只在因长度切分时加（`overlapTail`）。同一章节被切成多块时，后一块重复前一块结尾的几句话，避免跨块的一句话上下文断开。重叠以整句为单位，不会从句子中间开始。如果上一个单元本身就不到 100 字（典型是一行表格），就不重复它，因为整行重复意义不大。

**位置合并**（`toDraft`）。片段内的单元用换行拼接，章节路径和位置取第一个单元的。PDF 允许跨页合并，因为一个章节常常跨页，按页切会拆断正文；这时位置记为页码范围，如 `第3-4页`。前端点击引用打开 PDF 时，用范围的起始页拼成 `#page=3` 跳转。

切分参数都在 `skb.chunk.*`，单元测试 `ChunkerTests` 覆盖了章节切分、PDF 跨页 vs PPT 不跨页、超长段落按句拆分并重叠等场景。

## 摄入四：向量化与存储

每个片段用 Ollama 的 bge-m3 转成 1024 维向量，和原文、出处一起写进 PostgreSQL 的 `chunk` 表。有两个设计要点：向量化的文本和入库的原文不同；旧片段的删除和新片段的写入在同一个事务里。

**向量文本 = 出处信息 + 原文**（`IngestionService.embeddingText`）。片段本身往往不包含文件名和章节名，比如「员工手册」里的「年假」一节正文可能只写「入职满一年享有 5 天……」。把出处拼在前面再向量化，「员工手册里的年假规定」这类问题更容易命中。数据库里只存原文，展示和拼 Prompt 时不会重复这些头部。

```java
static String embeddingText(String filename, ChunkDraft draft) {
    StringJoiner header = new StringJoiner("\n");
    header.add("文件：" + filename);
    if (draft.headingPath() != null) header.add("章节：" + draft.headingPath());
    if (draft.location() != null)    header.add("位置：" + draft.location());
    return header + "\n\n" + draft.content();
}
```

**分批调用 embedding**：每批 `skb.ingestion.embedding-batch-size`（16）个片段调用一次 `EmbeddingModel.embed(List<String>)`。批量调用比逐条调用少很多 HTTP 往返；批次不过大，可以控制单次请求对 Ollama 的内存压力。`EmbeddingModel` 由 Spring AI 自动装配，`spring.ai.model.embedding: ollama` 决定用哪个实现。

**批量入库**（`ChunkRepository.insertAll`）：用 `JdbcTemplate.batchUpdate` 一次写入整篇文档的片段。向量由 `PgVectors.toLiteral` 转成 `[0.1,0.2,...]` 文本，在 SQL 里用 `?::vector` 转换，不需要 pgvector 的 Java 驱动。

```java
jdbcTemplate.batchUpdate("""
        INSERT INTO chunk (document_id, kb_id, chunk_index, content, heading_path, location, embedding)
        VALUES (?, ?, ?, ?, ?, ?, ?::vector)
        """, args);
```

**事务内原子替换**：「删除该文档旧片段 → 插入新片段 → 标记 `READY` 并写入片段数」包在一个 `TransactionTemplate` 里。重新处理一篇文档时，检索要么看到完整的旧片段、要么看到完整的新片段，不会出现半新半旧。耗时的解析和向量化都在事务外完成，事务只包含几条 SQL，不会长时间占用连接。

**表结构**（`V1__init.sql`）：

```sql
CREATE TABLE chunk (
    id           BIGSERIAL PRIMARY KEY,
    document_id  BIGINT  NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    kb_id        BIGINT  NOT NULL,      -- 冗余，检索时按知识库过滤不用关联 document
    chunk_index  INT     NOT NULL,
    content      TEXT    NOT NULL,      -- 片段原文（不含向量化时拼的头部）
    heading_path TEXT,
    location     VARCHAR(200),
    embedding    vector(1024) NOT NULL  -- bge-m3 的维度
);
CREATE INDEX idx_chunk_kb ON chunk (kb_id);
CREATE INDEX idx_chunk_embedding ON chunk USING hnsw (embedding vector_cosine_ops);
```

- 向量索引是 HNSW，距离类型是余弦（`vector_cosine_ops`），参数用 pgvector 默认值（`m = 16`、`ef_construction = 64`）。新片段插入时自动加进索引，不需要重建。
- 删除文档时，片段由外键 `ON DELETE CASCADE` 自动删除，原始文件由 `DocumentService.delete` 从磁盘删除。
- `knowledge_base.embedding_model` 记录建库时用的 embedding 模型。向量维度写死在表结构里，更换模型需要新增迁移并重新处理全部文档。

## 检索问答一：问题改写与问题向量化

多轮对话时，先用 DeepSeek 把最新问题改写成不依赖上下文的完整问题，再拿它去向量化和检索。第一轮问题不改写，直接用原文。

**入口**：`POST /api/conversations/{id}/chat`，请求体 `{"question": "..."}`，响应是 SSE 流。`ChatService.chat` 先做两项前置检查：问题不能为空；DeepSeek Key 已配置。不满足时直接返回一个 `error` 事件，不走后面的流程。

通过后进入 `prepare`，它在 `boundedElastic` 线程上运行，因为这一步全是阻塞调用（查库、改写、向量化）：

1. **取历史**：读取该会话最近 `skb.chat.history-messages`（6）条消息，即最近 3 轮问答。
2. **保存问题**：把本次问题写入 `message` 表。如果是会话的第一个问题，用它的前 30 个字当会话标题。
3. **改写问题**：有历史时调用 `rewriteQuery`，没有历史就用原问题。
4. **检索**：用改写后的问题调用 `Retriever.retrieve`，见下一节。

**为什么要改写**：追问通常是不完整的。上一轮问「年假有几天」，这一轮问「那病假呢？」。直接对「那病假呢？」做向量检索，语义太少召回不准；改写成「病假有几天？」后才能命中病假章节。

```java
private static final String REWRITE_SYSTEM_PROMPT = """
        你是检索查询改写助手。根据对话历史，把用户的最新问题改写成一个语义完整、可以独立用于知识库检索的问题：
        补全代词和省略的主语，保留专有名词、编号和数字。只输出改写后的问题，不要任何解释。
        如果最新问题本身已经完整，原样输出。
        """;
```

- 历史以「用户：… / 助手：…」的纯文本传给模型，每条只取前 300 字。助手的长回答对补全指代帮助不大，截断可以省 token、加快改写。
- 改写用的是同步调用（`.call().content()`），因为后续检索要等它的结果。
- **降级**：改写调用抛异常或返回空内容时，退回用原问题检索，只记一条 warn 日志，不影响后续回答。
- 改写后的问题会作为 `searchQuery` 存进答案消息的 `retrieval_trace`，排查「为什么没检索到」时可以直接看到实际用于检索的是什么。

**问题向量化**在 `Retriever.rank` 里完成：`embeddingModel.embed(query)` 用和摄入时同一个 bge-m3 模型，把问题转成 1024 维向量。问题和片段必须用同一个模型，否则两边的向量不在同一个空间里，距离没有意义。问题不加任何前缀；片段向量化时加的「文件 / 章节 / 位置」头部，是为了让问题里提到的文件名、章节名也能匹配上。

## 检索问答二：混合检索与召回过滤

检索分两路召回再融合，入口是 `Retriever.rank`：

1. **向量召回**：HNSW 余弦检索，取最相近的 `candidates`（30）个，擅长换了说法的问题；
2. **关键词召回**：BM25，同样取 30 个，擅长条款号、编号、型号这类向量不敏感的字面信息；
3. **融合排序**：精确命中问题中编号的片段排最前，其余按加权 RRF 排序；
4. **召回过滤**（仅问答）：丢掉余弦相似度低于 `min-score`（0.5）且没有精确命中的片段，取前 `top-k`（6）个。

为什么要加关键词一路：只有向量召回时，「劳动合同法第三十九条」「民法典第1079条」这类按条款号提问的题，14 道里只有 1 道排第一（`scripts/eval/laws_article.csv`）。bge-m3 把「第三十九条」和「第二十九条」看成几乎一样的东西，而且错误结果的相似度都在 0.5 以上，阈值也拦不住。

### 向量召回

```java
// Retriever.vectorSearch（略去参数绑定）
// 带 kb_id 过滤时让 HNSW 继续扫描，避免存在多个知识库时召回数量不足
jdbc.sql("SET LOCAL hnsw.iterative_scan = relaxed_order").update();
List<Hit> hits = jdbc.sql("""
        SELECT c.id AS chunk_id, 1 - (c.embedding <=> CAST(:vector AS vector)) AS score
        FROM chunk c
        WHERE c.kb_id = :kbId
        ORDER BY c.embedding <=> CAST(:vector AS vector)
        LIMIT :limit
        """)...;
// relaxed_order 下结果可能略微乱序
return hits.stream().sorted(Comparator.comparingDouble(Hit::score).reversed()).toList();
```

逐项说明：

- **`<=>` 与 score**：`<=>` 是 pgvector 的余弦距离运算符，取值 0～2，越小越相似。`1 - 距离` 就是余弦相似度，作为 `score` 返回，越大越相关。
- **走索引**：`ORDER BY 距离 LIMIT k` 正好是 HNSW 索引能加速的查询形式，不会对全表做距离计算。
- **按知识库过滤**：`chunk.kb_id` 是冗余列，过滤时不用先关联 `document`。
- **`iterative_scan`**：HNSW 先在所有知识库的向量里取出一批候选（默认 `ef_search = 40` 个），再按 `kb_id` 过滤。如果候选大多属于别的知识库，过滤后可能凑不够数。pgvector 0.8 的 `iterative_scan` 在数量不够时会继续沿图向外搜。`relaxed_order` 模式为了速度允许结果顺序有轻微偏差，所以代码最后在 Java 里按 score 重新排序。
- **只读事务**：`SET LOCAL` 只在当前事务内有效，必须和查询在同一个事务里，所以两路召回和取详情都放在一个只读的 `TransactionTemplate` 里。设置不会泄漏到连接池里的其他请求。

### 关键词召回（BM25）

PostgreSQL 自带的全文检索不会切中文，装 zhparser / pg_jieba 又要自己构建数据库镜像。所以分词在 Java 里完成（`KeywordAnalyzer`），切好的词项直接写成 `tsvector` 存进 `chunk.keyword_tsv`，PG 只负责 GIN 索引和打分，不再分词。建索引和查询用同一套规则：

| 规则 | 示例 |
| --- | --- |
| 中文按二字组切分，单字连续段保留单字 | 年假天数 → 年假 / 假天 / 天数 |
| 英文、数字按整词保留并转小写；带下划线的再拆出各段 | Q5 → q5；RW_202607050001 → rw_202607050001 / rw / 202607050001 |
| 条款编号统一成阿拉伯数字，编号本身不再切二字组 | 第一千零七十九条、第1079条、1079条 → 第1079条 |
| 全角字符先转半角（NFKC） | 第８２条 → 第82条 |
| 仅查询：先去掉疑问词 | 「是什么」「怎么」「哪些」「多少」「吗」等 |

条款号不切二字组，是因为「三十」「九条」会命中所有「第三十X条」，反而把要找的那条挤下去。疑问词要去掉，是因为它们在制度、法规正文里很少出现，IDF 反而很高。

**索引**：V4 迁移给 `chunk` 加了 `keyword_tsv`（GIN 索引）和 `keyword_token_count`（词项总数，即 BM25 的文档长度）两列。词项来自「章节路径 + 正文」，章节名里的「证件办理」「车辆管理」往往就是用户的检索词。新片段在 `ChunkRepository.insertAll` 写入时生成；V4 之前入库的片段由 `KeywordIndexBackfill` 在应用启动后于后台补齐，只需分词，不用重新向量化。

**打分**：标准 BM25，`k1 = 1.2`、`b = 0.75`。文档数 N、每个词的文档频率 df、平均文档长度都按当前知识库实时统计；词频 tf 取 `tsvector` 中该词项的位置个数：

```text
idf(t)  = ln(1 + (N - df + 0.5) / (df + 0.5))
score   = Σ idf(t) · tf · (k1 + 1) / (tf + k1 · (1 - b + b · 文档长度 / 平均文档长度))
```

SQL 先用 `keyword_tsv @@ 'q5' | '系统' | ...`（任一词项命中，走 GIN 索引）筛出候选，再 `unnest(keyword_tsv)` 取出命中词项的 tf 求和。在当前几百个片段的库上单次查询 15～100 ms。它要对每个候选展开全部词项，片段数到几万时需要关注耗时。

### 融合排序

```java
// 问题里写明了"第39条""Q5"这类编号时，包含它的片段几乎一定是用户要找的，直接排到最前
rows.sorted(comparing(exactMatch).reversed()
        .thenComparing(fused 得分降序)
        .thenComparing(余弦相似度降序))
```

- **精确命中优先**：问题中的「精确词项」指条款编号、字母数字混合的编号（q5、rw_202607050001）和 5 位以上的数字（商品编码等）。片段包含其中任意一个时 `exactMatch = true`，排在所有片段前面。
- **加权 RRF**：其余片段的得分为 `1 / (rrf-k + 向量排名) + keyword-weight / (rrf-k + 关键词排名)`，只出现在一路时只算该路。RRF 只看排名、不看分数，余弦相似度和 BM25 分数的量纲不同也能直接融合。
- **参数**：`rrf-k = 10`、`keyword-weight = 0.25`，是在四份题集上网格搜索得到的。常见的 `k = 60` 等权融合会让 laws 题集的 Hit@1 从 100% 掉到 56%。k 越大，各名次之间的分差越小：k = 60 时向量第 1 名和第 9 名只差 0.002，关键词这一路随便贡献一点就能把顺序翻过来。而二字组 BM25 对改写、口语化的问题噪声很大，例如「租房合同最长能签多少年」会命中一堆含「合同」的劳动合同法条文。
- **结果中的分数**：`score` 始终是与问题的余弦相似度。只从关键词召回的片段会补算余弦相似度，过滤和前端展示仍然统一用它。另有 `keywordScore`（BM25）、`vectorRank`、`keywordRank`、`exactMatch` 字段，用于排查。

不同方式在四份题集上的 Hit@1 / 问答可用率（`python3 scripts/eval_retrieval.py --mode vector|hybrid ...`）：

| 题集 | 内容 | 纯向量 | 混合检索 |
| --- | --- | --- | --- |
| `laws.csv` | 法律法规，改写 / 口语化问法 | 100% / 100% | 100% / 100% |
| `laws_article.csv` | 按条款号提问 | 7.1% / 35.7% | 92.9% / 100% |
| `handbook.csv` | 员工手册（双栏 PDF） | 78.9% / 94.7% | 89.5% / 100% |
| `requirements.csv` | 需求文档中的编码、ID、字段规则 | 80.0% / 85.0% | 95.0% / 100% |

无答案题的拒答率不变（laws 100%，handbook 0%，requirements 33.3%）。混合检索解决的是「找得到、排得准」，判断「库里到底有没有答案」要靠后续的 Reranker。

### 召回过滤

`retrieve()` 在融合后的候选上过滤，只保留 `score >= skb.retrieval.min-score` **或** `exactMatch` 的片段，再取前 `top-k` 个。向量检索永远会返回「最近的 k 个」，哪怕它们和问题毫不相关。阈值让「知识库里没有的内容」可以得到明确的「没找到」，而不是拿无关片段勉强作答。阈值最初取 bge-m3 的经验值 0.45，用 `scripts/eval/laws.csv` 评测后调整为 0.5：正确片段最低分 0.557，无答案题最高分 0.455（0.45 时会被放行）。

精确命中不受阈值限制，是因为编号类问题的余弦相似度往往偏低。例如「Q5 系统是用来做什么的」，正确片段只有 0.426，「商品编码 2025999500042779 对应哪个产品」只有 0.462。

```java
public List<RetrievedChunk> retrieve(long kbId, String query) {
    return rank(kbId, query, Mode.HYBRID, candidates).stream()
            .filter(c -> c.score() >= minScore || c.exactMatch())
            .limit(defaultTopK)
            .toList();
}
```

**调试接口**：`POST /api/knowledge-bases/{kbId}/search`，请求体 `{"query": "...", "topK": 10, "mode": "hybrid"}`。topK 限制在 1～50；`mode` 可选 `hybrid`（默认）、`vector`、`keyword`，用来对比单路效果。它直接调用 `search()`，不做相关性过滤，也不调用 LLM，用来观察排名和分数分布、调整参数。前端「检索测试」会显示每条结果的向量 / 关键词排名和「精确命中」标记。

**直接用 SQL 验证向量召回**：向量召回本身就是上面那条 SQL，可以在 psql 里手动执行，观察召回结果和分数。SQL 没法把文字转成向量，所以「问题」有两种来源。

方法一，拿库里已有片段的向量当问题，查和片段 1 最相似的 6 个片段。这是纯 SQL，先执行 `docker exec -it skb-postgres psql -U skb -d skb` 进入 psql：

```sql
WITH q AS (SELECT kb_id, embedding FROM chunk WHERE id = 1)
SELECT c.id, d.filename, c.heading_path, c.location,
       round((1 - (c.embedding <=> q.embedding))::numeric, 4) AS score,
       left(c.content, 40) AS preview
FROM chunk c JOIN document d ON d.id = c.document_id, q
WHERE c.kb_id = q.kb_id
ORDER BY c.embedding <=> q.embedding
LIMIT 6;
```

第一行是片段 1 自己，score 为 1。

方法二，用真实问题查，和应用的检索一致：先调 Ollama 的 bge-m3 把问题转成 1024 维向量，再用 psql 变量传给 SQL。

```bash
Q='年假有几天'
VEC=$(curl -s localhost:11434/api/embed -d "{\"model\":\"bge-m3\",\"input\":\"$Q\"}" \
  | python3 -c 'import sys,json;print(json.dumps(json.load(sys.stdin)["embeddings"][0],separators=(",",":")))')

docker exec -i skb-postgres psql -U skb -d skb -v vec="$VEC" <<'SQL'
SELECT c.id, d.filename, c.heading_path, c.location,
       round((1 - (c.embedding <=> :'vec'::vector))::numeric, 4) AS score,
       left(c.content, 40) AS preview
FROM chunk c JOIN document d ON d.id = c.document_id
ORDER BY c.embedding <=> :'vec'::vector
LIMIT 6;
SQL
```

这条 SQL 查的是所有知识库。要和应用完全一致，需要在 `ORDER BY` 前加 `WHERE c.kb_id = <知识库 id>`，并忽略 score 低于 0.5 的行。在 `SELECT` 前加 `EXPLAIN` 可以查看执行计划，出现 `Index Scan using ... hnsw` 说明走了 HNSW 索引；数据量小时 PG 可能选择全表扫描。

## 检索问答三：拼接 Prompt 与流式生成

召回的片段先按字数预算截取并编号，再拼成「系统提示 + 历史消息 + 参考资料和问题」交给 DeepSeek 流式生成。模型在句末标注的 `[n]` 和片段编号一一对应，前端据此把引用变成可点击的出处。

**上下文预算与编号**（`toCitations`）。片段按相关度从高到低依次放入，累计字数超过 `skb.chat.max-context-chars`（12000）就停止；第一个片段不受限，保证至少有一条资料。编号从 1 开始，封装成 `Citation`，其中的 `index` 就是回答正文里的 `[n]`。

**无命中短路**。过滤后一条资料都没有时，不调用 LLM，直接返回固定回答「知识库中没有找到与这个问题相关的内容……」。这样既省一次调用，也从根本上排除了模型在没有资料时自己编答案。

**Prompt 的三部分**：

| 部分 | 角色 | 内容 |
| --- | --- | --- |
| 系统提示 | system | 作答规则（见下） |
| 历史消息 | user / assistant 交替 | 最近 6 条消息原文，用于理解对话语境 |
| 本轮输入 | user | 「【参考资料】」带编号和出处的片段 + 「【问题】」用户原始问题 |

```java
private static final String ANSWER_SYSTEM_PROMPT = """
        你是一个严谨的知识库问答助手，根据用户提供的【参考资料】回答问题。
        规则：
        1. 只依据参考资料作答，不要编造资料中没有的信息；资料不足以回答时，直接说明"知识库中没有找到相关信息"，可以给出资料中已有的相关部分。
        2. 在使用了资料的句子末尾标注来源编号，格式为 [1]、[2]；一句话来自多个来源时写成 [1][3]。不要在末尾单独列出参考文献。
        3. 用中文回答，先给出结论，再展开说明；内容较多时使用 Markdown 列表或表格。
        """;
```

本轮输入由 `userPrompt` 拼出，每条资料的来源行包含文件名、章节、位置，模型能据此判断资料之间的关系。示例：

```
【参考资料】
[1] 来源：员工手册.docx ｜ 第二章 考勤与假期 > 2.2 年假
员工入职满一年后享有带薪年假 5 天……

[2] 来源：考勤制度.pdf ｜ 第3-4页
……

【问题】
那病假呢？
```

注意「【问题】」用的是用户原始问题，不是改写后的问题：改写只服务于检索，生成阶段模型可以从历史消息里自己理解指代。

**流式生成与 SSE 事件**（`answer`）。用 Spring AI 的 `ChatClient.prompt().system(...).messages(...).user(...).stream().content()` 得到增量文本流，再与前后事件拼成一条流：

| 顺序 | 事件 | data | 用途 |
| --- | --- | --- | --- |
| 1 | `citations` | `Citation` 数组 | 前端先拿到引用列表，生成过程中 `[n]` 就能点击 |
| 2 | `delta`（多次） | `{"text": "..."}` | 增量文本，前端追加并渲染 Markdown |
| 3 | `done` | `{"messageId": 123}` | 答案已入库 |
| 任意时刻 | `error` | `{"message": "..."}` | 改写、检索或生成失败 |

**答案入库**。流正常结束后，把累积的完整答案、引用列表（`citations` JSONB）和检索轨迹（`retrieval_trace` JSONB）一起写入 `message` 表，然后发送 `done`。检索轨迹记录了实际用于检索的问题和每个命中片段的分数，格式如下（数值为示例）：

```json
{"searchQuery": "病假有几天？", "hits": [{"chunkId": 812, "score": 0.71}, {"chunkId": 640, "score": 0.58}]}
```

用户中途点「停止」会触发流的 `doOnCancel`：已生成的部分加上「（已停止生成）」后照样入库，刷新页面后不会丢失。

## 关键参数与已知限制

下表参数都在 `src/main/resources/application.yaml` 的 `skb.*` 下，绑定到 `common/SkbProperties`，改完重启生效。切分参数只影响之后处理的文档，已有文档需要「重新处理」。

| 参数 | 默认值 | 作用环节 | 说明 |
| --- | --- | --- | --- |
| `ingestion.concurrency` | 2 | 异步调度 | 同时处理的文档数 |
| `ingestion.embedding-batch-size` | 16 | 向量化 | 每次调用 embedding 的片段数 |
| `chunk.target-chars` | 600 | 切分 | 片段目标字数 |
| `chunk.max-chars` | 1000 | 切分 | 片段字数上限；超过它的段落按句拆分 |
| `chunk.overlap-chars` | 100 | 切分 | 因长度切分时的句子级重叠 |
| `retrieval.top-k` | 6 | 检索 | 问答时召回的片段数 |
| `retrieval.min-score` | 0.5 | 召回过滤 | 余弦相似度阈值；精确命中编号的片段不受限制 |
| `retrieval.candidates` | 30 | 混合检索 | 向量、关键词两路各召回的候选数 |
| `retrieval.rrf-k` | 10 | 融合排序 | RRF 常数，越小名次之间的分差越大 |
| `retrieval.keyword-weight` | 0.25 | 融合排序 | 关键词一路在 RRF 中的权重（向量为 1） |
| `chat.history-messages` | 6 | 改写、生成 | 携带的历史消息条数 |
| `chat.max-context-chars` | 12000 | 拼接 Prompt | 参考资料的字数预算 |

**已知限制**：

- **不支持扫描件**：PDF 解析关闭了 OCR，扫描版 PDF 和纯图片内容会以 `FAILED` 结束。图表里的信息也不会被提取。
- **没有重排**：混合检索只按排名融合，没有用 Reranker 判断片段是否真的回答了问题。员工手册、需求文档这类库里，无答案题的最高相似度（0.55～0.58）高于部分正确片段，单靠余弦阈值无法完全区分，「公司班车几点发车」这类问题仍会拿相近章节作答。
- **二字组分词较粗**：关键词召回没有词典，「合同」会命中「劳动合同」「租赁合同」，所以融合时给它的权重较低；按人名、型号精确检索主要靠「精确命中优先」。
- **阈值全局统一**：`min-score` 0.5 是在法律法规题集上校准的，不同知识库的分数分布不同，一个全局阈值不能兼顾所有库。调整前先用 `scripts/eval_retrieval.py --min-score` 在各题集上模拟。
- **向量维度写死**：`vector(1024)` 和 bge-m3 绑定，更换 embedding 模型要改表结构并重新处理全部文档。
- **引用由模型自觉标注**：`[n]` 是否准确依赖提示词约束，系统没有校验模型引用的编号是否真的支持对应的句子。

**后续演进**（摘自 README）：P2 加入 BM25 与向量的混合召回（RRF 融合）、Reranker 精排、父子块和评估测试集，改动集中在 `Retriever`；P3 引入 MinerU / Docling 处理复杂 PDF 和 OCR，改动集中在 `DocumentParser`。
