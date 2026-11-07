package flyingFish.ai.superKnowledgeBase.retrieval;

import flyingFish.ai.superKnowledgeBase.common.PgVectors;
import flyingFish.ai.superKnowledgeBase.common.SkbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 混合检索：向量召回（语义相近）和关键词召回（BM25，擅长编号、型号、专有名词）各取若干候选，
 * 用加权 RRF 按排名融合，再把前若干个交给交叉编码器精排；精确命中问题中编号（条款号、型号等）的片段始终排在最前。
 * <p>
 * 余弦相似度只反映"话题像不像"，无法区分"有相关章节但没有答案"和"真正能回答"，所以启用精排时用精排分数做相关性过滤。
 */
@Service
public class Retriever {

    public enum Mode {HYBRID, VECTOR, KEYWORD}

    private record Hit(long chunkId, double score) {
    }

    /**
     * 交给模型的上下文。
     *
     * @param chunks            按顺序排好的参考资料：先是读全文的小文档（按原文顺序分段），再是其他文档的命中片段（已扩展）
     * @param fullTextDocuments 读了全文的文档的文件名
     */
    public record Context(List<RetrievedChunk> chunks, List<String> fullTextDocuments) {
    }

    private record DocumentSize(Long documentId, long chars) {
    }

    record TextRow(Long chunkId, String headingPath, String location, String content) {
    }

    /** 命中片段所在章节里的相邻片段（含命中片段本身） */
    private record Neighbor(Long hitId, Long chunkId, int chunkIndex, String location, String content) {
    }

    private record ChunkRow(Long chunkId, Long documentId, String filename, String headingPath, String location,
                            String content, double score, boolean exactMatch, String kind) {
    }

    private static final Logger log = LoggerFactory.getLogger(Retriever.class);
    private static final double BM25_K1 = 1.2;
    private static final double BM25_B = 0.75;
    /**
     * 命中超过这个比例片段的查询词不参与关键词召回。这类词（"一个""我们"）idf 很低，对排序几乎没有贡献，
     * 却会让 tf 统计展开大量片段的词表：5 万片段的库里，"租房一个月多钱"中的"一个"命中 2.1 万个片段，关键词召回因此要 2 秒以上。
     */
    private static final double MAX_DF_RATIO = 0.1;
    /** 片段少的库不跳过常见词：命中数低于这个值时展开的代价可以忽略，按比例跳过反而会丢掉小库里的有效词 */
    private static final int MIN_SKIPPED_DF = 1000;
    /**
     * 范围内片段不超过这个数时精确计算距离，否则走 HNSW。精确计算要把每个向量（4KB，存在 TOAST 表里）都读一遍，
     * 5000 个约 100ms；而范围占全库比例很小时，HNSW 边扫描边过滤可能凑不够数量。
     */
    private static final int EXACT_SEARCH_MAX_CHUNKS = 5000;
    /**
     * 关键词召回的前几名一定进入精排候选。关键词一路在 RRF 中权重低（0.25），只被它召回的片段（关键词第 1 名得 0.25/11）
     * 排在所有向量结果（第 30 名得 1/40）之后，进不了精排：如"简历里的求职目标"，简历片段关键词排第 2，
     * 向量相似度却排不进全库前 30。精排候选总数不变，由它们挤掉 RRF 排名最靠后的几个。
     */
    private static final int KEYWORD_RERANK_GUARANTEE = 3;
    /** 含问题中编号的片段最多补进这么多个候选；同一编号在别的文档里被引用（"本法第十七条"）时也会命中 */
    private static final int MAX_EXACT_CANDIDATES = 10;
    /** 每轮补充检索最多补进这么多个新片段 */
    private static final int FOLLOW_UP_MAX_NEW = 8;
    /** 补看前后文时取紧挨着的这么多个片段（约两三万字，三丸药那段在断气那段之前七八页） */
    private static final int SURROUNDING_MAX_CHUNKS = 40;
    /** 按向量相似度粗筛后交给精排的片段数 */
    private static final int SURROUNDING_RERANK = 12;
    /** 精排后留下的片段数 */
    private static final int SURROUNDING_KEEP = 3;
    /**
     * 前后文片段的精排分数下限。比 rerank.min-score（0.05）严：检索结果是按相关性召回的，前后文只是按位置取来的，
     * 大多与问题无关；实测因果链上的几段在 0.85 以上，无关段落多在 0.2 以下
     */
    private static final double SURROUNDING_MIN_SCORE = 0.3;
    /**
     * 与排名更靠前的结果相似度达到这个值的片段视为重复，不再返回。同一本书的多个副本、同一译本的不同扫描版
     * 会让同一段话占掉好几个参考资料名额（"林黛玉进贾府"的前 6 个结果里有 3 对完全相同）；
     * 同一页的相邻片段、格式相近的表格行相似度在 0.5～0.7，不受影响。
     */
    private static final double DUPLICATE_SIMILARITY = 0.85;
    /** 读全文时每段最多这么多字：按章节切开，每段作为一条参考资料，模型可以按 [n] 标注出处 */
    private static final int FULL_TEXT_SECTION_CHARS = 2500;
    private static final Pattern PAGE_LOCATION = Pattern.compile("^第(\\d+)(?:-(\\d+))?页$");

    private final JdbcClient jdbc;
    private final EmbeddingModel embeddingModel;
    private final RerankClient rerankClient;
    private final TransactionTemplate readOnlyTx;
    private final int defaultTopK;
    private final double minScore;
    private final int candidates;
    private final int rrfK;
    private final double keywordWeight;
    private final int efSearch;
    private final boolean rerankEnabled;
    private final int rerankCandidates;
    private final int aggregateTopK;
    private final int subQueryRerankCandidates;
    private final ExecutorService aggregateExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final double rerankMinScore;

    public Retriever(JdbcClient jdbc, EmbeddingModel embeddingModel, RerankClient rerankClient,
                     TransactionTemplate transactionTemplate, SkbProperties properties) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
        this.rerankClient = rerankClient;
        this.readOnlyTx = new TransactionTemplate(transactionTemplate.getTransactionManager());
        this.readOnlyTx.setReadOnly(true);
        this.defaultTopK = properties.retrieval().topK();
        this.minScore = properties.retrieval().minScore();
        this.candidates = properties.retrieval().candidates();
        this.rrfK = properties.retrieval().rrfK();
        this.keywordWeight = properties.retrieval().keywordWeight();
        this.efSearch = properties.retrieval().efSearch();
        this.rerankEnabled = properties.rerank().enabled();
        this.rerankCandidates = properties.rerank().candidates();
        this.aggregateTopK = properties.retrieval().aggregateTopK();
        this.subQueryRerankCandidates = properties.retrieval().subQueryRerankCandidates();
        this.rerankMinScore = properties.rerank().minScore();
    }

    /** 问答用：混合检索后去掉不相关的片段，取前 top-k 个。 */
    public List<RetrievedChunk> retrieve(long kbId, String query, SearchScope scope) {
        return rank(kbId, query, scope, Mode.HYBRID, candidates, rerankEnabled, rerankCandidates).stream()
                .filter(RetrievedChunk::relevant)
                .limit(defaultTopK)
                .toList();
    }

    /**
     * 汇总类问题（见 {@link QueryPlanner}）：原问题和每个子问题各检索一次，只留相关片段，轮流从各路取片段合并，
     * 去重后最多 aggregateTopK 个。轮流取而不是统一按分数排，是为了每个子问题（每个人物、每类情形）都有资料入选：
     * 按分数排时，分数普遍偏高的一路会占满名额。子问题只精排 subQueryRerankCandidates 个，控制总耗时。
     */
    public List<RetrievedChunk> retrieveAggregate(long kbId, String query, List<String> subQueries, SearchScope scope) {
        // 各路并行：精排服务一次只处理一个请求，但向量化和数据库查询可以和其他路的精排重叠
        List<CompletableFuture<List<RetrievedChunk>>> futures = new ArrayList<>();
        futures.add(CompletableFuture.supplyAsync(
                () -> relevant(rank(kbId, query, scope, Mode.HYBRID, candidates, rerankEnabled, rerankCandidates)),
                aggregateExecutor));
        for (String subQuery : subQueries) {
            futures.add(CompletableFuture.supplyAsync(() -> relevant(rank(kbId, subQuery, scope, Mode.HYBRID, candidates,
                    rerankEnabled, subQueryRerankCandidates)), aggregateExecutor));
        }
        List<List<RetrievedChunk>> routes = futures.stream().map(CompletableFuture::join).toList();
        return dropDuplicates(interleave(routes)).stream().limit(aggregateTopK).toList();
    }

    private static List<RetrievedChunk> relevant(List<RetrievedChunk> ranked) {
        return ranked.stream().filter(RetrievedChunk::relevant).toList();
    }

    /** 问答时取的片段数（skb.retrieval.top-k） */
    public int topK() {
        return defaultTopK;
    }

    /**
     * 补充检索（迭代检索的一轮）：新查询并行检索，只留相关片段，轮流合并后去掉已有的片段和内容几乎相同的片段，
     * 最多 FOLLOW_UP_MAX_NEW 个新片段，接在已有资料之后。
     */
    public List<RetrievedChunk> retrieveMore(long kbId, List<String> queries, SearchScope scope,
                                             List<RetrievedChunk> existing) {
        List<CompletableFuture<List<RetrievedChunk>>> futures = queries.stream()
                .map(q -> CompletableFuture.supplyAsync(() -> relevant(rank(kbId, q, scope, Mode.HYBRID, candidates,
                        rerankEnabled, subQueryRerankCandidates)), aggregateExecutor))
                .toList();
        List<RetrievedChunk> found = interleave(futures.stream().map(CompletableFuture::join).toList());
        Set<Long> known = existing.stream().map(RetrievedChunk::chunkId).collect(Collectors.toSet());
        List<RetrievedChunk> merged = new ArrayList<>(existing);
        found.stream().filter(c -> !known.contains(c.chunkId())).forEach(merged::add);
        // 已有片段排在前面，dropDuplicates 保留先出现的，与已有内容重复的新片段会被去掉
        return dropDuplicates(merged).stream().filter(c -> !known.contains(c.chunkId())).limit(FOLLOW_UP_MAX_NEW).toList();
    }

    private record OrderedRow(Long chunkId, int chunkIndex, String headingPath, String location, String content) {
    }

    /**
     * anchor 前文或后文中与问题最相关的几段，按原文顺序。叙事类文本里原因写在事件之前、结果写在之后：
     * 找到"西门庆断气身亡"这段后，死因（潘金莲灌下三丸药、吴神仙诊为"酒色过度"）在之前几十段里。
     * 取紧挨着的 SURROUNDING_MAX_CHUNKS 段，先按向量相似度粗筛出 SURROUNDING_RERANK 段，再交给精排，
     * 只留分数达到 SURROUNDING_MIN_SCORE 的前 SURROUNDING_KEEP 段：直接按距离取五千字时，大部分是无关的对话和别的情节，
     * 离得远一点的原因又够不着。精排 40 段要 6 秒，粗筛后 12 段约 2 秒；只用向量挑选区分度太差（前几名都在 0.52～0.56），
     * 会漏掉三丸药那段（向量排第 9）。
     * 实测前文 40 段中挑出的 3 段正是第七十九回开头、潘金莲灌下三丸药、次日昏晕栽倒，约 2400 字。精排不可用时退回按距离取，累计最多 fallbackChars 字。
     *
     * @param query 按这个问题挑选（有本意时用本意）
     */
    public List<RetrievedChunk> surrounding(RetrievedChunk anchor, boolean before, String query, int fallbackChars) {
        List<OrderedRow> rows = jdbc.sql("""
                        SELECT c.id AS chunk_id, c.chunk_index, c.heading_path, c.location, c.content
                        FROM chunk c JOIN chunk a ON a.id = :anchor AND c.document_id = a.document_id
                        WHERE c.kind = 'TEXT' AND %s
                        ORDER BY c.chunk_index %s
                        LIMIT :limit
                        """.formatted(before ? "c.chunk_index < a.chunk_index" : "c.chunk_index > a.chunk_index",
                        before ? "DESC" : "ASC"))
                .param("anchor", anchor.chunkId())
                .param("limit", SURROUNDING_MAX_CHUNKS)
                .query(OrderedRow.class).list();
        List<RetrievedChunk> result = new ArrayList<>();
        if (rerankEnabled && !rows.isEmpty()) {
            List<OrderedRow> pool = rows;
            if (rows.size() > SURROUNDING_RERANK) {
                List<Long> closest = jdbc.sql("""
                                SELECT id FROM chunk WHERE id IN (:ids)
                                ORDER BY embedding <=> CAST(:vector AS vector) LIMIT :limit
                                """)
                        .param("ids", rows.stream().map(OrderedRow::chunkId).toList())
                        .param("vector", PgVectors.toLiteral(embeddingModel.embed(query)))
                        .param("limit", SURROUNDING_RERANK)
                        .query(Long.class).list();
                // 保持离 anchor 由近到远的顺序，挑完后按位置还原成原文顺序
                pool = rows.stream().filter(r -> closest.contains(r.chunkId())).toList();
            }
            List<OrderedRow> candidates = pool;
            try {
                double[] scores = rerankClient.score(query, candidates.stream()
                        .map(r -> r.headingPath() == null ? r.content() : r.headingPath() + "\n" + r.content()).toList());
                List<Integer> picked = IntStream.range(0, candidates.size()).boxed()
                        .filter(i -> scores[i] >= SURROUNDING_MIN_SCORE)
                        .sorted(Comparator.comparingDouble((Integer i) -> scores[i]).reversed())
                        .limit(SURROUNDING_KEEP)
                        .sorted(before ? Comparator.reverseOrder() : Comparator.naturalOrder())
                        .toList();
                picked.forEach(i -> result.add(surroundingChunk(anchor, candidates.get(i), scores[i])));
                return result;
            } catch (RuntimeException e) {
                log.warn("精排服务不可用，补看前后文按距离取：{}", e.getMessage());
            }
        }
        int used = 0;
        for (OrderedRow row : rows) {
            if (!result.isEmpty() && used + row.content().length() > fallbackChars) {
                break;
            }
            used += row.content().length();
            result.add(surroundingChunk(anchor, row, anchor.rerankScore()));
        }
        if (before) {
            Collections.reverse(result);
        }
        return result;
    }

    private static RetrievedChunk surroundingChunk(RetrievedChunk anchor, OrderedRow row, Double rerankScore) {
        return new RetrievedChunk(row.chunkId(), anchor.documentId(), anchor.filename(), row.headingPath(),
                row.location(), row.content(), anchor.score(), null, null, null, rerankScore, false, true, "TEXT");
    }

    /** 依次取每一路的第 1 个、再依次取第 2 个……，同一片段只保留第一次出现 */
    static List<RetrievedChunk> interleave(List<List<RetrievedChunk>> routes) {
        Map<Long, RetrievedChunk> merged = new LinkedHashMap<>();
        int depth = routes.stream().mapToInt(List::size).max().orElse(0);
        for (int i = 0; i < depth; i++) {
            for (List<RetrievedChunk> route : routes) {
                if (i < route.size()) {
                    merged.putIfAbsent(route.get(i).chunkId(), route.get(i));
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * 不做相关性过滤的原始检索结果，供调试接口查看各路排名和分数。
     *
     * @param rerank 是否精排，null 时按配置；只在 hybrid 模式下生效，单路模式用于对比召回效果
     */
    public List<RetrievedChunk> search(long kbId, String query, SearchScope scope, int topK, Mode mode, Boolean rerank) {
        boolean useRerank = mode == Mode.HYBRID && (rerank == null ? rerankEnabled : rerank);
        return rank(kbId, query, scope, mode, Math.max(candidates, topK), useRerank, rerankCandidates).stream()
                .limit(topK).toList();
    }

    /** @param rerankLimit 送去精排的片段数 */
    private List<RetrievedChunk> rank(long kbId, String query, SearchScope scope, Mode mode, int poolSize,
                                      boolean rerank, int rerankLimit) {
        long start = System.nanoTime();
        List<RetrievedChunk> fused = fuse(kbId, query, scope, mode, poolSize);
        long fusedAt = System.nanoTime();
        // 精排是一次 HTTP 调用，放在数据库事务之外，避免长时间占用连接
        List<RetrievedChunk> result = dropDuplicates(
                rerank && !fused.isEmpty() ? rerank(query, fused, rerankLimit) : fused);
        long end = System.nanoTime();
        log.info("检索耗时 {} ms：召回 {} ms，精排 {} ms，{} 个片段", (end - start) / 1_000_000,
                (fusedAt - start) / 1_000_000, (end - fusedAt) / 1_000_000, result.size());
        return result;
    }

    private List<RetrievedChunk> fuse(long kbId, String query, SearchScope scope, Mode mode, int poolSize) {
        String vector = PgVectors.toLiteral(embeddingModel.embed(query));
        List<String> terms = KeywordAnalyzer.queryTerms(query);
        return readOnlyTx.execute(status -> {
            // JDBC 同一条语句执行 5 次后改用服务端预编译，PG 随后可能换成不看参数值的通用计划：
            // 向量参数 CAST(? AS vector) 无法提前算好，变成对每一行重新解析 1024 个数字再整表排序（5 万片段要 8 秒）。
            // 检索语句都很快，每次按实际参数重新规划的开销可以忽略
            jdbc.sql("SET LOCAL plan_cache_mode = force_custom_plan").update();
            // null 表示不限范围
            List<Long> documentIds = scope.isAll() ? null : scopeDocuments(kbId, scope);
            if (documentIds != null && documentIds.isEmpty()) {
                return List.of();
            }
            List<Hit> vectorHits = mode == Mode.KEYWORD ? List.of() : vectorSearch(kbId, documentIds, vector, poolSize);
            List<Hit> keywordHits = mode == Mode.VECTOR || terms.isEmpty()
                    ? List.of() : keywordSearch(kbId, documentIds, terms, poolSize);

            Map<Long, Integer> vectorRanks = ranks(vectorHits);
            Map<Long, Integer> keywordRanks = ranks(keywordHits);
            Map<Long, Double> keywordScores = keywordHits.stream().collect(Collectors.toMap(Hit::chunkId, Hit::score));
            Map<Long, Double> fused = new HashMap<>();
            vectorRanks.forEach((id, rank) -> fused.merge(id, 1.0 / (rrfK + rank), Double::sum));
            keywordRanks.forEach((id, rank) -> fused.merge(id, keywordWeight / (rrfK + rank), Double::sum));

            // 纯向量模式用于对比单路效果，不做精确命中优先
            Set<String> exactTerms = mode == Mode.VECTOR ? Set.of() : KeywordAnalyzer.exactTerms(terms);
            // 含问题中编号的片段必进候选：精确命中优先只能给已召回的片段排序。改写后检索文本多了"中华人民共和国""条文内容"
            // 这类词，民法典第十七条被挤出了关键词召回的前 30，向量召回也没有它，问"民法典第十七条是什么"就答不上来
            if (!exactTerms.isEmpty()) {
                exactMatchIds(kbId, documentIds, exactTerms).forEach(id -> fused.putIfAbsent(id, 0.0));
            }
            if (fused.isEmpty()) {
                return List.of();
            }
            Map<Long, ChunkRow> rows = loadChunks(fused.keySet(), vector, exactTerms);
            // 问题里写明了"第39条""Q5"这类编号时，包含它的片段几乎一定是用户要找的，直接排到最前
            return rows.values().stream()
                    .sorted(Comparator.comparing(ChunkRow::exactMatch).reversed()
                            .thenComparing(Comparator.comparingDouble((ChunkRow r) -> fused.get(r.chunkId())).reversed())
                            .thenComparing(Comparator.comparingDouble(ChunkRow::score).reversed()))
                    .map(r -> new RetrievedChunk(r.chunkId(), r.documentId(), r.filename(), r.headingPath(),
                            r.location(), r.content(), r.score(), keywordScores.get(r.chunkId()),
                            vectorRanks.get(r.chunkId()), keywordRanks.get(r.chunkId()), null, r.exactMatch(),
                            r.score() >= minScore || r.exactMatch(), r.kind()))
                    .toList();
        });
    }

    /**
     * 交叉编码器精排：把问题和片段放在一起逐对打分，能区分"试用期"和"转正后"这类相近规则，
     * 也能识别"有相关章节但没有答案"的片段。精排范围之外的片段保持 RRF 顺序，只有精确命中的仍视为相关。
     * 服务不可用时退回 RRF 排序和余弦阈值，问答不受影响。
     */
    private List<RetrievedChunk> rerank(String query, List<RetrievedChunk> fused, int rerankLimit) {
        List<RetrievedChunk> head = rerankPool(fused, rerankLimit, KEYWORD_RERANK_GUARANTEE);
        double[] scores;
        try {
            scores = rerankClient.score(query, head.stream().map(Retriever::rerankText).toList());
        } catch (RuntimeException e) {
            log.warn("精排失败，退回 RRF 排序：{}", e.getMessage());
            return fused;
        }
        List<RetrievedChunk> reranked = new ArrayList<>(fused.size());
        for (int i = 0; i < head.size(); i++) {
            RetrievedChunk c = head.get(i);
            reranked.add(withRerank(c, scores[i], scores[i] >= rerankMinScore || c.exactMatch()));
        }
        reranked.sort(Comparator.comparing(RetrievedChunk::exactMatch).reversed()
                .thenComparing(Comparator.comparingDouble(RetrievedChunk::rerankScore).reversed()));
        for (RetrievedChunk c : fused) {
            if (!head.contains(c)) {
                reranked.add(withRerank(c, null, c.exactMatch()));
            }
        }
        return reranked;
    }

    /** 按排名依次保留，与已保留片段内容几乎相同的跳过 */
    static List<RetrievedChunk> dropDuplicates(List<RetrievedChunk> ranked) {
        List<RetrievedChunk> kept = new ArrayList<>();
        List<Set<String>> keptGrams = new ArrayList<>();
        for (RetrievedChunk c : ranked) {
            Set<String> grams = trigrams(c.content());
            if (keptGrams.stream().noneMatch(g -> similarity(g, grams) >= DUPLICATE_SIMILARITY)) {
                kept.add(c);
                keptGrams.add(grams);
            }
        }
        return kept;
    }

    /** 字符三元组的交集占较短一方的比例；用较短一方，部分副本（只截取了原书一部分）切出的片段也能识别 */
    static double similarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = smaller == a ? b : a;
        long common = smaller.stream().filter(larger::contains).count();
        return (double) common / smaller.size();
    }

    static Set<String> trigrams(String text) {
        String compact = text.replaceAll("\\s+", "");
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + 3 <= compact.length(); i++) {
            grams.add(compact.substring(i, i + 3));
        }
        return grams;
    }

    /**
     * 组装交给模型的上下文。命中片段所属的小文档整篇交给模型：按排名依次取文档，全文字数累计不超过 fullTextChars 的
     * 读全文，其余文档（几十万字的书）仍是命中片段加相邻片段（{@link #expand}）。
     * 只给命中的几段时，答案常常恰好在第 7、第 10 段；合同、手册这类几万字的文档，读完全文才能回答"押金和违约金一共多少"
     * "哪些条款对承租人不利"这类要综合多处的问题。fullTextChars 为 0 时不读全文。
     */
    public Context context(List<RetrievedChunk> hits, int expandChars, int fullTextChars) {
        if (hits.isEmpty() || fullTextChars <= 0) {
            return new Context(expand(hits, expandChars), List.of());
        }
        List<Long> documentIds = hits.stream().map(RetrievedChunk::documentId).distinct().toList();
        Map<Long, Long> sizes = jdbc.sql("""
                        SELECT document_id, sum(length(content)) AS chars FROM chunk
                        WHERE kind = 'TEXT' AND document_id IN (:ids) GROUP BY document_id
                        """)
                .param("ids", documentIds)
                .query(DocumentSize.class).list().stream()
                .collect(Collectors.toMap(DocumentSize::documentId, DocumentSize::chars));
        Set<Long> fullText = new LinkedHashSet<>();
        long used = 0;
        for (Long id : documentIds) {
            long size = sizes.getOrDefault(id, Long.MAX_VALUE);
            if (used + size <= fullTextChars) {
                fullText.add(id);
                used += size;
            }
        }
        List<RetrievedChunk> chunks = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Long id : fullText) {
            RetrievedChunk best = hits.stream().filter(h -> h.documentId().equals(id)).findFirst().orElseThrow();
            chunks.addAll(fullTextSections(best));
            names.add(best.filename());
        }
        chunks.addAll(expand(hits.stream().filter(h -> !fullText.contains(h.documentId())).toList(), expandChars));
        return new Context(chunks, names);
    }

    /** 文档全文按原文顺序分段：章节变化或超过 FULL_TEXT_SECTION_CHARS 时另起一段；分数沿用该文档排名最前的命中片段 */
    private List<RetrievedChunk> fullTextSections(RetrievedChunk best) {
        List<TextRow> rows = jdbc.sql("""
                        SELECT id AS chunk_id, heading_path, location, content FROM chunk
                        WHERE document_id = ? AND kind = 'TEXT' ORDER BY chunk_index
                        """)
                .param(best.documentId())
                .query(TextRow.class).list();
        return sections(rows, FULL_TEXT_SECTION_CHARS).stream().map(section -> new RetrievedChunk(section.getFirst().chunkId(), best.documentId(),
                best.filename(), section.getFirst().headingPath(),
                mergeLocations(section.getFirst().location(), section.getLast().location()),
                section.stream().map(TextRow::content).collect(Collectors.joining("\n")), best.score(), null, null, null,
                best.rerankScore(), false, true, "TEXT")).toList();
    }

    /** 按原文顺序分段：章节变化或累计超过 maxChars 时另起一段 */
    static List<List<TextRow>> sections(List<TextRow> rows, int maxChars) {
        List<List<TextRow>> sections = new ArrayList<>();
        int length = 0;
        for (TextRow row : rows) {
            List<TextRow> current = sections.isEmpty() ? null : sections.getLast();
            if (current == null || !Objects.equals(current.getFirst().headingPath(), row.headingPath())
                    || length + row.content().length() > maxChars) {
                current = new ArrayList<>();
                sections.add(current);
                length = 0;
            }
            current.add(row);
            length += row.content().length();
        }
        return sections;
    }

    /**
     * 上下文扩展：把命中片段在同一文档、同一章节里的前后片段接上，每条最多 maxChars 字（先接后一个，再接前一个）。
     * 片段按 600 字左右切分，答案常常跨片段：列举项的后半段、跨页的段落、"有下列情形之一的"之后的条目。
     * 相邻片段本身也是命中结果时并成一条，不重复占用参考资料名额。返回的片段保留命中片段的 id 和分数，内容换成扩展后的文本。
     */
    public List<RetrievedChunk> expand(List<RetrievedChunk> hits, int maxChars) {
        if (maxChars <= 0 || hits.isEmpty()) {
            return hits;
        }
        Map<Long, Map<Integer, Neighbor>> around = jdbc.sql("""
                        SELECT h.id AS hit_id, n.id AS chunk_id, n.chunk_index, n.location, n.content
                        FROM chunk h
                        JOIN chunk n ON n.document_id = h.document_id
                                    AND n.chunk_index BETWEEN h.chunk_index - 1 AND h.chunk_index + 1
                                    AND n.heading_path IS NOT DISTINCT FROM h.heading_path
                        WHERE h.id IN (:ids)
                        """)
                .param("ids", hits.stream().map(RetrievedChunk::chunkId).toList())
                .query(Neighbor.class)
                .list().stream()
                .collect(Collectors.groupingBy(Neighbor::hitId,
                        Collectors.toMap(Neighbor::chunkIndex, Function.identity())));
        Set<Long> covered = new HashSet<>();
        List<RetrievedChunk> expanded = new ArrayList<>();
        for (RetrievedChunk hit : hits) {
            if (!covered.add(hit.chunkId())) {
                continue;
            }
            Map<Integer, Neighbor> section = around.getOrDefault(hit.chunkId(), Map.of());
            Neighbor self = section.values().stream().filter(n -> n.chunkId().equals(hit.chunkId())).findFirst().orElse(null);
            if (self == null) {
                expanded.add(hit);
                continue;
            }
            Deque<Neighbor> parts = new ArrayDeque<>(List.of(self));
            int used = self.content().length();
            Neighbor next = section.get(self.chunkIndex() + 1);
            if (next != null && !covered.contains(next.chunkId()) && used + next.content().length() <= maxChars) {
                parts.addLast(next);
                used += next.content().length();
            }
            Neighbor previous = section.get(self.chunkIndex() - 1);
            if (previous != null && !covered.contains(previous.chunkId())
                    && used + previous.content().length() <= maxChars) {
                parts.addFirst(previous);
            }
            parts.forEach(n -> covered.add(n.chunkId()));
            String content = parts.stream().map(Neighbor::content).collect(Collectors.joining("\n"));
            String location = mergeLocations(parts.getFirst().location(), parts.getLast().location());
            expanded.add(new RetrievedChunk(hit.chunkId(), hit.documentId(), hit.filename(), hit.headingPath(),
                    location, content, hit.score(), hit.keywordScore(), hit.vectorRank(), hit.keywordRank(),
                    hit.rerankScore(), hit.exactMatch(), hit.relevant(), hit.kind()));
        }
        return expanded;
    }

    /** "第3页" + "第4-5页" → "第3-5页"；不是页码时用第一段的位置 */
    static String mergeLocations(String first, String last) {
        if (first == null || last == null || first.equals(last)) {
            return first != null ? first : last;
        }
        Matcher a = PAGE_LOCATION.matcher(first);
        Matcher b = PAGE_LOCATION.matcher(last);
        if (!a.matches() || !b.matches()) {
            return first;
        }
        int start = Integer.parseInt(a.group(1));
        int end = Integer.parseInt(b.group(2) != null ? b.group(2) : b.group(1));
        return end > start ? "第" + start + "-" + end + "页" : "第" + start + "页";
    }

    /**
     * 精排候选：关键词召回中排名最前的 keywordTop 个正文片段，再按 RRF 顺序补足 size 个。
     * 摘要片段不占这几个名额：它的关键词里有文件名和全书关键词，问"简历里的求职目标"时简历摘要排关键词第 1，
     * 把真正写着"求职目标"的正文片段挤出了必进名额；摘要仍可以按 RRF 顺序进入候选。
     */
    static List<RetrievedChunk> rerankPool(List<RetrievedChunk> fused, int size, int keywordTop) {
        List<RetrievedChunk> pool = new ArrayList<>(fused.stream()
                .filter(c -> c.keywordRank() != null && !RetrievedChunk.SUMMARY.equals(c.kind()))
                .sorted(Comparator.comparingInt(RetrievedChunk::keywordRank))
                .limit(keywordTop)
                .toList());
        for (RetrievedChunk c : fused) {
            if (pool.size() >= size) {
                break;
            }
            if (!pool.contains(c)) {
                pool.add(c);
            }
        }
        return pool;
    }

    /** 章节路径也交给精排模型，"考勤处罚""试用期终止"这类标题本身就是重要的判断依据。 */
    private static String rerankText(RetrievedChunk c) {
        return c.headingPath() == null ? c.content() : c.headingPath() + "\n" + c.content();
    }

    private static RetrievedChunk withRerank(RetrievedChunk c, Double rerankScore, boolean relevant) {
        return new RetrievedChunk(c.chunkId(), c.documentId(), c.filename(), c.headingPath(), c.location(),
                c.content(), c.score(), c.keywordScore(), c.vectorRank(), c.keywordRank(), rerankScore,
                c.exactMatch(), relevant, c.kind());
    }

    /** 范围内的文档：source_path 以所选文件夹开头，或单独选中的文档；用 starts_with 而不是 LIKE，文件夹名里的 _ % 不会被当成通配符 */
    private List<Long> scopeDocuments(long kbId, SearchScope scope) {
        return jdbc.sql("""
                        SELECT id FROM document
                        WHERE kb_id = :kbId
                          AND (id = ANY (:documentIds)
                               OR EXISTS (SELECT 1 FROM unnest(CAST(:folders AS text[])) AS f(folder)
                                          WHERE starts_with(source_path, f.folder || '/')))
                        """)
                .param("kbId", kbId)
                .param("documentIds", scope.documentIds().toArray(Long[]::new))
                .param("folders", scope.folders().toArray(String[]::new))
                .query(Long.class)
                .list();
    }

    /** 范围条件；documentIds 为 null 时不限范围 */
    private static String scopeCondition(List<Long> documentIds) {
        return documentIds == null ? "" : "AND c.document_id IN (:documentIds)";
    }

    private static JdbcClient.StatementSpec withScope(JdbcClient.StatementSpec spec, List<Long> documentIds) {
        return documentIds == null ? spec : spec.param("documentIds", documentIds);
    }

    /**
     * 执行方式不交给 PG 的代价估算：m = 32 的索引被估得偏贵，5 万片段时 PG 会改成全表精确计算，
     * 要读 22 万个数据页（约 1.8GB），内存紧张时要 1 秒以上；同样的查询走 HNSW 只读 5 千个页，约 40ms。
     * 所以范围内片段多时禁止排序，迫使 PG 走 HNSW；片段少时禁止索引扫描，精确计算。
     */
    private List<Hit> vectorSearch(long kbId, List<Long> documentIds, String vector, int limit) {
        int chunks = withScope(jdbc.sql("""
                        SELECT coalesce(sum(chunk_count), 0) FROM document d
                        WHERE d.kb_id = :kbId %s
                        """.formatted(documentIds == null ? "" : "AND d.id IN (:documentIds)")), documentIds)
                .param("kbId", kbId)
                .query(Integer.class)
                .single();
        jdbc.sql(chunks > EXACT_SEARCH_MAX_CHUNKS ? "SET LOCAL enable_sort = off" : "SET LOCAL enable_indexscan = off")
                .update();
        // 带 kb_id 过滤时让 HNSW 继续扫描，避免存在多个知识库时召回数量不足
        jdbc.sql("SET LOCAL hnsw.iterative_scan = relaxed_order").update();
        // 默认搜索范围 40 太小：库大了以后，HNSW 会困在一团相似的片段（如大量近似的乱码、目录）里，漏掉真正最近的片段。
        // SET 不支持参数绑定，efSearch 是配置里的整数，直接拼接
        jdbc.sql("SET LOCAL hnsw.ef_search = " + efSearch).update();
        List<Hit> hits = withScope(jdbc.sql("""
                        SELECT c.id AS chunk_id, 1 - (c.embedding <=> CAST(:vector AS vector)) AS score
                        FROM chunk c
                        WHERE c.kb_id = :kbId %s
                        ORDER BY c.embedding <=> CAST(:vector AS vector)
                        LIMIT :limit
                        """.formatted(scopeCondition(documentIds))), documentIds)
                .param("vector", vector)
                .param("kbId", kbId)
                .param("limit", limit)
                .query(Hit.class)
                .list();
        // 只针对向量查询，恢复后再执行关键词召回和取片段详情
        jdbc.sql("SET LOCAL enable_sort = on").update();
        jdbc.sql("SET LOCAL enable_indexscan = on").update();
        // relaxed_order 下结果可能略微乱序
        return hits.stream().sorted(Comparator.comparingDouble(Hit::score).reversed()).toList();
    }

    /**
     * BM25：idf = ln(1 + (N - df + 0.5) / (df + 0.5))，
     * 得分 = Σ idf · tf · (k1 + 1) / (tf + k1 · (1 - b + b · 文档长度 / 平均文档长度))。
     * N、df、平均长度都按当前知识库实时统计（限定范围时也按整个知识库统计，词的稀有程度不随范围变化）；
     * tf 取 tsvector 中该词项的位置个数。df 超过 {@link #MAX_DF_RATIO} 的常见词先剔除，再用剩下的词匹配片段。
     */
    private List<Hit> keywordSearch(long kbId, List<Long> documentIds, List<String> terms, int limit) {
        return withScope(jdbc.sql("""
                        WITH stats AS (
                            SELECT count(*) AS n, greatest(avg(keyword_token_count), 1) AS avgdl
                            FROM chunk WHERE kb_id = :kbId
                        ), idf AS (
                            SELECT q.term, ln(1 + (s.n - d.df + 0.5) / (d.df + 0.5)) AS idf
                            FROM unnest(string_to_array(:terms, ' ')) AS q(term)
                            CROSS JOIN stats s
                            CROSS JOIN LATERAL (
                                SELECT count(*) AS df FROM chunk c
                                WHERE c.kb_id = :kbId AND c.keyword_tsv @@ CAST(quote_literal(q.term) AS tsquery)
                            ) d
                            WHERE d.df > 0 AND d.df <= greatest(s.n * :maxDfRatio, :minSkippedDf)
                        ), tf AS (
                            SELECT c.id, c.keyword_token_count AS dl, u.lexeme, coalesce(cardinality(u.positions), 1) AS tf
                            FROM chunk c CROSS JOIN LATERAL unnest(c.keyword_tsv) AS u
                            WHERE c.kb_id = :kbId %s
                              AND c.keyword_tsv @@ (SELECT CAST(string_agg(quote_literal(term), ' | ') AS tsquery) FROM idf)
                              AND u.lexeme IN (SELECT term FROM idf)
                        )
                        SELECT tf.id AS chunk_id,
                               sum(i.idf * tf.tf * (:k1 + 1) / (tf.tf + :k1 * (1 - :b + :b * tf.dl / s.avgdl))) AS score
                        FROM tf JOIN idf i ON i.term = tf.lexeme CROSS JOIN stats s
                        GROUP BY tf.id
                        ORDER BY score DESC
                        LIMIT :limit
                        """.formatted(scopeCondition(documentIds))), documentIds)
                .param("kbId", kbId)
                .param("terms", String.join(" ", terms))
                .param("maxDfRatio", MAX_DF_RATIO)
                .param("minSkippedDf", MIN_SKIPPED_DF)
                .param("k1", BM25_K1)
                .param("b", BM25_B)
                .param("limit", limit)
                .query(Hit.class)
                .list();
    }

    /** 含精确词项（条款号、型号等）的片段，最多 MAX_EXACT_CANDIDATES 个 */
    private List<Long> exactMatchIds(long kbId, List<Long> documentIds, Set<String> exactTerms) {
        return withScope(jdbc.sql("""
                        SELECT c.id FROM chunk c
                        WHERE c.kb_id = :kbId %s AND c.keyword_tsv @@ CAST(:exact AS tsquery)
                        LIMIT :limit
                        """.formatted(scopeCondition(documentIds))), documentIds)
                .param("kbId", kbId)
                .param("exact", KeywordAnalyzer.toTsquery(exactTerms))
                .param("limit", MAX_EXACT_CANDIDATES)
                .query(Long.class)
                .list();
    }

    /** 取片段详情；关键词召回的片段也补算余弦相似度，过滤和展示统一用它。 */
    private Map<Long, ChunkRow> loadChunks(Set<Long> ids, String vector, Set<String> exactTerms) {
        String exactMatch = exactTerms.isEmpty() ? "false" : "c.keyword_tsv @@ CAST(:exact AS tsquery)";
        JdbcClient.StatementSpec spec = jdbc.sql("""
                        SELECT c.id AS chunk_id, c.document_id, d.filename, c.heading_path, c.location, c.content, c.kind,
                               1 - (c.embedding <=> CAST(:vector AS vector)) AS score,
                               coalesce(%s, false) AS exact_match
                        FROM chunk c JOIN document d ON d.id = c.document_id
                        WHERE c.id IN (:ids)
                        """.formatted(exactMatch))
                .param("vector", vector)
                .param("ids", new ArrayList<>(ids));
        if (!exactTerms.isEmpty()) {
            spec = spec.param("exact", KeywordAnalyzer.toTsquery(exactTerms));
        }
        return spec.query(ChunkRow.class).list().stream()
                .collect(Collectors.toMap(ChunkRow::chunkId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    private static Map<Long, Integer> ranks(List<Hit> hits) {
        Map<Long, Integer> ranks = new HashMap<>();
        for (int i = 0; i < hits.size(); i++) {
            ranks.putIfAbsent(hits.get(i).chunkId(), i + 1);
        }
        return ranks;
    }
}
