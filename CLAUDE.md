# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

本地 RAG 知识库：上传 Word / Excel / PPT / PDF → 解析、切块、向量化 → 基于检索结果用 DeepSeek 流式回答，并标注 `[n]` 原文引用。用户文档与 README 均为中文，代码注释、日志、异常信息、提示词也统一用中文。

## 常用命令

后端必须用 JDK 21，且要在项目根目录运行（才能读到 `config/application.yaml` 中的 DeepSeek Key，该目录已 gitignore；也可用环境变量 `DEEPSEEK_API_KEY`）。

```bash
docker compose up -d                                              # PostgreSQL 17 + pgvector，端口 5433（不是 5432）
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./mvnw spring-boot:run                                            # 启动后端 http://localhost:8080
./mvnw test                                                       # 全部测试
./mvnw test -Dtest=ChunkerTests                                   # 单个测试类
./mvnw test -Dtest=ChunkerTests#splitsOnHeadingChange             # 单个测试方法
python3 scripts/eval_retrieval.py --kb-id 5 --csv scripts/eval/laws.csv   # 检索评测（需后端运行），题集格式见脚本头注释；基线在 scripts/eval/baseline/
python3 scripts/eval_retrieval.py --kb-id 5 --csv scripts/eval/laws.csv --rerank off   # 对比不精排的效果
python3 scripts/eval_answer.py --kb-id 5 --csv scripts/eval/answers/laws.csv    # 答案级评测：真实问答 + DeepSeek 裁判（要点、编造、引用、拒答），基线在 scripts/eval/baseline/answers/
swiftc -O scripts/ocr/vision_ocr.swift -o scripts/ocr/vision-ocr   # 编译扫描版 PDF 的文字识别工具（苹果 Vision，仅 macOS，产物已 gitignore）
python3 scripts/import_dir.py --kb-id 7 --dir ~/资料 --out import.csv   # 目录批量导入（需后端运行），等处理完输出报告；--dry-run 只扫描，可重复运行
HF_HUB_OFFLINE=1 python3 scripts/rerank_server.py                # Rerank 服务 http://localhost:8081（bge-reranker-v2-m3，需 sentence-transformers；不在 docker 里，安装与启动见 README）

cd frontend && npm install
npm run dev          # http://localhost:5173，/api 代理到 8080
npm run build        # 产物输出到 ../src/main/resources/static（已 gitignore），随 jar 打包
npm run typecheck    # vue-tsc 类型检查
```

`SuperKnowledgeBaseApplicationTests`（`@SpringBootTest`）会连接数据库并执行 Flyway 迁移，需要先启动 docker 中的 PostgreSQL；`ChunkerTests` 是纯单元测试，不依赖外部服务。

外部依赖：Ollama（`localhost:11434`，已拉取 `bge-m3`）只用于 embedding；Rerank 服务（`localhost:8081`，`scripts/rerank_server.py`）用于精排，没启动时检索自动降级；LLM 只用 DeepSeek 云端 API，不要在本机跑本地 LLM（内存不足）。

## 架构

技术栈：Spring Boot 4 + Spring AI 2（DeepSeek chat / Ollama embedding）+ Apache Tika 3 + PostgreSQL/pgvector；前端 Vue 3 + Element Plus + Vite。

后端包 `flyingFish.ai.superKnowledgeBase` 按功能划分：`kb`（知识库）、`document`（上传、文件存储、状态）、`ingestion`（解析/切块/向量化）、`retrieval`（向量检索）、`chat`（会话与 RAG 问答）、`common`（配置、异常、pgvector 工具）。

### 数据访问约定

- 不用 JPA / Spring Data：每个模块有手写 SQL 的 `*Repository`，基于 `JdbcClient`，结果直接映射到 Java `record`。
- 向量以文本字面量 `[0.1,0.2,...]` 传入 SQL（`common/PgVectors.toLiteral`），在 SQL 里 `CAST(:vector AS vector)`。
- 表结构由 Flyway 管理（`src/main/resources/db/migration`）。已有迁移不可修改，改表结构需新增 `V{n}__xxx.sql`；新表/新字段要同时补 `COMMENT ON` 中文注释（项目所有表和字段都有注释）。
- `chunk.embedding` 固定为 `vector(1024)`（bge-m3 的维度），`knowledge_base.embedding_model` 记录生成向量的模型；更换 embedding 模型意味着改迁移并重建整个库的向量。
- Jackson 为 3.x（包名 `tools.jackson.*`，不是 `com.fasterxml.jackson.*`）。

### 摄入管线（`ingestion/IngestionService`）

上传（`DocumentService`）→ 按 SHA-256 在同一知识库内去重（`DUPLICATE`）→ 同一来源（文件夹导入按 `source_path`，单独上传按文件名）已有文档时视为新版本，`replaceFile` 在原文档上换文件、删旧文件和识别缓存后重新处理（`UPDATED`，文档 id 不变，处理完成前旧片段仍可检索）→ 文件存到 `skb.storage-dir/{kbId}/{sha256}.{ext}` → `IngestionService.submit` 异步处理。处理在固定大小的虚拟线程池中进行（`skb.ingestion.concurrency`，避免压垮本地 Ollama），文档状态依次为 `PENDING → PARSING → (OCR) → EMBEDDING → READY / FAILED`。应用关闭时中断的处理不标记失败，下次启动继续。旧 chunk 删除与新 chunk 写入在同一事务中完成，所以 reindex 是原子替换。应用启动时（`ApplicationReadyEvent`）会自动重新提交所有未完成状态的文档。

- `DocumentParser` + `TikaBlockHandler`：Tika 输出 → `TextUnit`（文本 + 章节路径 + 位置）。Word 优先用标题样式，没有样式时用中文编号正则（"第X章"、"一、"、"2.1" 等）推断标题；PDF 记录页码，并先经 `PdfTextCleaner` 去掉页眉页脚和目录、接回被换行截断的句子；PPT 每张幻灯片首行作为标题；Excel 每行转成"列名: 值"。
- `Chunker`：不跨章节、不跨幻灯片/工作表（PDF 跨页允许合并，位置记为"第1-2页"）。先把不可分的段落归组（法律条文及其款项、冒号结尾的引导句与其后的列举项、连续列举项），只在组之间按 `skb.chunk.*` 的目标/上限字数切分，组间不重叠；超长组在组内切分，后续片段重复最近的引导句，没有引导句时用句子级重叠。改切块规则后要对已有文档 reindex 才生效。
- 扫描版 PDF：`DocumentParser.withOcr` 把文字层（去掉每页重复的页眉页脚和水印后）少于 `skb.ocr.min-page-chars` 的页交给 `OcrService`，它调用 `skb.ocr.command`（`scripts/ocr/vision-ocr`，Swift + 苹果 Vision，每识别一页输出一行 JSON），结果按文件 SHA-256 + 页码缓存在 `ocr_page` 表（reindex 不重复识别），进度写入 `document.ocr_done/ocr_pages`。识别出的行作为该页的段落（与同页文字层编辑距离相似的行丢弃，水印渲染后会被再识别一遍），再统一经过 `PdfTextCleaner`。命令不存在时跳过识别。
- `DocumentParser.stripEncoded` 去掉 PDF 里嵌入的编码内容（含字母的长串十六进制、Adobe 设置字典），这类乱码片段向量几乎相同，会把 HNSW 检索引偏。
- 标题识别：编号规则的层级为 编 1 > 分编 2 > 章 3 > 节/"一、" 4 > "（一）" 5 > 小数编号（段数 + 3）；样式标题开头的小数编号（Tika 常算错）会被去掉。
- 文档摘要（`DocumentSummarizer`，`skb.summary.*`）：切块后把文件路径、目录、开头和均匀抽取的 8 段正文交给 DeepSeek 写一段摘要，作为 `chunk.kind = 'SUMMARY'`、`chunk_index = -1`、章节为「文档摘要」的特殊片段和正文一起参与检索（关键词索引额外加上文件出处），用于「这本书讲了什么」「哪些资料讲到了 XX」这类问题。摘要原文存在 `document.summary`，重新处理时复用，`replaceFile` 换新版本时清空；生成失败只记 WARN。`SummaryBackfill` 在启动时后台为已有文档逐个补生成（只调 LLM 和向量化一次，与摄入共用"处理中"标记）。正文片段少于 `skb.summary.min-chunks`（5）的短文档不生成摘要片段（正文就是全部内容，14 个激活码文件的摘要曾把操作教程挤出前 10），`SummaryBackfill` 启动时清掉已有的；`chunk_count` 不含摘要片段；`SuperKnowledgeBaseApplicationTests` 关掉了摘要，避免测试调用 DeepSeek。
- 向量化时把"文件名 + 章节 + 位置"拼在片段前面（`IngestionService.embeddingText`），但数据库中 `chunk.content` 只存原文。文件夹导入（页面「选择文件夹」或 `scripts/import_dir.py`）时上传接口会带 `paths` 字段，记录到 `document.source_path`（相对路径，含所选文件夹名），向量文本中用它代替文件名；该字段只影响之后处理的文档。

### 检索与问答

- `retrieval/Retriever`：混合检索。向量召回是 pgvector HNSW 余弦检索，按 `kb_id` 过滤，查询前 `SET LOCAL hnsw.iterative_scan = relaxed_order` 防止过滤后召回不足，因此结果需要在 Java 中重新排序；同时 `SET LOCAL hnsw.ef_search`（`skb.retrieval.ef-search`，200），默认的 40 在大库里会漏掉真正最近的片段；索引用 `m = 32, ef_construction = 128` 建（V8），默认参数建出的图会困在大量近似重复片段聚成的团里，评测见迁移注释。执行方式不交给 PG 估算：范围内片段（`document.chunk_count` 之和）超过 5000 时 `SET LOCAL enable_sort = off` 强制走 HNSW（m = 32 的索引被估得偏贵，PG 会改成读 1.8GB 的全表精确计算），否则 `enable_indexscan = off` 精确计算；整个检索事务还设置了 `plan_cache_mode = force_custom_plan`，JDBC 预编译后的通用计划会对每一行重新解析向量参数（5 万片段 8 秒）。BM25 跳过命中超过一成片段的常见词（如「一个」），否则 tf 统计要展开几万个片段的词表。每次检索在日志里输出召回、精排耗时，问答的改写、检索、首字、生成耗时写入 `retrieval_trace.timings`。验证召回时用 `SET LOCAL enable_indexscan = off` 做精确计算对比，注意 ef_search 较大时 PG 可能自己改走全表扫描（`EXPLAIN` 里没有 Index Scan），对比前先看执行计划。关键词召回是 BM25：`KeywordAnalyzer` 在 Java 里切词（中文二字组、英文数字整词、条款号规范成"第N条"），写入 `chunk.keyword_tsv`，PG 只做 GIN 索引和打分，不依赖中文分词扩展。融合时，精确命中问题中编号的片段排在最前，其余按加权 RRF 排序。随后把 `skb.rerank.candidates` 个片段（章节路径 + 正文）交给 `RerankClient` 精排：关键词召回中排名最前的 3 个正文片段必进候选（只被关键词召回的片段在 RRF 里排在所有向量结果之后；摘要片段不占名额），其余按 RRF 顺序补足。精排后 `dropDuplicates` 去掉与更靠前结果字符三元组相似度 ≥ 0.85 的片段（同一本书的副本、同一译本的不同扫描版），调试接口和问答都生效，仍然精确命中优先、其余按 `rerankScore` 排序；精排在数据库事务之外调用，服务不可用时记 WARN 并退回 RRF 排序。`score` 始终是余弦相似度，`rerankScore` 是精排分数（0~1，未精排为 null）。相关性由 `relevant` 字段表示：精排时为 `rerankScore >= rerank.min-score`，未精排时为 `score >= retrieval.min-score`，精确命中的片段始终相关。`retrieve()` 只保留 `relevant` 的片段；`search()` 不过滤，供 `/search` 调试接口使用（支持 `mode=hybrid|vector|keyword`、`rerank=true|false` 和 `rewrite=true|false`，默认与问答一样先改写，单路模式不精排），评测脚本直接读取 `relevant`。修改分词规则后，要把 `keyword_tsv` 置空并重启，由 `KeywordIndexBackfill` 重建。
- 检索范围（`retrieval/SearchScope`）：文件夹（`document.source_path` 前缀，用 `starts_with` 匹配，含子文件夹）与文档 ID 的并集，都为空表示整个知识库。`Retriever` 先在事务里把范围解析成文档 ID，两路召回都加 `c.document_id IN (...)`；范围小时 PG 会自己改走 `document_id` 索引 + 精确计算，范围大时走 HNSW 过滤，不需要额外处理。BM25 的 N、df 仍按整个知识库统计。问答的范围存在 `conversation.scope`（JSONB，不限范围时为 NULL），前端 `ScopeSelector.vue` 从文档的 `sourcePath` 组成文件夹树。
- `chat/ChatService`：先用 `retrieval/QueryRewriter` 让 LLM 把问题改写成文档术语（多轮时同时补全指代），检索文本为「原问题 + 换行 + 改写结果」，单轮也改写；有对话历史时改用 `MULTI_TURN_PROMPT`，输出 JSON 判断最新问题是否依赖上文，依赖时检索文本只用补全后的查询（纠正时去掉被否定的内容），不依赖时禁止带入历史中的人名书名话题，效果用 `scripts/eval/multiturn_*.csv` 评测 → 检索 → `Retriever.expand` 把命中片段在同一文档、同一章节里的前后片段接上（每条最多 `skb.chat.expand-chars` 字，相邻的命中片段并成一条）→ 按 `skb.chat.max-context-chars` 预算组装带编号的参考资料 → `ChatClient` 流式生成。返回 SSE 事件 `citations` → `delta`* → `done`（消息 id 和各环节耗时 `timings`）→ `verification`（出错时为 `error`），前端 `ChatView.vue` 依赖这个顺序，并在回答下方显示检索耗时（消息接口的 `timings` 取自 `retrieval_trace`，历史消息同样显示）。`verification` 来自 `AnswerVerifier`：回答保存后再请 DeepSeek 对照参考资料找出无依据内容和对不上的 `[n]` 引用（判定标准与 `scripts/eval_answer.py` 的裁判一致），写入 `message.verification` 并推给前端；`skb.chat.verify-answers` 关闭，核对失败时不发送。前端收到 `done` 就允许继续提问，核对结果稍后到达。无命中时不调用 LLM，直接返回固定回答。改写后的查询和命中分数作为 `retrieval_trace` 存入 `message` 表，便于排查 bad case。
- 未配置 DeepSeek Key 时应用依然可以启动（占位值 `not-configured`），只有问答接口会返回 error 事件。

### 反馈与问答分析

- 回答评价存在 `message.feedback / feedback_reason / feedback_note`（`PUT /api/messages/{id}/feedback`，取消时返回 204）。
- `insight/InsightService` 从 `message` 已有的 `retrieval_trace`（检索查询、命中分数、`timings`）、`verification`、评价算统计和坏案例，不需要额外埋点；判定规则见 `flags()`：拒答 = 没有参考资料或回答含「知识库中没有找到」（不用「资料中没有说明」，正常回答里也常出现）；精排未生效 = 命中片段都没有 `rerankScore`；慢 = 改写 + 检索 + 首字超过 15 秒。评测脚本创建的会话（标题以「[评测]」开头）不计入。
- `eval_case` 表存从坏案例整理出的评测题，`/api/knowledge-bases/{id}/eval-cases.csv` 导出为 `eval_retrieval.py` 的题集格式，脚本的 `--csv` 可以直接填这个地址。

### 错误处理

Service 层用异常表达业务错误，`GlobalExceptionHandler` 统一映射为 `ProblemDetail`：`NotFoundException` → 404，`IllegalArgumentException` → 400，`IllegalStateException` → 409。前端 `api.ts` 读取响应中的 `detail` 字段展示错误。

## 配置

RAG 相关参数（切块大小、召回数量、相似度阈值、历史轮数、上下文预算、摄入并发）都在 `application.yaml` 的 `skb.*` 下，绑定到 `common/SkbProperties`（record）。新增参数需要同时修改两处，且 `ChunkerTests` 中直接构造了 `SkbProperties`，其构造参数变化时测试也要跟着改。
