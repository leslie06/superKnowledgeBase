# 超级知识库（superKnowledgeBase）

本地 RAG 知识库：上传 Word / Excel / PPT / PDF，自动解析、切块、向量化，基于检索结果用 DeepSeek 回答，每个结论都标注可点击的原文出处。

## 架构

```
浏览器 (Vue 3 + Element Plus)
   │ REST / SSE
Spring Boot 4 + Spring AI 2
   ├─ 摄入：Tika 解析 → 结构感知切块 → bge-m3 向量化（Ollama 本地） → PostgreSQL + pgvector
   ├─ 检索：问题改写（多轮） → 向量召回 + BM25 关键词召回 → RRF 融合 → 相关性过滤
   └─ 生成：带编号参考资料的 Prompt → DeepSeek 流式输出 → [n] 引用
```

| 组件 | 作用 | 位置 |
|---|---|---|
| PostgreSQL 17 + pgvector | 元数据、向量、对话记录 | docker，端口 5433 |
| Ollama + bge-m3 | 文本向量化（本地，约 1.2GB 内存） | 端口 11434 |
| DeepSeek | 问答生成、问题改写 | 云端 API |
| 原始文件 | 按内容哈希存储 | `./data/files` |

## 启动

前置条件：JDK 21、Docker、Ollama（已拉取 `bge-m3`）、Node 22（仅开发前端时需要）。

```bash
# 1. 数据库
docker compose up -d

# 2. DeepSeek Key：写在 config/application.yaml（已 gitignore），或设置环境变量 DEEPSEEK_API_KEY

# 3. 构建前端（产物输出到 src/main/resources/static，随 jar 一起打包）
cd frontend && npm install && npm run build && cd ..

# 4. 启动后端（需在项目根目录启动，才能读到 config/application.yaml）
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw spring-boot:run
```

打开 http://localhost:8080 。

前端开发：后端启动后执行 `cd frontend && npm run dev`，访问 http://localhost:5173（`/api` 自动代理到 8080）。

## 处理逻辑要点

- **解析**（`ingestion/DocumentParser`）：Word 按标题样式建立章节路径，没有样式时按"第X章 / 一、/ 2.1"等编号识别；PDF 记录页码；PPT 按幻灯片切分，首行作为标题；Excel 每行转成"列名: 值"，保证切块后每行仍带表头语义。扫描版 PDF 暂不支持（需要 OCR）。
- **切块**（`ingestion/Chunker`）：不跨章节、不跨幻灯片或工作表；目标 600 字、上限 1000 字；按长度切分时保留约 100 字的句子级重叠。
- **向量文本**：把"文件名 + 章节路径 + 位置"拼在片段前面再做向量化，提升"XX 手册里的 YY"这类问题的命中率。
- **混合检索**（`retrieval/Retriever`）：向量召回负责语义相近的问法，BM25 负责条款号、编号、型号这类字面信息（中文按二字组切词，不依赖数据库分词扩展）。精确命中问题中编号的片段排最前，其余按加权 RRF 融合。
- **检索评测**：`scripts/eval_retrieval.py` 按题集统计 Hit@k、MRR、问答可用率和无答案题拒答率，题集与基线在 `scripts/eval/`。
- **去重**：同一知识库内按 SHA-256 去重。
- **恢复**：服务重启后自动继续处理未完成的文档。

参数（切块大小、召回数量、相似度阈值等）都在 `src/main/resources/application.yaml` 的 `skb.*` 下。

结合代码的详细说明见 [docs/rag-pipeline.md](docs/rag-pipeline.md)（[在线版](https://claude.ai/code/artifact/65ce178b-e780-41b2-9bfe-34d933a50508)）。

## 主要接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET/POST | `/api/knowledge-bases` | 知识库列表 / 新建 |
| POST | `/api/knowledge-bases/{id}/documents` | 上传（multipart，字段名 `files`，可多个） |
| GET | `/api/documents/{id}/chunks` | 查看切块结果 |
| POST | `/api/documents/{id}/reindex` | 重新处理 |
| POST | `/api/knowledge-bases/{id}/search` | 检索调试（不调用 LLM） |
| POST | `/api/conversations/{id}/chat` | 问答，SSE 事件：`citations` → `delta`* → `done` / `error` |

## 后续路线

- **P2 效果**：~~BM25 关键词检索 + 向量的混合召回（RRF 融合）~~、~~评估测试集~~（已完成）、Reranker 精排、父子块。
- **P3 规模**：MinerU / Docling 处理复杂 PDF 与扫描件 OCR、目录监听自动同步、标签过滤。
- **P4 进阶**：Agentic RAG、跨文档关系问答、图表理解。
