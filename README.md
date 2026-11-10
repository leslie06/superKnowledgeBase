# 超级知识库（superKnowledgeBase）

本地 RAG 知识库：上传 Word / Excel / PPT / PDF，自动解析、切块、向量化，基于检索结果用 DeepSeek 回答，每个结论都标注可点击的原文出处。

## 架构

```
浏览器 (Vue 3 + Element Plus)
   │ REST / SSE
Spring Boot 4 + Spring AI 2
   ├─ 摄入：Tika 解析 → 结构感知切块 → bge-m3 向量化（Ollama 本地） → PostgreSQL + pgvector
   ├─ 检索：查询改写（口语 → 文档术语，多轮补全指代） → 向量召回 + BM25 关键词召回 → RRF 融合 → bge-reranker 精排 → 相关性过滤
   └─ 生成：带编号参考资料的 Prompt → DeepSeek 流式输出 → [n] 引用
```

| 组件 | 作用 | 位置 |
|---|---|---|
| PostgreSQL 17 + pgvector | 元数据、向量、对话记录 | docker，端口 5433 |
| Ollama + bge-m3 | 文本向量化（本地，约 1.2GB 内存） | 端口 11434 |
| Rerank 服务 + bge-reranker-v2-m3 | 检索结果精排（本地，`scripts/rerank_server.py`，约 2.2GB 模型） | 端口 8081 |
| DeepSeek | 问答生成、查询改写 | 云端 API |
| 原始文件 | 按内容哈希存储 | `./data/files` |

## 启动

前置条件：JDK 21、Docker、Ollama（已拉取 `bge-m3`）、Python 3.10+ 与 `pip install sentence-transformers`（Rerank 服务）、Node 22（仅开发前端时需要）。

```bash
# 1. 数据库
docker compose up -d

# 2. DeepSeek Key：写在 config/application.yaml（已 gitignore），或设置环境变量 DEEPSEEK_API_KEY

# 3. Rerank 服务（另开一个终端；首次运行会下载约 2.2GB 模型，详见下方「Rerank 服务（Python）」）
python3 scripts/rerank_server.py

# 4. 编译扫描版 PDF 的文字识别工具（苹果 Vision，仅 macOS，需要 Xcode 命令行工具；不编译时扫描页取不到文字）
swiftc -O scripts/ocr/vision_ocr.swift -o scripts/ocr/vision-ocr

# 5. 构建前端（产物输出到 src/main/resources/static，随 jar 一起打包）
cd frontend && npm install && npm run build && cd ..

# 6. 启动后端（需在项目根目录启动，才能读到 config/application.yaml）
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw spring-boot:run
```

打开 http://localhost:8080 。

前端开发：后端启动后执行 `cd frontend && npm run dev`，访问 http://localhost:5173（`/api` 自动代理到 8080）。

### 日常启动（重启电脑后）

首次安装完成后，每次开机按下面的顺序启动。Rerank 服务和后端谁先谁后都可以：后端每次检索都实时调用它，调用失败就跳过精排，所以后端已在运行时再启动 Rerank 服务，精排会立即生效，不用重启后端。

```bash
cd /path/to/superKnowledgeBase
docker compose up -d                                   # 1. PostgreSQL（先打开 Docker Desktop）
                                                       # 2. Ollama：打开 Ollama 应用即可
HF_HUB_OFFLINE=1 python3 scripts/rerank_server.py      # 3. Rerank 服务（另开一个终端，保持窗口不关）
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw spring-boot:run   # 4. 后端（或在 IDE 中启动）
```

### Rerank 服务（Python）

`scripts/rerank_server.py` 是项目唯一的 Python 服务，用交叉编码器 bge-reranker-v2-m3 给检索结果精排，监听 `http://127.0.0.1:8081`。它不在 docker compose 里：Mac 上的 Docker 用不了 Apple 芯片的 GPU（MPS），只能 CPU 推理，延迟会高出数倍；Ollama 直接装在本机也是同样的原因。

**它是可选的**：没启动时后端会记一条 WARN 日志，退回 RRF 排序和余弦阈值，问答照常进行，只是没有精排效果（主要是无答案问题的拒答能力变弱）。完全不想用时把 `application.yaml` 的 `skb.rerank.enabled` 设为 `false`。

**首次安装**

```bash
pip install sentence-transformers          # 会一并装上 torch；需要 Python 3.10+
python3 scripts/rerank_server.py           # 首次运行自动下载模型（约 2.2GB）到 ~/.cache/huggingface
```

国内网络下载慢时加上镜像：`HF_ENDPOINT=https://hf-mirror.com python3 scripts/rerank_server.py`。下载中断后重新运行会断点续传。

**启动**（模型已下载后）

```bash
cd /path/to/superKnowledgeBase

# 前台运行，终端窗口要保持打开
HF_HUB_OFFLINE=1 python3 scripts/rerank_server.py

# 或者后台运行，关掉终端也不会停
HF_HUB_OFFLINE=1 nohup python3 scripts/rerank_server.py > /tmp/rerank.log 2>&1 &
```

`HF_HUB_OFFLINE=1` 让它直接用本地缓存的模型、不联网检查更新，启动更快，断网也能用。约 10 秒后出现 `模型已加载（mps），监听 http://127.0.0.1:8081` 即启动完成。

**检查与停止**

```bash
curl localhost:8081/health          # 正常时返回 {"model": "BAAI/bge-reranker-v2-m3", "device": "mps"}
tail -f /tmp/rerank.log             # 后台运行时查看日志，每次精排会打印片段数和耗时
lsof -iTCP:8081 -sTCP:LISTEN        # 查看占用 8081 的进程
pkill -f rerank_server.py           # 停止
```

**常用参数**：`--port`（默认 8081，改了要同步修改 `skb.rerank.base-url`）、`--model`（默认 `BAAI/bge-reranker-v2-m3`，内存紧张时可换 `BAAI/bge-reranker-base`，但中文法规类效果明显变差）、`--max-length`（默认 1024 token）、`--batch-size`（默认 16）。

**注意**

- 启动用的 `python3` 必须是装了 sentence-transformers 的那个环境。先运行 `which python3` 和 `python3 -c "import sentence_transformers"` 确认；如果用 conda / venv，先激活对应环境。
- 设备自动选择：Apple 芯片用 MPS（fp16），有 NVIDIA 显卡用 CUDA，否则用 CPU。日志里的 `device` 显示实际使用的设备，CPU 推理会慢很多。
- **内存紧张时精排会变得很慢**：正常精排 20 个片段约 2 秒。交换区接近用满时模型会被换出到磁盘，每次精排都要先读回来，一次 15 秒以上，超过 `skb.rerank.timeout`（15 秒）后退回不精排的排序，整次问答可能拖到 30～40 秒。用 `sysctl vm.swapusage` 查看交换区，关掉暂时不用的程序和 docker 容器；Docker Desktop 停掉容器后不会把内存还给系统，要在 Settings → Resources 调小 Memory（只跑本项目的 PostgreSQL 时 3GB 足够）并重启 Docker，然后重启 Rerank 服务，让模型重新完整加载到内存。服务每次精排后会释放 PyTorch 缓存的显存（`torch.mps.empty_cache()`）：早期版本不释放，每批片段长度不同，缓存越积越大，连续运行一天后进程占到 22GB（其中 MPS 显存缓存 14GB），闲置一会儿后第一次精排要 10～25 秒。释放显存后普通堆内存仍会涨：MPS 为每一种输入形状（批大小 × 序列长度）编译一张计算图并一直缓存，片段长度每次都不同，实测每次请求涨约 60MB，运行 21 小时涨到 17GB；现在序列长度补齐到 128 的倍数、批大小补齐到 2 的幂，形状最多 40 种，占用稳定在 3～4GB（分数与补齐前相差不到 0.002，排序一致，耗时不变）。用 `footprint -p $(pgrep -f rerank_server.py) | grep phys_footprint` 可以查看它的实际占用，正常在 2～4GB。
- 接口与 HuggingFace text-embeddings-inference（TEI）的 `/rerank` 相同。部署到有 NVIDIA 显卡的 Linux 服务器时，可以直接用 TEI 镜像替代这个脚本，只需修改 `skb.rerank.base-url`。

### 扫描版 PDF 的文字识别（OCR）

扫描版 PDF 的页面是图片，没有文字层。解析时文字少于 20 个字的页（每页重复的水印、页眉页脚不算）会交给 `scripts/ocr/vision-ocr` 识别，它用 macOS 自带的 Vision 框架，不需要另外启动服务，也几乎不占内存。首次使用先编译（编译产物已 gitignore）：

```bash
swiftc -O scripts/ocr/vision_ocr.swift -o scripts/ocr/vision-ocr
scripts/ocr/vision-ocr data/files/<知识库ID>/<文件>.pdf 200 1 2    # 试识别第 1、2 页，每页输出一行 JSON
```

- 速度约 0.5～1 秒一页（M2 Pro），一本 400 页的书 3～7 分钟。处理时文件列表显示「文字识别 120/400 页」。
- 识别结果按文件内容缓存在 `ocr_page` 表：中途重启后从缓存接着识别；以后修改切块规则「重新处理」时直接用缓存，不会重新识别。删除文档或知识库后，没有文档引用的缓存会一并删除。
- 没有编译或命令不存在时，后端启动会打一条 WARN，扫描页跳过识别，和接入 OCR 之前一样。
- 接入前已导入的扫描版 PDF 要点「重新处理」才会识别。
- 换其他识别引擎（如 Linux 上的 RapidOCR）时，提供参数和输出格式相同的命令（见 `vision_ocr.swift` 头部注释），修改 `skb.ocr.command` 即可。
- 数学公式基本识别不出来；代码里偶有 `0/O`、`l/1` 混淆，对检索影响不大。

### 批量导入目录

**页面上**：「文档管理」页点「选择文件夹」，或把文件夹直接拖进上传区，会包含所有子文件夹。

- **导入前预览**：按格式（Word / Excel / PPT / PDF / 文本）列出文件数、大小和扩展名，并列出将跳过的文件（隐藏文件、Office 临时文件、空文件、超过 200MB 的文件、不支持的类型）及原因，确认后才开始导入。
- **导入进度**：上传按每批最多 10 个、100MB 分批进行；进度面板分「上传」和「解析与向量化」两条进度条，实时显示新增、已就绪、处理中、失败、已存在的数量，以及已用时间和预计剩余时间。上传期间不要关闭页面；上传完成后可以离开，后台会继续处理，但进度面板不会保留。
- 有失败的文件时，点「查看」会把文件列表筛选为失败的文件；文件列表也可以按状态筛选。
- **重新导入同一个文件夹即可同步**：内容改过的文件会替换旧版本（进度面板显示「更新」）；预览里会列出知识库中已不在这个文件夹里的文档，勾选后导入完成时一并删除（默认不勾选）。单独上传与已有文档同名的文件，也视为新版本替换。

**脚本**：需要看汇总报告时用 `scripts/import_dir.py`。它同样递归扫描目录、逐个上传，并等后端处理完，再输出按类型统计的导入报告（只依赖 Python 标准库，需要后端运行）：

```bash
python3 scripts/import_dir.py --kb-id 7 --dir ~/资料 --dry-run           # 先只扫描：各类型文件数、大小、会跳过哪些
python3 scripts/import_dir.py --kb-id 7 --dir ~/资料 --out import.csv    # 上传并等待处理完成，明细写入 CSV
```

- 跳过隐藏文件和目录、Office 临时文件（`~$` 开头）、空文件、超过 200MB 的文件和不支持的类型（如 `.wps`、图片），扫描结果里会列出数量。
- 后端按内容去重，重复运行是安全的：已导入的文件记为「已存在」，不会重复处理，这时脚本相当于重新生成报告。等待期间按 Ctrl-C 不影响后端继续处理。
- **引用原文件，不复制（`--link`）**：默认导入会把文件复制一份到 `data/files/`（网页上传拿不到原文件的路径，只能复制；资料库 109 本电子书复制占 1.7GB）。后端和文件在同一台电脑上时，加 `--link` 只把原文件的绝对路径交给后端，后端直接读原文件，不复制，也不受 200MB 上传上限的限制。已经复制导入过的文档（内容相同）会改为引用原文件并删除副本，不用重新处理，所以对已导入的目录加 `--link` 再运行一次就能把副本省掉。代价是原文件移动后，重新处理和查看原文件会失败（检索、问答不受影响，片段都在库里；文档管理里标「原文件缺失」）：整个文件夹挪走的，在文档管理点「修改文件夹位置」填旧位置和新位置（新位置内容一致才改，不用重新处理）；或者对新位置重新运行导入脚本，按内容认出同一个文档，只更新路径。原文件内容被替换后，下次处理时按新版本处理。
- **同步更新和删除**：路径相同但内容改过的文件记为「已更新」，后端替换旧版本并重新处理（新版本处理完之前旧内容仍可检索）。知识库里属于这个目录、但目录里已经没有的文档会列出来，加 `--delete-missing` 时删除；本次导入中内容仍然存在的（文件只是移动或改名）不删。定时运行 `import_dir.py --kb-id 7 --dir ~/资料 --delete-missing --no-wait`（如 launchd、cron）即可让知识库跟随目录自动同步。
- 报告按类型统计成功、失败和片段数，失败原因归类，列出「文字疑似过少」的文件（每 MB 不足 1 个片段，多半是扫描件或以图片为主，有内容没提取出来）和目录里内容重复的文件，最后给出处理吞吐。
- 改进解析后，加 `--retry-failed` 重新处理之前失败的文件。
- 两种方式都会记录文件在所选文件夹中的相对路径（如「资料/人事制度/考勤管理办法.docx」），文件列表里显示在文件名下方，筛选框也可以按目录名筛。路径会代替文件名拼进向量文本，资料按文件夹分类时，「人事制度里的考勤规定」这类问题能靠目录名命中。单独上传的文件不记录路径。

### 限定检索范围

知识库里资料很杂时（技术书、小说、个人证件放在一起），别的书里措辞相近的片段会挤掉真正的答案。问答页输入框上方的「检索范围」可以按文件夹树勾选文件夹或单个文件，只在所选范围内检索：

- 范围保存在对话上，追问沿用同一范围，随时可以修改；新对话沿用当前的选择。
- 勾选整个文件夹时按文件夹保存，之后导入到这个文件夹的文件也在范围内；单独上传的文件（没有文件夹）可以单独勾选。
- 范围内找不到相关内容时，回答会提示扩大范围。
- 检索调试接口和评测脚本同样支持：`/search` 请求体加 `folders`、`documentIds`，题集 CSV 加 `scope` 列（`--scope off` 忽略它，用于对比）。

## 处理逻辑要点

- **解析**（`ingestion/DocumentParser`）：Word 按标题样式建立章节路径，没有样式时按"第X章 / 一、/ 2.1"等编号识别；PDF 记录页码；PPT 按幻灯片切分，首行作为标题；Excel 每行转成"列名: 值"，保证切块后每行仍带表头语义。扫描版 PDF 中没有文字层的页用苹果 Vision 识别（见上文）。
- **切块**（`ingestion/Chunker`）：不跨章节、不跨幻灯片或工作表；法律条文及其款项、「有下列情形之一的：」与其后的列举项不会被切开；目标 600 字、上限 1000 字；超长条文在内部切分时，后续片段重复引导句或保留约 100 字的句子级重叠。
- **向量文本**：把"文件名（文件夹导入时为相对路径）+ 章节路径 + 位置"拼在片段前面再做向量化，提升"XX 手册里的 YY""XX 部门的 YY"这类问题的命中率。
- **混合检索**（`retrieval/Retriever`）：向量召回负责语义相近的问法，BM25 负责条款号、编号、型号这类字面信息（中文按二字组切词，不依赖数据库分词扩展）。精确命中问题中编号的片段排最前，其余按加权 RRF 融合。
- **回答核对**（`chat/AnswerVerifier`）：回答生成后再请 DeepSeek 对照参考资料检查一遍，回答下方显示「已核对」，或标出资料中找不到依据的句子和对不上的 `[n]` 引用；提示词也明确禁止用模型自己的知识补充资料没写的数字、期限、法律后果。`skb.chat.verify-answers: false` 关闭。
- **文档摘要**（`ingestion/DocumentSummarizer`）：每个文档处理完后，用 DeepSeek 根据目录、开头和抽样的正文写一段摘要（主题、主要内容、关键词），作为特殊片段参与检索，「这本书讲了什么」「哪些资料讲到了 MySQL」这类整体性问题也能答；文件列表里点文件名旁的「摘要」可以查看。只有几个片段的短文档不生成（`skb.summary.min-chunks`，正文本身就是全部内容）。已有的文档在后端启动时后台补生成，每个文档约 4000 token；`skb.summary.enabled: false` 关闭。
- **按章节读原文**：问题点名了第几回、第几章（「金瓶梅第七十九回讲了什么」），直接把这一章按原文顺序整章交给模型，不用预先生成摘要。同一本书有不同版本时（万历词话本和崇祯本），各版本的这一章都读入，回答分版本说明并比较差异；同一版本的多个文件只读一个。
- **章节摘要**（默认关闭，`skb.summary.chapter-enabled`）：正文较长的章节（第X章 / 第X回 / 第X篇）各生成一段摘要参与检索，回答「第七十九回讲了什么」「西门庆死在谁的床上」这类情节问题：原文用词和问法对不上，回目和摘要能直接命中。文档处理完成后在后台生成，已有文档启动时补齐，全库约 850 个章节、一次性十几元；摘要按文件内容缓存，重新处理不重复生成。章回小说的「第X回」（含词话本的「囬」）会识别为章节标题。
- **理解问题的本意**：口语、俗语、比喻的问法照字面检索会找偏，例如「西门庆死在谁的床上」其实是问「西门庆因为哪个女人而死」。问题规划时让模型写出本意，本意另检索一路，回答时也按本意推理，不按字面纠正用户；回答下方显示「理解为：……」。
- **迭代检索**：首轮检索后先请 DeepSeek 判断资料够不够回答，不够时换原文可能用的说法再查，最多补查 1 轮（`skb.retrieval.max-follow-ups`）。问「西门庆死在谁的床上」，首轮只检索到书里上百处「床上」的生前情节，补查「西门庆 断气身亡」「第七十九回」后找到原文。资料够用时只多一次判断（约 1～2 秒），需要补查时多 5～15 秒；回答下方显示「补充检索 · N 轮」，悬停可看每轮缺什么、查了什么。
- **多给资料、小文档读全文**：问答时交给模型 10 个片段（原来 6 个，答案常常恰好排在第 7、第 10）；命中片段所属的合同、手册、证件这类几万字以内的小文档，整篇交给模型通读后回答（`skb.chat.full-text-chars`，累计 4 万字），能回答需要综合全文的问题；几十万字的书仍只给命中片段。回答下方显示「已读全文 · 文件名」。
- **去重与上下文扩展**：精排后去掉内容几乎相同的结果（同一本书的多个副本）；组装参考资料时把命中片段在同一章节里的前后片段接上（每条最多 1500 字），跨片段的列举项、段落不再只给模型一半。
- **精排**（`retrieval/RerankClient`）：RRF 前 20 个片段交给交叉编码器 bge-reranker-v2-m3 逐对打分并重排，相关性过滤改用精排分数。余弦相似度只反映话题像不像，分不清"有相关章节但没有答案"；精排后无答案题拒答 7/19 → 14/19，问答可用 114/118 → 117/118，单次检索总耗时约 2 秒（M2 Pro，MPS）。
- **查询改写**（`retrieval/QueryRewriter`）：检索前让 DeepSeek 把口语问题改写成文档术语（如「10岁买游戏装备算数吗」→「限制民事行为能力人 民事法律行为效力」），多轮时补全指代；检索文本为「原问题 + 改写结果」。首字延迟增加约 1～2 秒，改写失败或超过 10 秒时退回原问题。
- **反馈与问答分析**：每个回答下方可以点「有用 / 没用」，没用时选原因（没找到、答错了、不完整、引用不对）并补充说明。「问答分析」页统计最近的回答数、拒答率、差评、核对发现问题、精排未生效、慢（出字超过 15 秒）的回答，以及查询改写、检索、首字耗时的中位数和 P90；回答明细可展开查看检索查询、参考资料及分数、核对结果，确认是检索问题的点「加入评测题」，以后用 `python3 scripts/eval_retrieval.py --kb-id 12 --csv http://localhost:8080/api/knowledge-bases/12/eval-cases.csv` 回归。
- **汇总类问题**：「哪些行为会被直接解除劳动合同」「金瓶梅里几位妻妾分别长什么样」这类答案分散在多处的问题，一次检索覆盖不全。检索前先判断问题类型（与查询改写并行，不增加普通问题的耗时），汇总类问题拆成 2～6 个子问题并行检索，轮流从各路取片段合并（最多 12 个），用更大的参考资料预算让模型逐项整理；回答下方显示「多步检索 · N 个子问题」，悬停可看子问题。题集在 `scripts/eval/aggregate_*.csv`，评测脚本加 `--plan off` 对比不拆分的效果。
- **多轮对话**：有对话历史时，查询改写先判断最新问题是否依赖上文：追问（「那病假呢」「它的作者是谁」）补全成完整问题，纠正（「不一定是康昱的」「不是崇祯本」）去掉被否定的内容，换话题时不带入之前的人名书名。多轮题集在 `scripts/eval/multiturn_*.csv`（`history` 列为前几轮问答），`/search` 请求体可传 `history`，响应头 `X-Search-Query` 是改写后的检索文本。
- **检索评测**：`scripts/eval_retrieval.py` 按题集统计 Hit@k、MRR、问答可用率和无答案题拒答率，题集与基线在 `scripts/eval/`。
- **答案级评测**：`scripts/eval_answer.py` 逐题真实调用问答接口，由 DeepSeek 当裁判检查要点是否答全、有没有资料之外的内容、`[n]` 引用是否正确、无答案题是否拒答；答案要点在 `scripts/eval/answers/`。当前 109 道有答案题答对 105 道，19 道无答案题全部拒答，116 个回答中 3 个含资料外的内容，未发现引用错误。
- **去重与版本替换**：同一知识库内按 SHA-256 去重；路径（单独上传时为文件名）相同而内容变化的文件替换旧版本。
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
| POST | `/api/knowledge-bases/{id}/search` | 检索调试（不调用 LLM），可用 `folders`、`documentIds` 限定范围 |
| PUT | `/api/conversations/{id}/scope` | 修改对话的检索范围（`{"folders": [...], "documentIds": [...]}`，都为空表示整个知识库） |
| POST | `/api/conversations/{id}/chat` | 问答，SSE 事件：`citations` → `delta`* → `done` → `verification` / `error` |
| PUT | `/api/messages/{id}/feedback` | 评价回答：`{"rating": "UP" \| "DOWN" \| null, "reason": "NOT_FOUND", "note": "..."}` |
| GET | `/api/knowledge-bases/{id}/insights?days=30` | 问答统计和需要关注的回答（差评、拒答、核对有问题、精排未生效、慢） |
| GET/POST | `/api/knowledge-bases/{id}/eval-cases` | 从真实问答中整理的评测题；`eval-cases.csv` 导出为评测脚本的题集格式 |

## 后续路线

- **P2 效果**：~~BM25 关键词检索 + 向量的混合召回（RRF 融合）~~、~~评估测试集~~、~~Reranker 精排~~、~~答案级评测~~、~~按组切分（条文、列举项不切开）~~、~~口语化查询改写~~（已完成）。
- **P3 规模**：~~扫描件 OCR~~（已完成，苹果 Vision）、复杂版面与公式（MinerU / Docling）、目录监听自动同步、~~按文件夹限定检索范围~~（已完成）。
- **P4 进阶**：Agentic RAG、跨文档关系问答、图表理解。
