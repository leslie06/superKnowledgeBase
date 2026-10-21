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
python3 scripts/eval_retrieval.py --kb-id 5 --csv scripts/eval/laws.csv   # 检索评测（需后端运行），题集格式见脚本头注释

cd frontend && npm install
npm run dev          # http://localhost:5173，/api 代理到 8080
npm run build        # 产物输出到 ../src/main/resources/static（已 gitignore），随 jar 打包
npm run typecheck    # vue-tsc 类型检查
```

`SuperKnowledgeBaseApplicationTests`（`@SpringBootTest`）会连接数据库并执行 Flyway 迁移，需要先启动 docker 中的 PostgreSQL；`ChunkerTests` 是纯单元测试，不依赖外部服务。

外部依赖：Ollama（`localhost:11434`，已拉取 `bge-m3`）只用于 embedding；LLM 只用 DeepSeek 云端 API，不要在本机跑本地 LLM（内存不足）。

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

上传（`DocumentService`）→ 按 SHA-256 在同一知识库内去重 → 文件存到 `skb.storage-dir/{kbId}/{sha256}.{ext}` → `IngestionService.submit` 异步处理。处理在固定大小的虚拟线程池中进行（`skb.ingestion.concurrency`，避免压垮本地 Ollama），文档状态依次为 `PENDING → PARSING → EMBEDDING → READY / FAILED`。旧 chunk 删除与新 chunk 写入在同一事务中完成，所以 reindex 是原子替换。应用启动时（`ApplicationReadyEvent`）会自动重新提交所有未完成状态的文档。

- `DocumentParser` + `TikaBlockHandler`：Tika 输出 → `TextUnit`（文本 + 章节路径 + 位置）。Word 优先用标题样式，没有样式时用中文编号正则（"第X章"、"一、"、"2.1" 等）推断标题；PDF 记录页码；PPT 每张幻灯片首行作为标题；Excel 每行转成"列名: 值"。
- `Chunker`：不跨章节、不跨幻灯片/工作表（PDF 跨页允许合并，位置记为"第1-2页"）；按 `skb.chunk.*` 的目标/上限字数切分，句子级重叠。
- 向量化时把"文件名 + 章节 + 位置"拼在片段前面（`IngestionService.embeddingText`），但数据库中 `chunk.content` 只存原文。

### 检索与问答

- `retrieval/Retriever`：pgvector HNSW 余弦检索，按 `kb_id` 过滤；查询前 `SET LOCAL hnsw.iterative_scan = relaxed_order` 防止过滤后召回不足，因此结果需要在 Java 中重新排序。`retrieve()` 按 `skb.retrieval.min-score` 过滤，`search()` 不过滤，供 `/search` 调试接口使用。后续计划在这里加入 BM25 混合检索 / RRF / Rerank。
- `chat/ChatService`：多轮对话时先用 LLM 把问题改写成独立查询 → 检索 → 按 `skb.chat.max-context-chars` 预算组装带编号的参考资料 → `ChatClient` 流式生成。返回 SSE 事件 `citations` → `delta`* → `done`（出错时为 `error`），前端 `ChatView.vue` 依赖这个顺序。无命中时不调用 LLM，直接返回固定回答。改写后的查询和命中分数作为 `retrieval_trace` 存入 `message` 表，便于排查 bad case。
- 未配置 DeepSeek Key 时应用依然可以启动（占位值 `not-configured`），只有问答接口会返回 error 事件。

### 错误处理

Service 层用异常表达业务错误，`GlobalExceptionHandler` 统一映射为 `ProblemDetail`：`NotFoundException` → 404，`IllegalArgumentException` → 400，`IllegalStateException` → 409。前端 `api.ts` 读取响应中的 `detail` 字段展示错误。

## 配置

RAG 相关参数（切块大小、召回数量、相似度阈值、历史轮数、上下文预算、摄入并发）都在 `application.yaml` 的 `skb.*` 下，绑定到 `common/SkbProperties`（record）。新增参数需要同时修改两处，且 `ChunkerTests` 中直接构造了 `SkbProperties`，其构造参数变化时测试也要跟着改。
