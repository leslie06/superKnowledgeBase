CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE knowledge_base (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    -- 向量由哪个模型生成；更换模型后必须重建整个库的向量
    embedding_model VARCHAR(100) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE document (
    id          BIGSERIAL PRIMARY KEY,
    kb_id       BIGINT       NOT NULL REFERENCES knowledge_base (id) ON DELETE CASCADE,
    filename    VARCHAR(500) NOT NULL,
    file_path   VARCHAR(1000) NOT NULL,
    sha256      CHAR(64)     NOT NULL,
    mime_type   VARCHAR(200),
    size_bytes  BIGINT       NOT NULL,
    -- PENDING / PARSING / EMBEDDING / READY / FAILED
    status      VARCHAR(20)  NOT NULL,
    error_msg   TEXT,
    chunk_count INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (kb_id, sha256)
);
CREATE INDEX idx_document_kb ON document (kb_id);

CREATE TABLE chunk (
    id           BIGSERIAL PRIMARY KEY,
    document_id  BIGINT  NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    kb_id        BIGINT  NOT NULL,
    chunk_index  INT     NOT NULL,
    content      TEXT    NOT NULL,
    -- 例如：第三章 请假制度 > 3.2 年假
    heading_path TEXT,
    -- PDF 页码 / PPT 幻灯片序号 / Excel 工作表名
    location     VARCHAR(200),
    embedding    vector(1024) NOT NULL
);
CREATE INDEX idx_chunk_document ON chunk (document_id);
CREATE INDEX idx_chunk_kb ON chunk (kb_id);
CREATE INDEX idx_chunk_embedding ON chunk USING hnsw (embedding vector_cosine_ops);

CREATE TABLE conversation (
    id         BIGSERIAL PRIMARY KEY,
    kb_id      BIGINT       NOT NULL REFERENCES knowledge_base (id) ON DELETE CASCADE,
    title      VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_conversation_kb ON conversation (kb_id);

CREATE TABLE message (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT      NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    role            VARCHAR(20) NOT NULL,
    content         TEXT        NOT NULL,
    citations       JSONB,
    -- 改写后的检索查询与命中分数，便于排查 bad case
    retrieval_trace JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_message_conversation ON message (conversation_id);
