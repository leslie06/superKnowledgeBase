-- 关键词检索（BM25）：词项由应用按中文二字组、英文数字词、条款编号切分后写入，
-- 不依赖 PostgreSQL 的中文分词扩展。存量片段的词项由应用启动时自动回填。

ALTER TABLE chunk ADD COLUMN keyword_tsv tsvector;
ALTER TABLE chunk ADD COLUMN keyword_token_count INT NOT NULL DEFAULT 0;

CREATE INDEX idx_chunk_keyword ON chunk USING gin (keyword_tsv);

COMMENT ON COLUMN chunk.keyword_tsv IS '关键词检索词项（章节路径 + 正文，由 KeywordAnalyzer 切分，含位置用于统计词频），GIN 索引；为空表示待回填';
COMMENT ON COLUMN chunk.keyword_token_count IS '关键词词项总数，即 BM25 的文档长度';
