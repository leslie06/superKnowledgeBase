# 超级知识库：文档摄入与检索问答实现说明

2026-10-29 · [在线版](https://claude.ai/code/artifact/65ce178b-e780-41b2-9bfe-34d933a50508)

## 概述

系统有两条核心链路：**摄入**把上传的 Word / Excel / PPT / PDF 变成带出处信息的向量片段和关键词索引，存进 PostgreSQL；**检索问答**用向量和关键词两路在同一个库里召回片段，经交叉编码器精排、过滤掉不相关的片段后，拼成带编号的参考资料交给 DeepSeek 流式回答，并要求标注 `[n]` 引用。

两条链路共用同一个 embedding 模型（Ollama 本地 bge-m3，1024 维）和同一张 `chunk` 表：摄入写、检索读。向量和业务数据都在 PostgreSQL（pgvector 扩展）里，没有独立的向量数据库。

![两条核心链路 · 摄入 5 步、检索问答 7 步](images/rag-overview.png)

上排每篇文档执行一次，下排每次提问执行一次；两条链路通过 `chunk` 表和同一个 bge-m3 模型衔接。

| 环节 | 代码位置 | 外部依赖 |
| --- | --- | --- |
| 上传、去重、文件存储 | `document/DocumentController`、`DocumentService`、`FileStorage` | 本地磁盘 `./data/files` |
| 异步调度、向量化、入库 | `ingestion/IngestionService`、`ChunkRepository` | Ollama bge-m3、PostgreSQL |
| 解析 | `ingestion/DocumentParser`、`TikaBlockHandler` | Apache Tika 3 |
| 切分 | `ingestion/Chunker` | 无 |
| 问题改写、拼接 Prompt、流式生成 | `chat/ChatController`、`ChatService` | DeepSeek API |
| 混合检索与召回过滤 | `retrieval/Retriever`、`KeywordAnalyzer` | Ollama bge-m3、PostgreSQL |
| 精排 | `retrieval/RerankClient`、`scripts/rerank_server.py` | 本地 Rerank 服务（bge-reranker-v2-m3） |

技术栈：Spring Boot 4 + Spring AI 2、Apache Tika 3、PostgreSQL 17 + pgvector；持久层用 `JdbcClient` 手写 SQL，表结构由 Flyway 管理。下文代码均摘自仓库当前版本，略去了与主流程无关的部分。

## 摄入一：上传、去重与异步调度

上传接口只负责「落盘 + 登记」，立即返回；解析、切分、向量化都在后台线程池里异步完成，前端通过轮询文档状态看进度。

**入口**：`POST /api/knowledge-bases/{kbId}/documents`，multipart 字段名 `files`，可一次传多个文件。单文件上限 200MB、单次请求 1GB（`spring.servlet.multipart`）。每个文件单独返回结果：`ACCEPTED` / `DUPLICATE` / `REJECTED`，一个文件失败不影响同批其他文件。

`DocumentService.uploadOne` 的处理顺序：

1. **校验类型**：`DocType.fromFilename` 按扩展名判断，支持 doc/docx、xls/xlsx、ppt/pptx、pdf、txt/md；其他类型和空文件直接 `REJECTED`。
2. **边写边算哈希**：`FileStorage.store` 把上传流写进 `tmp/` 下的临时文件，同时用 `DigestOutputStream` 算 SHA-256，文件只读一遍。
3. **按哈希命名落盘**：移动到 `{storageDir}/{kbId}/{sha256}.{扩展名}`。用内容哈希命名，相同内容只存一份，也避开了文件名里的特殊字符；原始文件名只记在数据库里。
4. **去重**：同一知识库内已有相同 `sha256` 的文档就返回 `DUPLICATE`。表上还有 `UNIQUE (kb_id, sha256)` 约束兜底，并发上传同一文件时由 `DuplicateKeyException` 捕获。
4. **新版本替换旧版本**：内容没有重复，但同一来源已有文档（文件夹导入按 `source_path`，单独上传按文件名）时，视为文件被修改过：`DocumentRepository.replaceFile` 在原文档上换成新文件、状态改回 `PENDING`，删掉旧文件和不再被引用的识别缓存，再投递处理，返回 `UPDATED`。文档 id 不变；重新处理时删旧片段、写新片段在同一事务里，新版本处理完成前旧内容仍可检索。旧版本正在处理中时拒绝上传，避免两次处理交错。没有这一步时，改过的文件重新导入会变成第二个文档，新旧内容同时被检索到，回答可能自相矛盾。
   同步删除不在后端做：页面导入预览和 `import_dir.py` 拿到的是整个目录的文件清单，知识库里路径属于这个目录、清单里却没有的文档就是被删掉的文件，由用户确认后逐个删除；本次上传中返回 `DUPLICATE` 的文档内容仍在（只是移动或改名），不删。
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
- 状态依次变为 `PENDING → PARSING → EMBEDDING → READY`，有扫描页的 PDF 在解析中途进入 `OCR`（文字识别，见下文）。任一步抛异常则为 `FAILED`，错误信息截取前 500 字存进 `error_msg`，前端直接展示。应用关闭导致的中断不算失败，状态保持不变，下次启动继续。
- **断点恢复**：应用启动完成时（`ApplicationReadyEvent`），把状态仍是 `PENDING` / `PARSING` / `OCR` / `EMBEDDING` 的文档重新提交。服务中途重启不会留下「永远在处理中」的文档。
- **重新处理**：`POST /api/documents/{id}/reindex` 把状态置回 `PENDING` 再提交，原始文件从磁盘重新读取。调整切分参数或换解析逻辑后用它刷新。

```java
// IngestionService.process：一篇文档的完整摄入过程
documents.markParsing(documentId);
// 扫描页的文字识别在 parse 内完成，进度回调把状态置为 OCR 并记录已识别页数
List<TextUnit> units = parser.parse(fileStorage.resolve(doc.filePath()), doc.filename(), doc.sha256(),
        (done, total) -> documents.updateOcrProgress(documentId, done, total));
List<ChunkDraft> drafts = chunker.chunk(units, DocType.fromFilename(doc.filename()));
if (drafts.isEmpty()) {
    throw new IllegalStateException("未从文件中提取到文字内容……");   // 区分是否没有编译识别工具
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
| PDF | 同上（PDF 通常没有样式，靠编号规则） | `第N页` | 按内容流顺序抽取；没有文字层的扫描页做文字识别（见下文）；去页眉页脚和目录、接回被换行截断的句子；不抽取图片 |
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
| 第X编、单独一行的「附则」 | 第二编 物 权 | 1 |
| 第X分编 | 第一分编 通 则 | 2 |
| 第X章 / 篇 / 部分 | 第三章 请假制度 | 3 |
| 第X节、中文数字加顿号 | 第二节 / 一、总则 | 4 |
| 括号中文数字 | （一）适用范围 | 5 |
| 小数编号（首段最多两位数，排除年份、金额） | 2.1 年假 → 5；2.1.3 → 6 | 段数 + 3，最多 8 |

「编」「分编」是法律的结构，在「章」之上，民法典的章节路径因此是「第二编 物 权 > 第一分编 通 则 > 第一章 一般规定」。层级只用来决定谁覆盖谁，数字本身没有含义，没有「编」的文档里路径不受影响。

章节路径用一个按层级索引的数组维护：遇到 N 级标题就写入第 N 格并清空更深的层级，每个段落取当前数组里的非空项，用 ` > ` 连起来。

**样式标题去掉自动编号**（`stripHeadingNumber`）。Word 多级列表的编号由 Tika 计算，经常算错，需求文档里出现过「1.4.1.4.1.1.1. 查看活动」这样的标题。错误的编号进入章节路径后，会跟着进入向量文本和精排输入，所以样式标题开头的小数编号一律去掉，只保留标题文字。「第二章」这类中文编号不受影响。

**丢弃噪声行**。单独成行的页码（「－1－」「- 2 -」）和「目录」标题没有检索价值，解析时直接丢弃。

**去掉编码内容**（`stripEncoded`）。有的 PDF 嵌着 Adobe 导出设置，Tika 会把它当正文提取出来：先是「<< /ASCII85EncodePages false /AllowTransparency false …」这样的设置字典，再是几十种语言的说明，以 UTF-16 十六进制存放（「0065002000410064…」）。一本《Java 并发编程实践》因此多出 56 个乱码片段，它们向量几乎相同，把 HNSW 检索引偏（见向量召回）。解析后对每段文字处理：「/设置名 true|false」出现 3 次以上的整段丢弃；200 个字符以上、含 a～f 字母的十六进制串去掉，去掉的部分超过一半时整段丢弃。要求含字母，是为了保留教材里纯 0/1 的二进制示例；带空格的十六进制转储、路径、网址都不受影响。

**表格转成「列名: 值」**（`tableLines`）。表格如果按行直接拼成文本，切分后中间几行会丢掉表头，变成无法理解的数字串。现在每行都自带表头语义：

```
输入：  | 岗位 | 城市 | 住宿标准 |
        | 经理 | 北京 | 600   |
输出：  岗位: 经理；城市: 北京；住宿标准: 600
```

表格开头只有一个非空单元格的行（合并单元格写的表名）单独保留为一行；不足两行或不足两列的表格退化为用 ` | ` 连接单元格。

解析失败（文件损坏、加密等）会抛出 `IOException("文件解析失败：...")`；解析成功但提不出任何文字（识别工具不可用时的扫描版 PDF、纯图片）会在切分后被拦下，两种情况文档都会变为 `FAILED`。

**扫描版 PDF 的文字识别**（`DocumentParser.withOcr`、`OcrService`）。扫描书的页面是图片，PDFBox 取不到文字。导入的 109 份真实资料里，23 本 PDF 是扫描版：5 本直接失败，另外 18 本只取到了 PDF 自带的书签目录，状态显示「已就绪」，正文却完全搜不到（如《高性能 MySQL》72MB，只有 1 个片段）。处理方式：

1. **找扫描页**：Tika 对每一页都会输出 `div.page`，没有文字的页也一样，所以单元序号就是页码。统计每页文字层的字数（不含空白；先用 `PdfTextCleaner.removeHeadersAndFooters` 去掉每页重复的短行，否则每页都带一行"更多资源请访问……"水印的扫描书会被整本当成有文字层），少于 `skb.ocr.min-page-chars`（20）的页需要识别。按页判断，一本书里只有部分页是扫描的也能处理。
2. **识别**：`OcrService` 调用 `skb.ocr.command`（`scripts/ocr/vision-ocr`，Swift 编写，用 PDFKit 把页面按 200 DPI 渲染成图片，再用苹果 Vision 识别中英文），每识别完一页输出一行 JSON，后端随即写入缓存、更新进度。用命令行而不是常驻服务，不需要额外启动任何东西；换引擎只需提供参数和输出格式相同的命令。
3. **缓存**：结果按文件 SHA-256 + 页码存进 `ocr_page`。一本书识别要几分钟，中途重启后从缓存接着识别，修改切块规则后重新处理也不用再识别；同一文件在多个知识库里共用。删除文档或知识库后，清理没有文档引用的缓存。
4. **合并**：识别结果一行一个段落，加到对应页上。扫描页上的少量文字层（如水印）渲染成图片后会被再识别一遍，而且带错字、每页写法不同（"更多资源"识别成"雨多咨源"），`PdfTextCleaner` 按完全相同去重去不掉，所以先丢弃与同页文字层某行编辑距离相似度 ≥ 0.6 的识别行（只比较文字、字母和数字）。之后与其他页按页码稳定排序，再交给 `PdfTextCleaner` 去页眉页脚、接断行，后面的标题推断和切分与普通 PDF 完全相同。

**引擎选型**：在《高性能 MySQL》《Redis 设计与实现》《算法导论》的 7 页扫描页上对比了苹果 Vision 和 RapidOCR（PaddleOCR 模型的 ONNX 版）。普通正文两者结果 96%～99% 一致，分歧处各对一半左右，错误类型不同：Vision 会把代码里的括号逗号识别成中文全角、偶尔把 `col1` 变成 `COL1`；RapidOCR 常混淆 `0/O`、`l/1`，还会把英文单词连在一起（`OPTIMIZETABLE`），影响英文关键词检索。两者都认不出数学公式。速度和内存差距明显：Vision 每页 0.5～0.9 秒、内存约 200MB；RapidOCR 每页 2～5 秒、约 900MB。23 本书共 11229 页，Vision 约 2 小时，RapidOCR 约 10 小时，所以选 Vision。它的全角标点问题在识别工具里处理：以英文为主的行把全角标点换回半角。

端到端实测《深入学习 MongoDB》（139 页，135 页需要识别）：识别 61 秒，切出 187 个片段（识别前 2 个），章节结构也能推断出来（「第 3 章 > 3.1 选择片键」）；第二次处理全部命中缓存，0.1 秒。

## 摄入三：切分

`Chunker` 把 `TextUnit` 合并成片段（`ChunkDraft`），原则是**先尊重结构边界，再控制长度**：一个片段只属于一个章节，字数尽量接近 600、不超过 1000。这样召回的片段主题单一，引用出处也能精确到章节。

```java
record ChunkDraft(String content, String headingPath, String location) {}
```

**第一步：把不可分的段落归成组**（`groups`）。按段落直接合并时，切点可能落在「有下列情形之一的：」和「（一）（二）（三）」之间，列举项和题干被分到两个片段。以前劳动合同法第十四条就是这样：精排只看到片段里的三个列举项，看不到「应当订立无固定期限劳动合同」，LLM 拿到的资料也不完整。所以切分前先把下面这些情况归成一组，后面只在组与组之间切：

| 后一段落 | 归入上一段落所在的组，条件是 |
| --- | --- |
| 以「第X条」开头 | 从不归入，总是开始新组 |
| 法律文本中的其他段落 | 总是归入，它是上一条的款或项。至少 3 个段落以「第X条」开头时才认定为法律文本 |
| 列举项：（一）、1、、1.、a)、一、、项目符号 | 总是归入 |
| 其他段落 | 上一段落以冒号结尾（引导句）时归入 |

组不跨章节；PPT、Excel 不分组，每行、每段各自成组。

**第二步：拆超长单元**（`split`）。单个段落超过 `max-chars`（1000）时，按句子拆成不超过 `target-chars`（600）的小单元，拆出来的小单元仍属于原来的组。句子边界是 `。！？!?；;` 和换行；没有任何标点的超长句只能每 600 字硬切。

**第三步：按组合并**（`chunk`）。依次把组放进当前片段，放入前先判断是否要先把当前片段结束掉：

| 触发条件 | 类型 | 新片段怎么开头 |
| --- | --- | --- |
| 章节路径变了 | 结构边界（所有格式） | 从空白开始 |
| 幻灯片 / 工作表变了 | 结构边界（仅 PPT、Excel） | 从空白开始 |
| 当前已达 600 字，或放入整组后会超 1000 字 | 长度已满 | 从空白开始 |

```java
// Chunker.chunk 主循环
boolean boundary = !Objects.equals(last.headingPath(), first.headingPath())
        || (breakOnUnit && last.unit() != first.unit());   // breakOnUnit = PPT || EXCEL
boolean full = currentLength >= targetChars || currentLength + groupLength > maxChars;
if (boundary || full) {
    chunks.add(toDraft(current, type));
    current = new ArrayList<>();
}
```

组与组之间切分时不重叠，因为组边界本身就是语义边界。以前按长度切分时会带上前一块末尾的句子，法律片段因此常以上一条的半截内容开头。

**超长的组只能在组内切**（`splitGroup`）。一组超过 1000 字时（典型是列举很多项的条文），按单元拆成多个片段，后续片段的开头二选一：

- 组内出现过不超过 300 字的引导句（以冒号结尾）时，重复最近的那一句，让列举项不脱离题干。例如「第十条 有下列情形之一的，不得解除合同：」后面的（四）（五）项，所在片段也以这句开头；
- 否则带上一块最后一个单元末尾不超过 100 字的完整句子（`overlapTail`），避免跨块的一句话上下文断开。重叠以整句为单位；上一个单元本身不到 100 字时（典型是一行表格）不重复它。

组内的最后一段留在当前片段里，可以和后面的短组继续合并。

**按组切分的效果**（六份题集，改动前后各跑一次）：检索评测 laws 的 Hit@1 从 93.8% 升到 100%，laws_hard 从 55.0% 升到 70.0%，其余题集持平；答案级评测答对从 104/109 升到 106/109，「劳动合同法第十四条」从部分答对变为答对。也验证过「缩短片段能否提高精排分数」：把「10岁的孩子自己买游戏装备算数吗」对应的 671 字片段缩到只剩第十九条，精排分数仍是 0.0025，这道题差在口语化问法与法条术语对不上，不在片段长度，所以没有调小 `target-chars`。

**位置合并**（`toDraft`）。片段内的单元用换行拼接，章节路径和位置取第一个单元的。PDF 允许跨页合并，因为一个章节常常跨页，按页切会拆断正文；这时位置记为页码范围，如 `第3-4页`。前端点击引用打开 PDF 时，用范围的起始页拼成 `#page=3` 跳转。

切分参数都在 `skb.chunk.*`，单元测试 `ChunkerTests` 覆盖了章节切分、PDF 跨页 vs PPT 不跨页、超长段落按句拆分并重叠、列举项不脱离引导句、法律条文不被切开、超长列举重复引导句等场景。

## 摄入三之二：文档摘要

片段检索只能回答落在某一段里的问题。「《微服务设计》讲了什么」「哪些资料讲到了 MySQL」的答案分散在整份文档里，任何一个 600 字的片段都答不全；按片段检索时，命中的往往是前言里的一句话，或者某一章的细节。

所以每个文档切块后再让 DeepSeek 写一段摘要（`DocumentSummarizer`），作为一个特殊片段和正文一起入库：`chunk.kind = 'SUMMARY'`、`chunk_index = -1`、章节为「文档摘要」。它和正文片段走同样的向量召回、关键词召回和精排，检索范围、去重、上下文扩展都不用特殊处理（序号 -1 和章节名保证它不会被当成正文的相邻片段）。整体性问题由摘要片段命中，具体问题仍由正文片段命中，精排负责在两者之间排序。

- **输入**（`buildInput`，总字数 `skb.summary.input-chars`，6000）：文件路径、目录（去重后的章节路径）、开头几个片段、在剩余正文中均匀抽取的 8 段，三部分各占三分之一，目录或开头用不完的份额留给后面。几百页的书不可能全文输入：目录最能概括结构，开头通常有前言或简介，抽样补上正文的实际内容。
- **输出**：第一句说明文档类型和主题，再概括主要内容，最后一行「关键词：」列出 5～10 个关键词，不超过 300 字，temperature 0.2。
- **关键词索引加上文件出处**：摘要片段的关键词除了摘要正文，还包括「文档摘要」和文件路径，「《微服务设计》讲了什么」能靠书名命中。正文片段的文件名只拼进向量文本，不进关键词索引，否则同一本书的所有片段都会命中书名。
- **缓存与更新**：摘要原文存在 `document.summary`。重新处理（如修改切块规则）时直接复用，不再调用 LLM；文件换成新版本时 `replaceFile` 把它清空，重新生成。
- **已有文档**：`SummaryBackfill` 在启动时找出已就绪但没有摘要片段的文档，在后台逐个生成并写入，只调用一次 LLM 和一次向量化，不重新处理正文。一次一个，不占用摄入线程池；与正常处理共用"处理中"标记，同一文档不会同时被重新处理和补摘要。失败的下次启动再试。
- **短文档不生成摘要片段**（`skb.summary.min-chunks`，5）：摘要是为几百页的书准备的。只有一两个片段的文档（居住证、简历、激活码文本），正文就是全部内容，摘要只是换个说法重复一遍，精排却常常把它排在正文前面：14 个各 4 个片段的 JetBrains 激活码文件，每个都有一份「JetBrains 激活码」摘要，问「JetBrains 软件怎么激活」时前 5 名全是这些摘要，写着操作步骤的「操作教程.pdf」掉到第 10。所以正文片段数不够的文档既不调用 LLM，也不写摘要片段；`SummaryBackfill` 启动时清掉已有的（摘要原文仍留在 `document.summary` 供查看）。
- **失败不影响处理**：未配置 DeepSeek Key、接口出错时只记 WARN，文档照常就绪，只是没有摘要片段。`document.chunk_count` 只统计正文片段。

## 摄入四：向量化与存储

每个片段用 Ollama 的 bge-m3 转成 1024 维向量，和原文、出处一起写进 PostgreSQL 的 `chunk` 表。有两个设计要点：向量化的文本和入库的原文不同；旧片段的删除和新片段的写入在同一个事务里。

**向量文本 = 出处信息 + 原文**（`IngestionService.embeddingText`）。片段本身往往不包含文件名和章节名，比如「员工手册」里的「年假」一节正文可能只写「入职满一年享有 5 天……」。把出处拼在前面再向量化，「员工手册里的年假规定」这类问题更容易命中。数据库里只存原文，展示和拼 Prompt 时不会重复这些头部。

文件夹导入的文件（页面上「选择文件夹」或 `scripts/import_dir.py`），「文件」一行用的是相对路径而不是文件名，例如「资料/人事制度/2024/考勤管理办法.docx」。资料通常按部门、产品、年份分文件夹存放，目录名就是现成的分类，「人事制度里的考勤规定」这类问题能靠它命中。路径在上传时随表单的 `paths` 字段传入，`DocumentService.sourcePath` 统一分隔符、去掉开头的 `/`，只有文件名或试图跳出所选文件夹（`../`）时不记录；存在 `document.source_path`。

```java
static String embeddingText(String source, ChunkDraft draft) {   // source：相对路径，没有时为文件名
    StringJoiner header = new StringJoiner("\n");
    header.add("文件：" + source);
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

## 检索问答一：查询改写与问题向量化

每个问题检索前，先用 DeepSeek 把它改写成文档里可能使用的术语，多轮对话时同时补全指代；检索用的是「原问题 + 换行 + 改写结果」。

**入口**：`POST /api/conversations/{id}/chat`，请求体 `{"question": "..."}`，响应是 SSE 流。`ChatService.chat` 先做两项前置检查：问题不能为空；DeepSeek Key 已配置。不满足时直接返回一个 `error` 事件，不走后面的流程。

通过后进入 `prepare`，它在 `boundedElastic` 线程上运行，因为这一步全是阻塞调用（查库、改写、向量化）：

1. **取历史**：读取该会话最近 `skb.chat.history-messages`（6）条消息，即最近 3 轮问答。
2. **保存问题**：把本次问题写入 `message` 表。如果是会话的第一个问题，用它的前 30 个字当会话标题。
3. **查询改写**：调用 `QueryRewriter.searchQuery(历史, 问题)`，第一轮也改写。
4. **检索**：用改写得到的检索文本调用 `Retriever.retrieve`，见下一节。

**为什么要改写**，有两个原因：

- **口语和术语对不上**：「10岁的孩子自己买游戏装备算数吗」对应的法条写的是「八周岁以上的未成年人为限制民事行为能力人，实施民事法律行为……」，两边几乎没有共同的词。向量召回勉强能找到，但精排只给 0.003 分，被阈值拦下。把问题改写成「限制民事行为能力人 未成年人 民事法律行为效力」后，同一个片段的精排分数是 0.95。
- **追问不完整**：上一轮问「年假有几天」，这一轮问「那病假呢？」。直接对「那病假呢？」检索，语义太少召回不准。

补全指代要有边界。最初的提示词只说「补全省略的主语和指代」，实际使用中出现过：前几轮一直在问租房合同，接着问「康昱的专业是什么」，模型把它改写成「康昱 专业 相寓租赁合同 AFCZ11909729ZY 承租人信息」，检索结果全是租赁合同，学历备案表被挤出前 6，最后答「没有找到」。所以第 1 条加了限制：问题本身意思完整时，不要把历史中的文档名、编号、话题加进查询。回放这段对话，改后为「康昱 专业」；「押金是多少」「它什么时候到期」这类真正的追问仍会补上合同名。限制写得太长、带例子时，模型对单轮问题也变得保守（128 道评测题中 8 道原样输出，原来 1 道），所以只加了一句。

**古籍用原文的旧式说法**。《金瓶梅》写的是「这西门大官人先头浑家陈氏早逝」，问「西门庆第一个女人是谁」改写成「西门庆 第一个 女性 人物关系」，和原文对不上，这一段排第 7，没进交给模型的 6 个片段，模型答「资料中没有说明」。改写规则 2 加了一句：问的是古籍、古典小说等文言或旧白话作品时，同时写出原文可能使用的旧式说法（妻子、老婆→浑家、娘子，原配、第一任→先头）。之后这道题和「西门庆的原配妻子是谁」都排第 1（`scripts/eval/library.csv`）。

**多轮对话单独用一个提示词**（`MULTI_TURN_PROMPT`）。一句限制仍然不够：多轮评测题集（`scripts/eval/multiturn_*.csv`，每题带前几轮的问答）显示，追问补全指代（「那字典呢」「它的作者是谁」「男员工呢」）基本都对，失败集中在话题切换和用户纠正——前几轮问康昱，换话题问「毕业答辩论文的题目是什么」被补成「康昱毕业答辩论文」；用户说「不一定是康昱的，找找别人的」，原问题里被否定的「康昱」又被拼进检索文本。所以有对话历史时，改用一个要求输出 JSON 的提示词：先判断最新问题是否依赖上文（指代、省略、追问、纠正为 true，意思完整的新问题为 false），依赖时补全成完整问题、纠正时去掉被否定的人名书名版本，不依赖时禁止带入历史中的人名、书名和话题。**依赖上文时检索文本只用补全后的查询**，不再拼原问题：「那字典呢」「不一定是康昱的」脱离对话没有意义，还可能带着被否定的内容。JSON 解析失败时按不依赖上文处理，最多退化成原来的效果。单轮提问仍用原提示词和「原问题 + 改写」，六份单轮题集按它校准过。

**汇总类问题拆成子问题多路检索**（`QueryPlanner` + `Retriever.retrieveAggregate`）。一次检索取 6 个片段，答案落在一两段里的问题够用；「哪些行为会被公司直接解除劳动合同」的答案分散在员工手册好几章，「金瓶梅里西门庆的几位妻妾分别长什么样」要看每个人物各自的描写，一次检索只能覆盖其中一部分。所以与查询改写并行再调一次 DeepSeek，判断问题是否需要汇总（列举全部、比较多个对象、概括整本书或多个人物、统计「最多」），需要时拆成 2～6 个子问题，每个只针对一个对象：一个人物、一种结构、一类情形，按资料中会出现的具体名称拆（「Redis 有哪些底层数据结构」拆成简单动态字符串、链表、字典、跳跃表、整数集合、压缩列表，而不是「字符串和哈希」「列表和集合」）。拆分时允许模型用自己的知识点出要查的对象，但只用于检索，回答仍只依据资料。

然后原问题和每个子问题各检索一次（并行；子问题只精排 10 个），只留相关片段，**轮流**从各路取片段合并去重，最多 12 个：统一按分数排时，分数普遍偏高的一路会占满名额，某个人物、某种结构就没有资料。参考资料预算放宽到 24000 字，提示词附加「逐项整理、分别标注来源，资料只覆盖一部分时说明可能不完整，资料中没有的项不要补充」。

效果（检索评测，每条证据一行，问答可用 = 证据进入交给模型的片段）：

| 题集 | 不拆分 | 拆成 2～4 个 | 拆成 2～6 个、每个一个对象 |
| --- | --- | --- | --- |
| `aggregate_library.csv`（5 题 18 条证据） | 61.1% | 83.3% | 94.4% |
| `aggregate_handbook.csv`（4 题 17 条证据） | 100% | 100% | 100%（「直接解除合同」4 条证据排名 5,1,4,1 → 2,1,2,1） |

Redis 的 6 种底层结构从召回 2 种变成全部召回。仍未召回的一条：李瓶儿的外貌是吴月娘转述的，那段原文没有出现「李瓶儿」三个字。单轮、多轮题集回归无退步（员工手册难题 96.6% → 100%，真实资料库 88.5% → 96.2%），普通问题很少被误判为汇总类（每份 0～4 题，多是「有哪些」类本该汇总的问题）。代价是汇总类问题检索要 9～22 秒（原问题 + 6 个子问题共检索 7 次，精排服务一次处理一个请求），普通问题不受影响（约 4 秒）。

**理解问题的本意**（`QueryPlanner.Plan.intent`）。「西门庆死在谁的床上」是口语，意思是「西门庆是因为哪个女人而死的」，不是问他断气时躺在哪张床上。照字面检索，「床上」在书里出现上百次；照字面回答，模型会说「资料没说明他死在谁的床上」。问题规划本来就要调一次 LLM，顺便让它写出本意：口语、俗语、比喻或反问照字面会答偏时给出本意，否则留空。本意非空时另检索一路，与原问题的结果轮流合并；判断资料够不够时按本意；回答时在问题后附「（问题的意思：…）」，回答规则也要求按本意回答、不按字面纠正用户。

**迭代检索**（`RetrievalPipeline` + `EvidenceChecker`，`skb.retrieval.max-follow-ups`，2）。只检索一次时，问法和原文对不上就找不到：「西门庆死在谁的床上」改写成「西门庆 死在 谁 床上」，「床上」在书里出现上百次，交给模型的 10 段全是生前的床笫情节，原文写的却是「贪欲丧命」「断气身亡」「三四丸药儿」，模型只能答「资料中没有关于西门庆死亡场景的记载」。所以首轮检索后，再请 DeepSeek 看一遍问题、已试过的查询和每段资料（各取前 800 字），判断能否回答或推出答案；不能时说明缺什么，并给出 1～2 个新查询——换原文可能使用的说法（「死」→「断气身亡」「丧命」「临终」），或指向答案可能在的情节、章节、条款，可以用模型对这份资料的了解，但不写答案。新查询并行检索（每个只精排 10 个），去掉已有片段和内容重复的片段后，最多补进 8 个，接在原有资料之后，再判断一次，最多补查 2 轮。首轮一个片段都没检索到时也先补查，补不到才拒答。

**补看前后文**。叙事类文本里，原因写在事件之前，结果写在事件之后：找到「西门庆……呜呼哀哉，断气身亡」这一段后，死因（潘金莲把三丸胡僧药都灌给他、吴神仙诊为「酒色过度」、临终拉着潘金莲交代后事）在之前十来段里，换什么检索词都很难把那几段直接检索出来（原文称潘金莲为「妇人」，提到「胡僧药」「潘金莲」的段落有几十处）。所以判断时还可以给出 `context`：某段资料的编号和方向（before 前文 / after 后文），最多 2 处。`Retriever.surrounding` 取原文中紧挨着它的前文或后文，按原文顺序、累计约一万字，插在那段资料的前面或后面，模型读到的是连贯的原文；每补看一处，参考资料预算多一万字。规章制度同样适用：找到了某条规定，例外情形写在后面。

问答和 `/search` 调试接口共用这一流程（改写与规划并行 → 检索 → 补查），评测脚本因此能直接衡量补查的效果；补查后参考资料预算用汇总类问题的 24000 字。资料够用的问题只多一次判断调用（约 1～2 秒）。判断失败或超时时当作资料足够，不影响问答。题集 `scripts/eval/followup_library.csv` 收了这类「问法与原文对不上」的问题，`--follow-up off` 对比只检索一次的效果。

**多轮改写的效果**（检索评测，问答可用率）：书籍 12 题 91.7% → 100%，员工手册 8 题 100% → 100%，个人文档 5 题 60% → 80%。「不一定是康昱的，找找别人的」「不是崇祯本，在金瓶梅词话里找」从未召回变成第 1 名。仍有一题失败：前两轮问康昱的专业和身份证号，接着问「毕业答辩论文的题目是什么」，模型判为依赖上文、补成「康昱 毕业答辩论文题目」——这题本身有歧义，用户纠正后（上一题）能找到。

```java
// retrieval/QueryRewriter
private static final String SYSTEM_PROMPT = """
        你是知识库检索的查询改写助手。知识库里是企业规章制度、法律法规、产品需求文档等正式文本，用户的提问往往是口语化的，和文档的措辞对不上。
        请把用户的最新问题改写成一条用于检索的查询：
        1. 有对话历史时，先补全最新问题中省略的主语和指代的内容，让查询脱离对话也能看懂；最新问题本身意思完整时，不要把历史中的文档名、编号、话题加进查询。
        2. 把口语说法换成正式文档中可能使用的书面语或专业术语，并补充问题涉及的关键概念。例如"外卖小哥送餐撞了人谁赔"可改写为"用人单位工作人员因执行工作任务造成他人损害 侵权责任承担"。
        3. 保留原问题中的专有名词、编号、数字和限定条件，不要改变问题的意思。
        4. 不要回答问题，不要编造具体的数字、期限或结论。
        5. 问题本身已经是正式表述时，原样输出。
        只输出改写后的查询，一行，不超过 60 字，不要解释。
        """;
```

改写示例（DeepSeek 实际输出）：

| 原问题 | 改写结果 |
| --- | --- |
| 10岁的孩子自己买游戏装备算数吗？ | 限制民事行为能力人 未成年人 购买游戏装备 民事法律行为效力 |
| 捡到别人的钱包必须还吗？ | 拾得遗失物 返还义务 法律规定 |
| 老婆生孩子男员工能休几天陪产假？ | 男员工 配偶生育 陪产假 天数 规定 |
| 民法典1254条 | 《民法典》第一千二百五十四条 条文内容 |

**检索文本 = 原问题 + 改写结果**（`QueryRewriter.combine`，单轮以及多轮中不依赖上文的问题；依赖上文时只用补全后的查询，见上文）。这个文本同时用于向量召回、关键词召回和精排。只取改写结果的第一行，最多 120 字；改写为空或和原问题相同时只用原问题。

选这种用法，是在六份题集上比较了几种方案。为了排除改写随机性的干扰，每道题先让 DeepSeek 改写一次并缓存，各方案用同一份改写结果离线检索（检索评测，问答可用 118 道、无答案题 19 道，每种方案取各自最优的精排阈值）：

| 方案 | 问答可用 | 无答案题拒答 | 说明 |
| --- | --- | --- | --- |
| 不改写（之前） | 117 | 13 | 「10岁买游戏装备」被拦下 |
| 只用改写结果 | 117 | 13 | 改写稍有偏差就丢掉原意，如「婚家能分开休吗」被改成「婚假与家庭假」 |
| **原问题 + 术语改写** | **118** | **13** | 采用 |
| 原问题 + HyDE | 112 | 0 | 让模型写一段「可能的文档原文」再检索。假想原文和真实文档一样「像」，无答案题也全部高分，拒答完全失效 |

改写后，精排看到的是带术语的检索文本，正确片段的分数整体抬高，所以精排阈值从 0.01 提到了 0.05（见下文召回过滤）。

**接入后实测**（真实调用后端，每题实时改写，`scripts/eval/baseline/`）：

| 题集 | Hit@1 | 问答可用率 | 无答案题拒答 |
| --- | --- | --- | --- |
| `laws.csv` | 100% → 93.8% | 100% | 3/4 → 4/4 |
| `laws_article.csv` | 100% → 92.9% | 100% | — |
| `laws_hard.csv` | 70.0% → 75.0% | 95.0% → 100% | 4/4 |
| `handbook.csv` | 94.7% | 100% | 1/3 |
| `handbook_hard.csv` | 72.4% → 69.0% | 100% → 96.6% | 2/5 → 1/5 |
| `requirements.csv` | 95.0% → 90.0% | 100% | 3/3 |
| **合计** | **102 → 99** | **117 → 117** | **13 → 13** |

实测比离线模拟少一道：「10岁的孩子自己买游戏装备算数吗」修好了，但「哪些行为会被公司直接解除劳动合同」的第三条证据从第 4 名掉到第 8 名，掉出了前 6。这是一道多证据列举题，改写把检索文本引向了「解除劳动合同」的通用条款，讲忘打卡造假的那个片段向量排名从第 15 降到第 25，精排后排第 8；讲考勤的片段也从第 4 降到第 6，勉强留在前 6。Hit@1 少了 3 道，掉下来的题都还在前 6 名以内，不影响问答。

答案级评测（见下文）答对 106 → 105/109，变化同样是这两道题一进一出。整体持平，但改写解决的是一类问题（口语与术语对不上，题集之外的真实提问里更常见），退步的是单道多证据题，所以保留改写。

- 历史以「用户：… / 助手：…」的纯文本传给模型，每条只取前 300 字。助手的长回答对补全指代帮助不大，截断可以省 token、加快改写。
- 改写用的是同步调用（`.call().content()`），因为后续检索要等它的结果。每个问题多一次 DeepSeek 调用，首字延迟增加约 1～2 秒。
- 改写单独用 temperature 0（问答生成是 0.3）：同一个问题应得到相同的检索文本，否则检索结果会随机波动。即便如此，DeepSeek 在 temperature 0 下也偶尔给出不同的改写，评测结果会有几个百分点的波动。
- **降级**：改写调用抛异常、返回空内容，或超过 `skb.retrieval.rewrite-timeout`（10 秒）时，退回用原问题检索，只记一条 warn 日志。超时是必须的：DeepSeek 过载时会返回 503（Service is too busy），Spring AI 遇到 503 会自动按指数退避重试，同步调用可能被拖上几分钟。改写放在虚拟线程里执行，超时后中断它。另外 `spring.http.clients.read-timeout`（60 秒）给所有同步 HTTP 调用兜底，防止连接卡住时请求永远挂起。
- 检索文本会作为 `searchQuery` 存进答案消息的 `retrieval_trace`，排查「为什么没检索到」时可以直接看到改写成了什么。
- 生成阶段的「【问题】」仍然是用户原始问题，改写只服务于检索。
- 调试接口 `/search` 默认也先改写，与问答一致；请求体传 `"rewrite": false` 可以关闭，`scripts/eval_retrieval.py --rewrite off` 用它对比改写前的效果。

**问题向量化**在 `Retriever.fuse` 里完成：`embeddingModel.embed(query)` 用和摄入时同一个 bge-m3 模型，把问题转成 1024 维向量。问题和片段必须用同一个模型，否则两边的向量不在同一个空间里，距离没有意义。问题不加任何前缀；片段向量化时加的「文件 / 章节 / 位置」头部，是为了让问题里提到的文件名、章节名也能匹配上。

## 检索问答二：混合检索、精排与召回过滤

检索分两路召回、融合后再精排，入口是 `Retriever.rank`：

1. **向量召回**：HNSW 余弦检索，取最相近的 `candidates`（30）个，擅长换了说法的问题；
2. **关键词召回**：BM25，同样取 30 个，擅长条款号、编号、型号这类向量不敏感的字面信息；
3. **融合排序**：精确命中问题中编号的片段排最前，其余按加权 RRF 排序；
4. **精排**：`rerank.candidates`（20）个片段交给交叉编码器逐对打分（关键词召回的前 3 名一定在内，其余按 RRF 顺序补足，见下文「精排」），按精排分数重排，精确命中仍排最前；
5. **召回过滤**（仅问答）：只保留精排分数 ≥ `rerank.min-score`（0.05）或精确命中的片段，取前 `top-k`（6）个。精排服务不可用时退回 RRF 顺序，改用余弦相似度 ≥ `retrieval.min-score`（0.5）过滤。

为什么要加关键词一路：只有向量召回时，「劳动合同法第三十九条」「民法典第1079条」这类按条款号提问的题，14 道里只有 1 道排第一（`scripts/eval/laws_article.csv`）。bge-m3 把「第三十九条」和「第二十九条」看成几乎一样的东西，而且错误结果的相似度都在 0.5 以上，阈值也拦不住。

**调用链**（`retrieval/Retriever`，下面各小节按这个顺序展开）：

```text
retrieve(kbId, query, scope)                   问答入口；调试接口走 search()，不做过滤
├─ rank()
│  ├─ fuse()
│  │  ├─ embeddingModel.embed(query)           问题向量，事务外先算好
│  │  ├─ KeywordAnalyzer.queryTerms(query)     查询词项
│  │  └─ 只读事务 ─────────────────────────────────────────────
│  │     ├─ scopeDocuments()                   限定范围时先解析出文档 ID
│  │     ├─ vectorSearch()                     → 30 个 (chunkId, 余弦相似度)
│  │     ├─ keywordSearch()                    → 30 个 (chunkId, BM25 分数)
│  │     ├─ 加权 RRF 合并两路排名
│  │     └─ loadChunks()                       → 片段详情、补算余弦、判断精确命中
│  └─ rerank()                                 事务外
│     └─ RerankClient.score() ──HTTP──▶ scripts/rerank_server.py（前 20 个）
└─ filter(relevant).limit(6)
```

### 向量召回

```java
// Retriever.vectorSearch（略去参数绑定）
// 带 kb_id 过滤时让 HNSW 继续扫描，避免存在多个知识库时召回数量不足
jdbc.sql("SET LOCAL hnsw.iterative_scan = relaxed_order").update();
// 默认搜索范围 40 太小，库大了以后会漏掉真正最近的片段
jdbc.sql("SET LOCAL hnsw.ef_search = " + efSearch).update();   // skb.retrieval.ef-search，200
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
- **`ef_search`**：HNSW 不逐个比较全部向量，而是沿近邻图向更近的方向搜索，`ef_search` 是搜索时保留的候选数，pgvector 默认 40，只比每路召回的 30 个多一点。片段少时够用；导入 2.6 万个片段的电子书库后，「康昱的学校 专业是什么」与精确计算的前 30 名一个都不重合：正确答案（学籍报告，余弦 0.564）没有召回，返回的是一本书里余弦只有 0.43 的乱码片段。这些乱码彼此几乎相同，在图里聚成一团，搜索容易被困在里面。`ef_search` 调到 200 后，测试的几个问题都与精确结果完全一致；即使不走索引做精确计算也只要约 170 毫秒，200 的开销可以忽略。
- **索引参数 `m = 32, ef_construction = 128`**（V8，pgvector 默认 16、64）：库涨到 5 万个片段后，只调 `ef_search` 不够了。库里有大量彼此几乎相同的片段（激活码文件、表格行、扫描页识别出的碎片），在默认参数建出的图里聚成一团，搜索会困在里面。用真实资料库的 35 道评测题对比索引召回与精确计算的前 30 名：默认参数在 `ef_search` = 200 时平均只重合 83%，6 道题漏掉了真正最相似的片段，「武松打虎」返回的是《黄帝内经》（余弦 0.46，真正最近的《金瓶梅》片段是 0.61），只有一两个片段的小文档（居住证、租房合同）最容易漏；`REINDEX` 重建后没有改善，说明不是索引损坏。`m = 32` 让每个节点连更多邻居，同样 `ef_search` = 200 下 35 道题全部与精确结果一致，索引大小基本不变，建索引从 31 秒变成 80 秒。另外，`ef_search` 调大后 PG 会估算走索引不划算，自己改成并行全表精确计算（5 万片段约 150 毫秒），所以对比召回时要先看 `EXPLAIN` 里有没有 Index Scan。
- **执行方式由代码决定**：全表精确计算要把每个 4KB 的向量从 TOAST 表里读出来，5 万片段要访问 22 万个数据页（约 1.8GB），内存紧张时超过 1 秒；走 HNSW 只访问 5 千个页，约 5～40 毫秒。PG 的代价估算会选前者，所以 `vectorSearch` 先用 `document.chunk_count` 算出范围内的片段数：超过 5000 个时 `SET LOCAL enable_sort = off`，迫使 PG 走 HNSW；不超过时 `enable_indexscan = off`，按 `document_id` / `kb_id` 索引取出后精确计算（一个文档不到 1 毫秒），避免范围太小时 HNSW 边扫边过滤凑不够数量。查询结束后恢复这两个开关。
- **`plan_cache_mode = force_custom_plan`**：PgJDBC 同一条语句执行 5 次后改用服务端预编译，PG 随后可能换成不看参数值的通用计划。通用计划里 `CAST(:vector AS vector)` 不能提前算好，变成对每一行重新解析 1024 个数字的文本再整表排序，实测一次 8.4 秒（按实际参数规划只要几十毫秒）。检索事务开头设置这个参数，每次按实际参数重新规划。
- **`iterative_scan`**：HNSW 先在所有知识库的向量里取出一批候选（`ef_search` 个），再按 `kb_id` 过滤。如果候选大多属于别的知识库，过滤后可能凑不够数。pgvector 0.8 的 `iterative_scan` 在数量不够时会继续沿图向外搜。`relaxed_order` 模式为了速度允许结果顺序有轻微偏差，所以代码最后在 Java 里按 score 重新排序。
- **只读事务**：`SET LOCAL` 只在当前事务内有效，必须和查询在同一个事务里，所以两路召回和取详情都放在一个只读的 `TransactionTemplate` 里。设置不会泄漏到连接池里的其他请求。

### 限定检索范围

知识库里资料很杂时，可以只在部分文档里检索。范围是 `retrieval/SearchScope`：文件夹列表（对应 `document.source_path` 的前缀，含子文件夹）和文档 ID 列表，取并集，都为空表示整个知识库。问答的范围保存在 `conversation.scope`（JSONB），由问答页输入框上方的选择器修改，追问沿用；调试接口 `/search` 在请求体里传 `folders`、`documentIds`。

```java
// Retriever.scopeDocuments：先把范围解析成文档 ID
SELECT id FROM document
WHERE kb_id = :kbId
  AND (id = ANY (:documentIds)
       OR EXISTS (SELECT 1 FROM unnest(CAST(:folders AS text[])) AS f(folder)
                  WHERE starts_with(source_path, f.folder || '/')))
```

- **按文件夹保存**：勾选整个文件夹时存的是路径而不是展开后的文档 ID，之后导入到这个文件夹的文档自动在范围内。用 `starts_with` 而不是 `LIKE`，文件夹名里的 `_`、`%`（如 `learning_materials`）不会被当成通配符；拼上 `/` 再比较，「计算机」不会匹配到「计算机网络」。
- **两路召回都过滤**：向量召回和 BM25 的 SQL 都加上 `c.document_id IN (:documentIds)`，范围解析出 0 个文档时直接返回空结果。BM25 的 N、df、平均长度仍按整个知识库统计，词的稀有程度不随所选范围变化。
- **不用自己选执行方式**：HNSW 带过滤时，如果范围只占全库很小一部分，`iterative_scan` 要扫很多节点才能凑够数量（上限 `hnsw.max_scan_tuples`，默认 2 万），可能召回不足。实测 PostgreSQL 会按范围大小自己选：在 5 万片段的库里，范围是几千个片段（一个子文件夹）时改走 `idx_chunk_document` 取出后精确计算距离（66～146 毫秒），范围是 4 万个片段（「计算机」整个文件夹）时走 HNSW 过滤（约 130 毫秒），两种情况召回都完整。

### 关键词召回（BM25）

> 性能：命中超过一成片段（且超过 1000 个）的常见词不参与关键词召回。「租房一个月多钱」里的「一个」在 5 万片段的库中命中 2.1 万个，idf 只有 0.85，对排序几乎没有贡献，却让 tf 统计展开两万多个片段的词表，关键词召回要 2～4 秒；跳过后约 0.1 秒。片段少的库不跳过。

PostgreSQL 自带的全文检索不会切中文，装 zhparser / pg_jieba 又要自己构建数据库镜像。所以分词在 Java 里完成（`KeywordAnalyzer`），切好的词项直接写成 `tsvector` 存进 `chunk.keyword_tsv`，PG 只负责 GIN 索引和打分，不再分词。建索引和查询用同一套规则：

| 规则 | 示例 |
| --- | --- |
| 中文按二字组切分，单字连续段保留单字 | 年假天数 → 年假 / 假天 / 天数 |
| 英文、数字按整词保留并转小写；带下划线的再拆出各段 | Q5 → q5；RW_202607050001 → rw_202607050001 / rw / 202607050001 |
| 条款编号统一成阿拉伯数字，编号本身不再切二字组 | 第一千零七十九条、第1079条、1079条 → 第1079条 |
| 全角字符先转半角（NFKC） | 第８２条 → 第82条 |
| 仅查询：先去掉疑问词 | 「是什么」「怎么」「哪些」「多少」「吗」等 |

条款号不切二字组，是因为「三十」「九条」会命中所有「第三十X条」，反而把要找的那条挤下去。疑问词要去掉，是因为它们在制度、法规正文里很少出现，IDF 反而很高。

**分词**（`KeywordAnalyzer.analyze`，略去英文数字部分）：先抽出条款编号，再对剩下的连续汉字切二字组。

```java
String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);

// ① 条款编号整体抽出，统一成"第N条"，并从原文中挖掉，后面不再对它切二字组
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

// ② 连续汉字按二字组滑动切分，只有一个字的单独保留
Matcher han = HAN_RUN.matcher(remaining);
while (han.find()) {
    String run = han.group();
    if (run.length() == 1) terms.add(run);
    for (int i = 0; i + 2 <= run.length(); i++) terms.add(run.substring(i, i + 2));
}

// ③ 英文、数字按 [a-z0-9_]+ 整词保留，带下划线的再拆段，长度不足 2 的丢弃
```

查询时先去掉疑问词，再用同一个 `analyze` 切分，并对结果去重：

```java
public static List<String> queryTerms(String query) {
    String stripped = query == null ? null : QUESTION_WORDS.matcher(query).replaceAll(" ");
    return List.copyOf(new LinkedHashSet<>(analyze(stripped)));
}
```

**索引**：V4 迁移给 `chunk` 加了 `keyword_tsv`（GIN 索引）和 `keyword_token_count`（词项总数，即 BM25 的文档长度）两列。词项来自「章节路径 + 正文」，章节名里的「证件办理」「车辆管理」往往就是用户的检索词。新片段在 `ChunkRepository.insertAll` 写入时生成；V4 之前入库的片段由 `KeywordIndexBackfill` 在应用启动后于后台补齐，只需分词，不用重新向量化。

```java
// ChunkRepository.insertAll（略去其他列）
List<String> terms = KeywordAnalyzer.analyze(headingPath == null ? content : headingPath + "\n" + content);
args.add(new Object[]{..., KeywordAnalyzer.toTsvector(terms), terms.size()});
jdbcTemplate.batchUpdate("""
        INSERT INTO chunk (..., keyword_tsv, keyword_token_count)
        VALUES (..., ?::tsvector, ?)
        """, args);
```

`toTsvector` 把词项拼成 tsvector 字面量，比如 `'q5':1 '年假':2,7`，冒号后面是该词项出现的位置，位置个数就是 BM25 里的词频 tf。这里直接把字面量转换成 `tsvector`，而不是调用 `to_tsvector()`：后者会用 PG 自己的解析器重新分词，而它不认识中文。

**打分**：标准 BM25，`k1 = 1.2`、`b = 0.75`。文档数 N、每个词的文档频率 df、平均文档长度都按当前知识库实时统计；词频 tf 取 `tsvector` 中该词项的位置个数：

```text
idf(t)  = ln(1 + (N - df + 0.5) / (df + 0.5))
score   = Σ idf(t) · tf · (k1 + 1) / (tf + k1 · (1 - b + b · 文档长度 / 平均文档长度))
```

```java
// Retriever.keywordSearch：:terms 是空格连接的查询词项，:tsquery 是 'q5' | '系统' | ...
jdbc.sql("""
        WITH stats AS (                                    -- 当前知识库的片段数 N、平均文档长度
            SELECT count(*) AS n, greatest(avg(keyword_token_count), 1) AS avgdl
            FROM chunk WHERE kb_id = :kbId
        ), idf AS (                                        -- 每个查询词项的 df 和 idf，库里没有的词项直接丢掉
            SELECT q.term, ln(1 + (s.n - d.df + 0.5) / (d.df + 0.5)) AS idf
            FROM unnest(string_to_array(:terms, ' ')) AS q(term)
            CROSS JOIN stats s
            CROSS JOIN LATERAL (
                SELECT count(*) AS df FROM chunk c
                WHERE c.kb_id = :kbId AND c.keyword_tsv @@ CAST(quote_literal(q.term) AS tsquery)
            ) d
            WHERE d.df > 0
        ), tf AS (                                         -- 候选片段中每个命中词项的 tf
            SELECT c.id, c.keyword_token_count AS dl, u.lexeme, coalesce(cardinality(u.positions), 1) AS tf
            FROM chunk c CROSS JOIN LATERAL unnest(c.keyword_tsv) AS u
            WHERE c.kb_id = :kbId AND c.keyword_tsv @@ CAST(:tsquery AS tsquery)
              AND u.lexeme = ANY (string_to_array(:terms, ' '))
        )
        SELECT tf.id AS chunk_id,
               sum(i.idf * tf.tf * (:k1 + 1) / (tf.tf + :k1 * (1 - :b + :b * tf.dl / s.avgdl))) AS score
        FROM tf JOIN idf i ON i.term = tf.lexeme CROSS JOIN stats s
        GROUP BY tf.id
        ORDER BY score DESC
        LIMIT :limit
        """)...;
```

- **stats**：N 和平均长度都按 `kb_id` 实时统计，不同知识库的 idf 互不影响，新增文档后也不需要重建统计。
- **idf**：每个词项单独查一次 df（`@@` 单个词项，走 GIN 索引）。df = 0 的词项在库里不存在，不参与打分。
- **tf**：`unnest(tsvector)` 把片段的 tsvector 展开成 `(lexeme, positions)` 行，只保留查询中出现的词项，`positions` 的个数就是 tf。
- **最终得分**：按片段把各词项的 BM25 贡献相加。只返回 `chunk_id` 和分数，片段详情在融合后由 `loadChunks` 统一查询。

SQL 先用 `keyword_tsv @@ 'q5' | '系统' | ...`（任一词项命中，走 GIN 索引）筛出候选，再 `unnest(keyword_tsv)` 取出命中词项的 tf 求和。在当前几百个片段的库上单次查询 15～100 ms。它要对每个候选展开全部词项，片段数到几万时需要关注耗时。

### 融合排序

> 含问题中编号的片段必进候选（`Retriever.exactMatchIds`，最多 10 个）。精确命中优先只能给已经召回的片段排序：「民法典第十七条是什么」改写后检索文本多了「中华人民共和国」「条文内容」，民法典第十七条被挤出关键词召回的前 30，向量召回本来就没有它，两路都没召回，答成了「没有找到」。所以问题里有条款号、型号这类精确词项时，另查一次含它的片段，直接补进融合候选。同一编号在别的文档里被引用（劳动合同法里的「本法第十七条」）时也会补进来，由精排和模型区分。

```java
private List<RetrievedChunk> fuse(long kbId, String query, Mode mode, int poolSize) {
    String vector = PgVectors.toLiteral(embeddingModel.embed(query));
    List<String> terms = KeywordAnalyzer.queryTerms(query);
    return readOnlyTx.execute(status -> {
        List<Hit> vectorHits = mode == Mode.KEYWORD ? List.of() : vectorSearch(kbId, vector, poolSize);
        List<Hit> keywordHits = mode == Mode.VECTOR || terms.isEmpty() ? List.of() : keywordSearch(kbId, terms, poolSize);

        // 两路结果只取排名（从 1 开始），按加权 RRF 累加
        Map<Long, Integer> vectorRanks = ranks(vectorHits);
        Map<Long, Integer> keywordRanks = ranks(keywordHits);
        Map<Long, Double> fused = new HashMap<>();
        vectorRanks.forEach((id, rank) -> fused.merge(id, 1.0 / (rrfK + rank), Double::sum));
        keywordRanks.forEach((id, rank) -> fused.merge(id, keywordWeight / (rrfK + rank), Double::sum));
        if (fused.isEmpty()) {
            return List.of();
        }

        Set<String> exactTerms = mode == Mode.VECTOR ? Set.of() : KeywordAnalyzer.exactTerms(terms);
        Map<Long, ChunkRow> rows = loadChunks(fused.keySet(), vector, exactTerms);
        // 问题里写明了"第39条""Q5"这类编号时，包含它的片段几乎一定是用户要找的，直接排到最前
        return rows.values().stream()
                .sorted(Comparator.comparing(ChunkRow::exactMatch).reversed()
                        .thenComparing(Comparator.comparingDouble((ChunkRow r) -> fused.get(r.chunkId())).reversed())
                        .thenComparing(Comparator.comparingDouble(ChunkRow::score).reversed()))
                .map(r -> new RetrievedChunk(..., null /* rerankScore */, r.exactMatch(),
                        r.score() >= minScore || r.exactMatch()))   // 未精排时的 relevant
                .toList();
    });
}
```

`loadChunks` 用一条 SQL 取出所有候选的详情，同时补算余弦相似度，并用 tsquery 判断是否包含问题中的精确词项：

```sql
SELECT c.id AS chunk_id, c.document_id, d.filename, c.heading_path, c.location, c.content,
       1 - (c.embedding <=> CAST(:vector AS vector)) AS score,
       coalesce(c.keyword_tsv @@ CAST(:exact AS tsquery), false) AS exact_match   -- 问题中没有精确词项时为 false
FROM chunk c JOIN document d ON d.id = c.document_id
WHERE c.id IN (:ids)
```

- **精确命中优先**：问题中的「精确词项」指条款编号、字母数字混合的编号（q5、rw_202607050001）和 5 位以上的数字（商品编码等）。片段包含其中任意一个时 `exactMatch = true`，排在所有片段前面。
- **加权 RRF**：其余片段的得分为 `1 / (rrf-k + 向量排名) + keyword-weight / (rrf-k + 关键词排名)`，只出现在一路时只算该路。RRF 只看排名、不看分数，余弦相似度和 BM25 分数的量纲不同也能直接融合。
- **参数**：`rrf-k = 10`、`keyword-weight = 0.25`，是在四份题集上网格搜索得到的。常见的 `k = 60` 等权融合会让 laws 题集的 Hit@1 从 100% 掉到 56%。k 越大，各名次之间的分差越小：k = 60 时向量第 1 名和第 9 名只差 0.002，关键词这一路随便贡献一点就能把顺序翻过来。而二字组 BM25 对改写、口语化的问题噪声很大，例如「租房合同最长能签多少年」会命中一堆含「合同」的劳动合同法条文。
- **结果中的分数**：`score` 始终是与问题的余弦相似度，只从关键词召回的片段也会补算。另有 `keywordScore`（BM25）、`vectorRank`、`keywordRank`、`rerankScore`（精排分数，未精排时为 null）、`exactMatch` 字段用于排查，`relevant` 表示是否通过召回过滤。

不同方式在四份题集上的 Hit@1 / 问答可用率（`python3 scripts/eval_retrieval.py --mode vector|hybrid ...`）：

| 题集 | 内容 | 纯向量 | 混合检索 |
| --- | --- | --- | --- |
| `laws.csv` | 法律法规，改写 / 口语化问法 | 100% / 100% | 100% / 100% |
| `laws_article.csv` | 按条款号提问 | 7.1% / 35.7% | 92.9% / 100% |
| `handbook.csv` | 员工手册（双栏 PDF） | 78.9% / 94.7% | 89.5% / 100% |
| `requirements.csv` | 需求文档中的编码、ID、字段规则 | 80.0% / 85.0% | 95.0% / 100% |

无答案题的拒答率不变（laws 100%，handbook 0%，requirements 33.3%）。混合检索解决的是「找得到、排得准」，判断「库里到底有没有答案」交给下面的精排。

### 精排（Rerank）

**为什么要精排**。向量检索是双编码器：问题和片段各自压缩成一个向量再比较余弦，只能反映「话题像不像」。「迟到一次扣多少钱」会以 0.66 的高分命中「考勤处罚」章节，但那一节只写了「依据《员工考勤管理规定》执行」，并没有金额；「转正后辞职要提前多久」和试用期「提前三日」的片段在向量上也几乎分不开。新增的难题集（`handbook_hard.csv`、`laws_hard.csv`）上，正确片段的余弦最低 0.53，无答案题的最高分却有 0.66，阈值怎么调都分不开。

交叉编码器（bge-reranker-v2-m3）把「问题 + 片段」拼成一条输入，让模型逐字对照后直接输出相关性。它比双编码器准得多，但每对都要实时推理，没法对全库做，所以只对 RRF 的前 20 个片段精排：两路召回负责「别漏掉」，精排负责「排准、判断有没有答案」。

**去掉重复结果**（`Retriever.dropDuplicates`）。精排之后按排名依次保留，与已保留片段的字符三元组相似度（交集占较短一方的比例）≥ 0.85 的跳过。真实资料库里同一本书常有多个副本（两本完全相同的《Java 并发编程实战》、《红楼梦》和它的副本、同一译本的 Effective Java 两个扫描版），「林黛玉第一次进贾府」的前 6 个结果里有 3 对完全相同，参考资料名额被浪费一半。同一页的相邻片段、格式相近的表格行相似度在 0.5～0.7，不受影响。

**关键词召回前 3 个正文片段必进候选**（`Retriever.rerankPool`）。关键词一路在 RRF 中的权重是 0.25，只被它召回的片段，即使排关键词第 1 名也只得 0.25/11 ≈ 0.023，比向量第 30 名（1/40 = 0.025）还低，永远进不了前 20。真实资料库里的「简历里的求职目标是什么？」：简历片段的关键词排第 2，余弦只有 0.545，在 5 万片段里排不进向量前 30，于是从来没被精排看到。现在精排候选先放关键词召回中排名最前的 3 个正文片段，再按 RRF 顺序补足 20 个，候选总数不变，精排耗时也不变。文档摘要片段不占这 3 个名额：它的关键词里有文件名和全书关键词，加入摘要后「简历里的求职目标」中简历摘要排关键词第 1，把写着「求职目标」的正文片段挤出了必进名额；摘要仍可以按 RRF 顺序进入候选。

**服务**。`scripts/rerank_server.py` 用 sentence-transformers 加载模型，在 Apple Silicon 上走 MPS、fp16 推理（与 fp32 分数相差不到 0.01），默认端口 8081。接口与 HuggingFace text-embeddings-inference 的 `/rerank` 相同，以后可以直接换成 TEI：

```text
POST /rerank   {"query": "年假有几天", "texts": ["片段1", "片段2"]}
→              [{"index": 1, "score": 0.93}, {"index": 0, "score": 0.02}]
```

核心是 `CrossEncoder.predict`：把问题和每个片段配成一对，逐对打分，经 sigmoid 转成 0～1。

```python
# scripts/rerank_server.py：Reranker.rerank
def rerank(self, query: str, texts: list[str]) -> list[dict]:
    if not texts:
        return []
    with self.lock:
        scores = self.model.predict([(query, t) for t in texts], batch_size=self.batch_size,
                                    activation_fn=torch.nn.Sigmoid(), show_progress_bar=False)
    ranked = sorted(range(len(texts)), key=lambda i: scores[i], reverse=True)
    return [{"index": i, "score": float(scores[i])} for i in ranked]
```

`score` 经过 sigmoid，范围 0～1。服务按 HTTP/1.1 保持连接，推理串行执行（GPU 上并发没有收益）。它不在 docker compose 里，因为 Mac 上的 Docker 用不了 MPS；安装、启动、检查和停止的方法见 README 的「Rerank 服务（Python）」一节。

**后端调用**（`RerankClient`、`Retriever.rerank`）：

```java
private List<RetrievedChunk> rank(long kbId, String query, Mode mode, int poolSize, boolean rerank) {
    List<RetrievedChunk> fused = fuse(kbId, query, mode, poolSize);
    // 精排是一次 HTTP 调用，放在数据库事务之外，避免长时间占用连接
    return rerank && !fused.isEmpty() ? rerank(query, fused) : fused;
}

private List<RetrievedChunk> rerank(String query, List<RetrievedChunk> fused) {
    List<RetrievedChunk> head = fused.subList(0, Math.min(rerankCandidates, fused.size()));
    double[] scores;
    try {
        scores = rerankClient.score(query, head.stream().map(Retriever::rerankText).toList());
    } catch (RuntimeException e) {
        log.warn("精排失败，退回 RRF 排序：{}", e.getMessage());
        return fused;   // relevant 仍是 fuse() 里按余弦阈值算出的值
    }
    List<RetrievedChunk> reranked = new ArrayList<>(fused.size());
    for (int i = 0; i < head.size(); i++) {
        RetrievedChunk c = head.get(i);
        // 相关性改用精排分数判断；精确命中的始终相关
        reranked.add(withRerank(c, scores[i], scores[i] >= rerankMinScore || c.exactMatch()));
    }
    // 精确命中仍排最前，其余按精排分数降序
    reranked.sort(Comparator.comparing(RetrievedChunk::exactMatch).reversed()
            .thenComparing(Comparator.comparingDouble(RetrievedChunk::rerankScore).reversed()));
    // 精排范围之外的片段保持 RRF 顺序接在后面，只有精确命中的仍视为相关
    for (RetrievedChunk c : fused.subList(head.size(), fused.size())) {
        reranked.add(withRerank(c, null, c.exactMatch()));
    }
    return reranked;
}

/** 章节路径也交给精排模型，"考勤处罚""试用期终止"这类标题本身就是重要的判断依据。 */
private static String rerankText(RetrievedChunk c) {
    return c.headingPath() == null ? c.content() : c.headingPath() + "\n" + c.content();
}
```

`RerankClient.score` 发送请求，并按 `index` 把分数放回片段原来的位置（服务返回时已按分数排序）：

```java
public double[] score(String query, List<String> texts) {
    RerankResult[] results = restClient.post()
            .uri("/rerank")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new RerankRequest(query, texts))
            .retrieve()
            .body(RerankResult[].class);
    if (results == null || results.length != texts.size()) {
        throw new IllegalStateException("Rerank 服务返回的结果数量与片段数不一致");
    }
    double[] scores = new double[texts.size()];
    for (RerankResult r : results) {
        scores[r.index()] = r.score();
    }
    return scores;
}
```

- **送给模型的文本**：「章节路径 + 换行 + 正文」。「考勤处罚」「试用期终止」这类标题本身就是重要的判断依据。
- **降级**：服务没启动、超时（`rerank.timeout`，15 秒）或返回异常时记一条 WARN，退回 RRF 顺序和余弦阈值，问答照常进行。`rerank.enabled: false` 可以整体关闭。
- **事务之外**：两路召回和取详情仍在只读事务里完成；精排在事务提交后调用，不会在等待模型时占着数据库连接。
- **HTTP 细节**：`RerankClient` 基于 JDK HttpClient，固定使用 HTTP/1.1（Python 的 `http.server` 不支持 HTTP/2），连接超时 2 秒，服务没启动时能很快失败并降级。它还用 `BufferingClientHttpRequestFactory` 缓冲请求体以带上 `Content-Length`：JDK HttpClient 默认按分块传输发送，Python 的 `http.server` 读不到分块的请求体。
- **候选数取 20**：延迟与片段数成正比。评测中关闭精排、取前 30 时，所有正确片段在 RRF 中都排在前 13，20 留有余量。30 个时单次精排 3～4 秒，P90 超过 10 秒；20 个时检索总耗时中位约 1.9 秒、P90 约 2.1 秒（M2 Pro，含问题向量化和两路召回）。

**效果**（六份题集，118 道有答案题、19 道无答案题；`--rerank off` 为精排前）：

| 题集 | Hit@1 | Hit@6 | 问答可用率 | 无答案题拒答 |
| --- | --- | --- | --- | --- |
| `laws.csv` | 100% → 93.8% | 100% | 100% | 4/4 → 4/4 |
| `laws_article.csv` | 92.9% → 100% | 100% | 100% | — |
| `laws_hard.csv` | 65.0% → 55.0% | 95.0% → 100% | 90.0% → 95.0% | 2/4 → 4/4 |
| `handbook.csv` | 89.5% → 94.7% | 100% | 100% | 0/3 → 1/3 |
| `handbook_hard.csv` | 65.5% → 72.4% | 93.1% → 100% | 93.1% → 100% | 0/5 → 2/5 |
| `requirements.csv` | 95.0% → 95.0% | 100% | 100% | 1/3 → 3/3 |
| **合计** | | | **114 → 117** | **7 → 14** |

- **拒答**是最大的收益：「公司公积金按工资的多少比例缴纳」「申请工伤认定需要准备哪些材料」「开具发票需要填写哪些信息」这类库里有相近内容、余弦相似度在 0.5 以上的问题，精排分数都在 0.005 以下，被直接拦下。「迟到一次扣多少钱」的最高分从余弦 0.66 降到精排 0.24，排序更合理，但仍高于阈值。
- **列举类问题**：「哪些行为会被公司直接解除劳动合同」的两条证据在 RRF 中排第 12、13，精排后升到第 4，进入问答使用的前 6。
- **法条类 Hit@1 下降**：法律法规的片段往往一块包含好几个条文，模型对整块打分，正确条文被其他条文稀释，分数普遍只有 0.01～0.3，与相邻的相关片段拉不开差距。laws_hard 有 4 道题从第 1 名掉到第 2 名，但都还在前 6 名以内。之后切分改成按组切（条文不切开、片段不以上一条的半截内容开头），laws_hard 的 Hit@1 回升到 70.0%（14/20），见「切分」一节的效果。
- 选型时也试过 bge-reranker-base（约 1.1GB）：员工手册类有提升，法律类反而变差，它对「考勤处罚」这个难负例打了 0.86 分（v2-m3 为 0.24），区分不了「有相关章节但没有答案」。

### 召回过滤

`retrieve()` 只保留 `relevant` 的片段，再取前 `top-k` 个。向量检索永远会返回「最近的 k 个」，哪怕它们和问题毫不相关；阈值让「知识库里没有的内容」可以得到明确的「没找到」，而不是拿无关片段勉强作答。`relevant` 在排序时就已算好：

| 情况 | 判定 |
| --- | --- |
| 精确命中问题中的编号 | 始终相关 |
| 已精排 | `rerankScore >= rerank.min-score`（0.05） |
| 未精排（服务不可用或已关闭） | `score >= retrieval.min-score`（0.5，余弦相似度） |

**精排阈值 0.05** 是在六份题集上离线扫描得到的，经历了两轮。第一轮在没有查询改写时进行。bge-reranker-v2-m3 经 sigmoid 后分数分布很偏，无关片段通常只有 0.001 左右：

| `rerank.min-score` | 问答可用（118） | 无答案题拒答（19） |
| --- | --- | --- |
| 精排前（余弦 ≥ 0.5） | 114 | 7 |
| 0.005 | 117 | 11 |
| **0.01** | **117** | **14** |
| 0.02 | 115 | 15 |
| 0.03 | 114 | 16 |
| 0.1 | 104 | 17 |

0.01 是唯一在两项上都优于精排前的取值。再往上提，误伤的是「捡到别人的钱包必须还吗」「10岁的孩子自己买游戏装备算数吗」这类口语化问题：正确片段的精排分数只有 0.002～0.02。宁可把无关片段交给 LLM，让它按提示词回答「资料中没有」，也不要把有答案的题直接拒掉。

第二轮在加入查询改写之后。精排看到的是「原问题 + 术语改写」，口语化问题的正确片段分数大幅抬高（各题集最低分从 0.016 左右升到 0.08 以上），阈值可以相应提高：

| `rerank.min-score`（改写后） | 问答可用（118） | 无答案题拒答（19） |
| --- | --- | --- |
| 0.01 | 118 | 9 |
| 0.02 | 118 | 12 |
| **0.03 ～ 0.1** | **118** | **13** |
| 0.12 | 117 | 13 |
| 0.2 | 117 | 14 |
| 0.3 | 115 | 15 |

0.03～0.1 是一段平台，取中间的 0.05。改写也会抬高无答案题的分数，0.01 时拒答只剩 9 道，所以阈值必须跟着改写一起调整。关闭改写（`/search` 传 `rewrite=false`）时这个阈值偏高，口语化问题会被误伤。

**余弦阈值 0.5** 只在未精排时使用。它最初取 bge-m3 的经验值 0.45，用 `scripts/eval/laws.csv` 评测后调整为 0.5：正确片段最低分 0.557，无答案题最高分 0.455（0.45 时会被放行）。

精确命中不受阈值限制，是因为编号类问题的分数往往偏低。例如「Q5 系统是用来做什么的」，正确片段的余弦相似度只有 0.426，「商品编码 2025999500042779 对应哪个产品」只有 0.462。

```java
public List<RetrievedChunk> retrieve(long kbId, String query) {
    return rank(kbId, query, Mode.HYBRID, candidates, rerankEnabled).stream()
            .filter(RetrievedChunk::relevant)
            .limit(defaultTopK)
            .toList();
}
```

**调试接口**：`POST /api/knowledge-bases/{kbId}/search`，请求体 `{"query": "...", "topK": 10, "mode": "hybrid", "rerank": true}`。topK 限制在 1～50；`mode` 可选 `hybrid`（默认）、`vector`、`keyword`，用来对比单路效果，单路模式不精排；`rerank` 不传时按配置。它直接调用 `search()`，不做相关性过滤，也不调用 LLM，用来观察排名和分数分布、调整参数。前端「检索测试」会显示每条结果的向量 / 关键词排名、精排分数、「精确命中」和「不相关」标记。评测脚本直接读取结果里的 `relevant`，与问答时的过滤逻辑保持一致；`--rerank off` 用来对比精排前的效果。

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

**小文档读全文**（`Retriever.context`，`skb.chat.full-text-chars`，40000）。只给命中的几段时，模型只能就这几段作答，答案常常恰好在没给的那段里；而合同、手册、证件这类文档只有几千到几万字，整篇交给 DeepSeek 完全放得下，读完全文才能回答「押金和违约金一共多少」「哪些条款对承租人不利」这类要综合多处的问题。所以组装上下文时，按命中片段的排名依次看它们所属的文档，全文字数累计不超过 4 万字的整篇读入：按原文顺序、章节变化或满 2500 字分段，每段一条参考资料（模型照样按 `[n]` 标注出处），提示词注明「以上参考资料包含《X》的全文，可以通读后综合判断」。几十万字的书（库里约 50 本）放不下，仍是命中片段加相邻片段。全文预算单独计算，不占 `max-context-chars`；读了全文的文档记在 `retrieval_trace.fullTextDocuments`，回答下方显示「已读全文」。同时问答时取的片段从 6 个增加到 10 个（`skb.retrieval.top-k`），`max-context-chars` 从 12000 调到 18000：「西门庆第一个女人是谁」的答案（「先头浑家陈氏」）排在第 7。

**上下文扩展**（`Retriever.expand`）。片段按 600 字左右切分，答案常常跨片段：列举项的后半段、跨页的段落、「有下列情形之一的」之后的条目。组装参考资料前，把每个命中片段在同一文档、同一章节（`heading_path` 相同）里的后一个、前一个片段接上，每条最多 `skb.chat.expand-chars`（1500）字，放不下就不接。相邻片段本身也是命中结果时并成一条，不重复占用参考资料名额，位置合并成「第3-5页」。`retrieval_trace` 仍按原始命中记录。设为 0 关闭。

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
        2. 不要用自己的知识补充资料之外的内容：资料没有写明的数字、期限、计算标准、法律后果、编号或术语的含义，不要推断或解释；
           确实需要提及时，写明"资料中没有说明"。
        3. 要基于资料做合理的理解和推理：问题的说法和资料不同（口语、近义词、古籍的旧式说法，如"先头浑家"就是原配妻子）时，
           按问题的本意在资料中找答案；资料能推出答案时，直接给出结论并说明依据，不要因为资料没有逐字写出问题里的说法就说没有找到。
           口语、俗语、比喻的问法按本意回答，不要按字面纠正用户（如"死在谁的床上"是问因哪个女人而死，不是问断气时在哪张床）。
           只有资料中确实没有相关内容时才说没有找到。
        4. 在使用了资料的句子末尾标注来源编号，格式为 [1]、[2]；一句话来自多个来源时写成 [1][3]。不要在末尾单独列出参考文献。
        5. 用中文回答，先给出结论，再展开说明；内容较多时使用 Markdown 列表或表格。
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

**答案入库**。流正常结束后，把累积的完整答案、引用列表（`citations` JSONB）和检索轨迹（`retrieval_trace` JSONB）一起写入 `message` 表，然后发送 `done`。检索轨迹记录了实际用于检索的问题，以及通过召回过滤的每个片段的余弦相似度和精排分数（未精排时没有 `rerankScore`），格式如下（数值为示例）：

```json
{"searchQuery": "病假有几天？", "hits": [{"chunkId": 812, "score": 0.71, "rerankScore": 0.93}, {"chunkId": 640, "score": 0.58, "rerankScore": 0.12}]}
```

用户中途点「停止」会触发流的 `doOnCancel`：已生成的部分加上「（已停止生成）」后照样入库，刷新页面后不会丢失。

规则 3 是规则 2 的反面：只禁止「资料之外的事实」，不禁止「对资料的理解」。只有规则 2 时，模型变得过于字面：「西门庆第一个女人是谁」的参考资料里明明有「这西门大官人先头浑家陈氏早逝」，回答却先说「知识库中没有找到明确说明」，因为资料没有逐字写「第一个女人」。

### 回答核对

规则 2 是针对答案级评测中出现过的三个无依据回答加的：「违法解除的赔偿金是经济补偿的二倍」「RW_ 是任务的拼音首字母」「第三十九条解除无需支付经济补偿」，都是模型用自己的知识补充了资料没写的推断。提示词约束不是绝对的，所以回答保存后还会再核对一次（`AnswerVerifier`，`skb.chat.verify-answers`）：

1. 把参考资料（带编号）和回答交给 DeepSeek（temperature 0），找出两类问题，输出 JSON：资料无法支持的事实性内容（`unsupported`），以及标注了 `[n]` 但第 n 条资料并不支持的句子（`citationErrors`）。判定标准与答案级评测的裁判一致：对资料的复述、基于资料的正确推理、「资料中没有提到」之类的说明、不含具体事实的建议都不算。
2. 结果写入 `message.verification`，并作为 SSE 事件 `verification` 推给前端，排在 `done` 之后。回答下方显示「已核对：回答内容都能在参考资料中找到依据」，或列出需要确认的句子和原因；翻看历史对话时也能看到。
3. 核对在回答显示完之后进行（约 3～5 秒），前端收到 `done` 就允许继续提问，不影响阅读。无命中直接拒答、用户停止生成、核对失败时不核对，也不发送事件。

它不会改写回答，只是提示用户哪些话要再确认；代价是每个问题多一次 DeepSeek 调用。

### 反馈与问答分析

评测题集是手工出的，在六份题集上已经接近满分（答对 108/109），但真实资料库里约三分之一的回答是「没找到」：有的是检索漏召回，有的是库里本来就没有，有的是问题类型不对（写代码、库外知识）。分不清这三类，就不知道下一步该修哪里，所以加了一个闭环：

1. **回答评价**：回答下方「有用 / 没用」，没用时选原因（没找到、答错了、不完整、引用不对、其他）并补充说明，存在 `message.feedback*`。
2. **问答分析页**（`InsightService`）：取最近一段时间的回答，从已有的检索记录、核对结果和评价判定每条回答的问题类型，不需要额外埋点：差评、拒答（没有参考资料，或回答含「知识库中没有找到」）、核对发现问题、精排未生效（命中片段都没有精排分数，说明精排服务没启动或超时）、慢（改写 + 检索 + 首字超过 15 秒）。页面给出各类数量、拒答率和各环节耗时的中位数 / P90，按天汇总，并列出回答明细：检索查询、检索范围、参考资料及其相似度和精排分数、核对结果、用户评价。
3. **加入评测题**：确认是检索问题的，填上预期文件和原文存进 `eval_case`；导出地址 `/api/knowledge-bases/{id}/eval-cases.csv` 与 `scripts/eval/` 下的题集格式相同，`eval_retrieval.py --csv` 可以直接填这个地址，以后每次改动都回归这些真实问题。

### 答案级评测

检索评测只看正确片段有没有交给 LLM，`scripts/eval_answer.py` 再往后看一步：逐题真实调用 `/chat`（每题新建会话，问完删除），把回答连同参考资料和标准答案要点交给 DeepSeek 当裁判，检查四项：

| 检查项 | 判定 |
| --- | --- |
| 要点覆盖 | 意思一致即可，但数字、期限、条件必须一致；要点全部覆盖且没有无依据内容才算「答对」 |
| 无依据内容 | 参考资料不支持的事实，包括模型用自己的知识补充的内容；基于资料的正确推理、计算不算 |
| 引用错误 | `[n]` 编号不存在，或第 n 条资料不支持该句 |
| 拒答 | 明确表示资料中没有答案；无答案题要拒答且不含无依据内容才算通过 |

答案要点在 `scripts/eval/answers/`，问题与检索题集一一对应，每题要点都对照库中原文撰写。需要推算的题写推算后的结论，例如「满 5 年年假」写 9 天、「签一年期合同的试用期」写二个月（一年属于「一年以上不满三年」）。后者在检索题集里原本把预期原文写成了「一个月」，因为两句在同一个片段里，检索评测一直没发现，这类错误只有看最终答案才能暴露。

**裁判是否可信**：把一份正确回答分别改错数字、追加一句带 `[1]` 的编造、把 `[1]` 改成不存在的 `[9]`、去掉全部引用，裁判都能识别。下面基线里裁判标出的每个问题都经过人工核对。

**基线**（`scripts/eval/baseline/answers/`，精排和查询改写开启）：

| 题集 | 答对 | 要点覆盖 | 无答案题正确拒答 | 含无依据内容 | 引用错误 |
| --- | --- | --- | --- | --- | --- |
| `laws.csv` | 16/16 | 19/19 | 4/4 | 0/16 | 0 |
| `laws_article.csv` | 13/14 | 41/41 | — | 1/14 | 0 |
| `laws_hard.csv` | 17/18 | 29/29 | 4/4 | 1/18 | 0 |
| `handbook.csv` | 19/19 | 23/23 | 3/3 | 0/22 | 0 |
| `handbook_hard.csv` | 21/22 | 33/35 | 5/5 | 0/26 | 0 |
| `requirements.csv` | 19/20 | 22/22 | 3/3 | 1/20 | 0 |
| **合计** | **105/109** | **167/169** | **19/19** | **3/116** | **0/116** |

有答案题按问题计数（109 道），检索评测按证据行计数（118 行），列举题在这里只算一道。跑基线时 DeepSeek 两次过载（503），`laws.csv` 和 `laws_hard.csv` 各有一题请求失败，这两题单独补跑后合入了基线，报告里有标注。

- **无答案题全部拒答**：12 道在检索阶段被拦下；另外 7 道（「公司班车几点发车」「迟到一次扣多少钱」等难负例）精排没拦住，LLM 看过资料后按提示词说明「没有找到」，没有编造。精排和提示词两道防线合起来是有效的。
- **口语化问题（已修复）**：加入查询改写之前，「10岁的孩子自己买游戏装备算数吗」的正确片段精排分数低于 0.01，检索阶段就被拦下，LLM 拒答。改写后精排分数为 0.95，这道题答对。
- **多证据列举题（改写后退步）**：「哪些行为会被公司直接解除劳动合同」的答案分散在四个片段里，改写后有一个片段掉出前 6，回答漏了「连续旷工 3 天」「未出勤却填写忘记打卡申报」两点，只算部分答对。
- **列举项与题干分离（已修复）**：首次基线中「劳动合同法第十四条是什么内容」只答对一部分，因为条文列举的三种情形被切到了下一个片段。切分改为按组切之后，题干和列举项在同一个片段里，这道题答对。
- **无依据内容 3 道**：「劳动合同法第三十九条」答出了「无需提前通知、无需支付经济补偿」；「只干了 4 个月就被辞退」答出了「违法解除按经济补偿的二倍支付赔偿金」；「RW_202607050001 是什么编号」把前缀 RW 解释成「任务」的拼音首字母。前两道法律上没错，但不在交给模型的资料里，第三道是模型的猜测。首次基线中还出现过「无民事行为能力人通常指不满 8 周岁」「孕期员工严重违纪仍可按第三十九条解除」，生成有随机性，这类补充时有时无。法律问答问题不大，企业内部制度类知识库遇到这种情况就是隐患。
- **引用**：116 个回答没有发现引用错误或缺少引用。

结论：检索和生成整体可用，剩下的问题集中在多证据列举题的召回和模型用自身知识补充。生成和改写都有随机性，单题结果在多次运行之间可能变化，比较改动前后要看整体趋势，并抽查未通过的题。

## 关键参数与已知限制

下表参数都在 `src/main/resources/application.yaml` 的 `skb.*` 下，绑定到 `common/SkbProperties`，改完重启生效。切分参数只影响之后处理的文档，已有文档需要「重新处理」。

| 参数 | 默认值 | 作用环节 | 说明 |
| --- | --- | --- | --- |
| `ingestion.concurrency` | 2 | 异步调度 | 同时处理的文档数 |
| `ingestion.embedding-batch-size` | 16 | 向量化 | 每次调用 embedding 的片段数 |
| `chunk.target-chars` | 600 | 切分 | 片段目标字数 |
| `chunk.max-chars` | 1000 | 切分 | 片段字数上限；超过它的段落按句拆分 |
| `chunk.overlap-chars` | 100 | 切分 | 因长度切分时的句子级重叠 |
| `retrieval.top-k` | 10 | 检索 | 问答时召回的片段数（原为 6，答案常排在第 7～10） |
| `retrieval.min-score` | 0.5 | 召回过滤 | 未精排时的余弦相似度阈值；精确命中编号的片段不受限制 |
| `retrieval.candidates` | 30 | 混合检索 | 向量、关键词两路各召回的候选数 |
| `retrieval.rrf-k` | 10 | 融合排序 | RRF 常数，越小名次之间的分差越大 |
| `retrieval.keyword-weight` | 0.25 | 融合排序 | 关键词一路在 RRF 中的权重（向量为 1） |
| `retrieval.rewrite-timeout` | 10s | 查询改写 | 改写的最长等待时间，超时后用原问题检索 |
| `ocr.enabled` / `ocr.command` | true / `scripts/ocr/vision-ocr` | 解析 | 扫描页文字识别；命令不存在时跳过 |
| `ocr.dpi` | 200 | 解析 | 页面渲染分辨率，越高越慢 |
| `ocr.min-page-chars` | 20 | 解析 | 文字层少于这么多字的页视为扫描页 |
| `summary.enabled` | true | 文档摘要 | 是否用 DeepSeek 生成文档摘要；需要 Key，已有文档启动时后台补生成 |
| `summary.input-chars` | 6000 | 文档摘要 | 交给 LLM 的目录、开头和正文抽样的总字数 |
| `retrieval.ef-search` | 200 | 向量召回 | HNSW 搜索范围，越大越接近精确结果；要明显大于 `candidates`。索引本身用 `m = 32, ef_construction = 128` 建（迁移 V8） |
| `rerank.enabled` | true | 精排 | 是否精排；服务不可用时自动降级，无需改配置 |
| `rerank.base-url` | `http://localhost:8081` | 精排 | Rerank 服务地址 |
| `rerank.candidates` | 20 | 精排 | 取 RRF 前多少个片段精排，延迟与它成正比 |
| `rerank.min-score` | 0.05 | 召回过滤 | 精排分数阈值，精排时取代 `retrieval.min-score`；按查询改写后的分数分布校准 |
| `rerank.timeout` | 15s | 精排 | 单次精排请求超时，超时后退回 RRF |
| `chat.history-messages` | 6 | 改写、生成 | 携带的历史消息条数 |
| `chat.max-context-chars` | 18000 | 拼接 Prompt | 参考资料的字数预算（不含读全文的部分） |
| `chat.full-text-chars` | 40000 | 拼接 Prompt | 命中片段所属的小文档整篇交给模型，全文累计不超过这个字数；0 表示不读全文 |
| `chat.verify-answers` | true | 回答核对 | 生成回答后请 LLM 对照参考资料核对，标出无依据内容和对不上的引用；每个问题多一次调用 |
| `retrieval.plan-queries` | true | 问题规划 | 是否判断汇总类问题并拆成子问题多路检索；与改写并行，每个问题多一次 LLM 调用 |
| `retrieval.aggregate-top-k` | 12 | 多路检索 | 汇总类问题合并后最多保留的片段数 |
| `retrieval.sub-query-rerank-candidates` | 10 | 多路检索 | 每个子问题（和补充检索的查询）送去精排的片段数 |
| `retrieval.max-follow-ups` | 2 | 迭代检索 | 资料不够回答时换说法补充检索的最多轮数；资料够用时只多一次判断调用；0 表示不补查 |
| `chat.aggregate-context-chars` | 24000 | 拼接 Prompt | 汇总类问题的参考资料字数预算 |
| `chat.expand-chars` | 1500 | 拼接 Prompt | 每条参考资料扩展到的最大字数（接上同一章节的前后片段），0 表示不扩展 |

**已知限制**：

- **文字识别的局限**：扫描页靠苹果 Vision 识别，只能在 macOS 上运行；数学公式基本识别不出来，代码偶有 `0/O`、`l/1` 混淆，「入」「人」这类形近字偶尔认错。图片、图表里的信息不会被提取（文字型 PDF 里的插图也不做识别）。
- **精排依赖本地服务和算力**：Rerank 服务要单独启动，模型约 2.2GB；精排 20 个片段中位约 1.7 秒。没启动时自动降级为 RRF + 余弦阈值，拒答能力回到精排前的水平。
- **仍有拒答不了的难负例**：「公司班车几点发车」「迟到一次扣多少钱」「加班费按工资的几倍算」这类问题，库里有高度相关的章节但没有答案，精排分数仍高于阈值，要靠 LLM 按提示词说明「资料中没有」。答案级评测中这几道题 LLM 都正确拒答了，但这依赖模型遵守提示词，没有硬性保证。
- **模型会用自身知识补充**：答案级评测中 116 个回答有 3 个含有参考资料之外的内容（两个是法律常识，一个是对编号前缀的猜测）。提示词要求只依据资料作答，但约束不是绝对的。
- **查询改写依赖 DeepSeek**：每个问题多一次同步调用，首字延迟增加约 1～2 秒；DeepSeek 过载或超时（10 秒）时退回原问题检索，口语化问题的召回会回到改写前的水平。改写即使用 temperature 0 也不完全确定，同一道题两次检索的结果可能不同。
- **改写可能带入模型的知识**：改写会补充模型认为相关的术语，方向偏了时会把检索引向通用内容，例如多证据列举题「哪些行为会被公司直接解除劳动合同」有一条证据被挤出前 6。提示词要求不编造数字和结论，但无法保证改写只用文档里的词。
- **二字组分词较粗**：关键词召回没有词典，「合同」会命中「劳动合同」「租赁合同」，所以融合时给它的权重较低；按人名、型号精确检索主要靠「精确命中优先」。
- **阈值全局统一**：`rerank.min-score` 是在六份题集上整体校准的，不同知识库的分数分布不同，一个全局阈值不能兼顾所有库。调整前先用 `scripts/eval_retrieval.py` 在各题集上评测。
- **向量维度写死**：`vector(1024)` 和 bge-m3 绑定，更换 embedding 模型要改表结构并重新处理全部文档。
- **引用由模型自觉标注**：`[n]` 是否准确依赖提示词约束，线上没有校验模型引用的编号是否真的支持对应的句子；目前只在答案级评测中离线检查，116 个回答没有发现引用错误。

**后续演进**（摘自 README）：P2 已完成混合召回、Reranker 精排、检索评测、答案级评测、按组切分（条文、列举项不切开）和口语化查询改写；P3 已完成扫描件 OCR（苹果 Vision）和按文件夹限定检索范围，复杂版面与公式（MinerU / Docling）留待以后，改动集中在 `DocumentParser`。
