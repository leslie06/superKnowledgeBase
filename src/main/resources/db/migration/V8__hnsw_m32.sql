-- 向量索引改用 m = 32、ef_construction = 128（pgvector 默认 16、64）。
-- 电子书库导入 5 万个片段后，库里有大量彼此几乎相同的片段（激活码文件、表格行、扫描页识别出的碎片），
-- 在默认参数建出的图里聚成一团，搜索会困在里面：ef_search = 200 时 35 道评测题平均只召回精确结果前 30 名的 83%，
-- 6 道题漏掉了真正最相似的片段（如问"武松打虎"返回的是《黄帝内经》）；单纯 REINDEX 没有改善。
-- 每个节点连更多邻居后，同样 ef_search = 200 下 35 道题全部与精确结果一致。索引大小基本不变，建索引时间约为原来的 2.5 倍。
--
-- 已有大量片段的库，可以先在线建好新索引再重启，迁移只做删除和改名，不会长时间锁表：
--   CREATE INDEX CONCURRENTLY idx_chunk_embedding_m32 ON chunk USING hnsw (embedding vector_cosine_ops)
--       WITH (m = 32, ef_construction = 128);
-- （建议同时设置 maintenance_work_mem = 1GB；docker 中的 PostgreSQL 共享内存只有 64MB，要设 max_parallel_maintenance_workers = 0）

SET LOCAL maintenance_work_mem = '1GB';
SET LOCAL max_parallel_maintenance_workers = 0;

DROP INDEX IF EXISTS idx_chunk_embedding;
CREATE INDEX IF NOT EXISTS idx_chunk_embedding_m32 ON chunk USING hnsw (embedding vector_cosine_ops)
    WITH (m = 32, ef_construction = 128);
ALTER INDEX idx_chunk_embedding_m32 RENAME TO idx_chunk_embedding;

COMMENT ON INDEX idx_chunk_embedding IS '片段向量的 HNSW 余弦索引（m = 32，ef_construction = 128），查询时 ef_search 见 skb.retrieval.ef-search';
