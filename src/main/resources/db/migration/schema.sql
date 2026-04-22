-- Java 侧自管理的用户账号表（Sa-Token 认证，非 Auth.js OAuth 用户）
CREATE TABLE IF NOT EXISTS user_accounts (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(255),
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    roles         TEXT         NOT NULL DEFAULT '',
    permissions   TEXT         NOT NULL DEFAULT ''
);

-- 默认种子账号（已存在则跳过）
-- admin / Admin@123456
-- alice / Alice@123456
-- auditor / Audit@123456
INSERT INTO user_accounts (username, password_hash, display_name, enabled, roles, permissions)
VALUES ('admin',   'ad89b64d66caa8e30e5d5ce4a9763f4ecc205814c412175f3e2c50027471426d', 'Admin',   TRUE, 'admin',   'user:profile:read,user:center:read,user:center:manage'),
       ('alice',   'b02bb998ecc1616148b9b4ba0405dbd4c224acd1bac059d59f0a07b3b1a68400', 'Alice',   TRUE, 'user',    'user:profile:read'),
       ('auditor', 'ccabaaba054fb98905b5b9ee47174f57cb6088e04b1526f08b872dc06eaa6bb9', 'Auditor', TRUE, 'auditor', 'user:profile:read,user:center:read')
ON CONFLICT (username) DO NOTHING;

-- RAG 文档表：保存原始文档、索引状态与错误信息
CREATE TABLE IF NOT EXISTS rag_documents (
    id             BIGSERIAL PRIMARY KEY,
    source_type    VARCHAR(32)   NOT NULL,
    source_uri     TEXT,
    external_ref   VARCHAR(255),
    title          VARCHAR(512),
    content        TEXT          NOT NULL,
    content_sha256 CHAR(64)      NOT NULL,
    indexed_generation BIGINT,
    status         VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    chunk_count    INTEGER       NOT NULL DEFAULT 0,
    attempt_count  INTEGER       NOT NULL DEFAULT 0,
    metadata       JSONB         NOT NULL DEFAULT '{}'::jsonb,
    last_error     TEXT,
    last_attempted_at TIMESTAMPTZ(6),
    indexed_at     TIMESTAMPTZ(6),
    created_at     TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
    CONSTRAINT rag_documents_status_chk
        CHECK (status IN ('PENDING', 'PROCESSING', 'INDEXED', 'FAILED', 'DELETING'))
);

ALTER TABLE rag_documents
    ADD COLUMN IF NOT EXISTS attempt_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE rag_documents
    ADD COLUMN IF NOT EXISTS last_attempted_at TIMESTAMPTZ(6);
ALTER TABLE rag_documents
    ADD COLUMN IF NOT EXISTS indexed_generation BIGINT;
ALTER TABLE rag_documents
    ADD COLUMN IF NOT EXISTS metadata JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE rag_documents
    ALTER COLUMN metadata TYPE JSONB
    USING CASE
        WHEN metadata IS NULL THEN '{}'::jsonb
        WHEN trim(metadata::text) = '' THEN '{}'::jsonb
        ELSE metadata::jsonb
    END;
ALTER TABLE rag_documents
    ALTER COLUMN metadata SET DEFAULT '{}'::jsonb;

CREATE INDEX IF NOT EXISTS idx_rag_documents_status ON rag_documents(status);
CREATE INDEX IF NOT EXISTS idx_rag_documents_source_type ON rag_documents(source_type);
CREATE INDEX IF NOT EXISTS idx_rag_documents_external_ref ON rag_documents(external_ref);
CREATE INDEX IF NOT EXISTS idx_rag_documents_source_uri ON rag_documents(source_uri);
CREATE INDEX IF NOT EXISTS idx_rag_documents_indexed_generation ON rag_documents(indexed_generation);
CREATE INDEX IF NOT EXISTS idx_rag_documents_title_fts
    ON rag_documents USING GIN (to_tsvector('simple', COALESCE(title, '')));

-- RAG 离线索引作业表：记录文档版本级别的任务状态与阶段推进
CREATE TABLE IF NOT EXISTS rag_index_jobs (
    id               BIGSERIAL PRIMARY KEY,
    document_id      BIGINT         NOT NULL,
    content_sha256   CHAR(64)       NOT NULL,
    status           VARCHAR(16)    NOT NULL DEFAULT 'QUEUED',
    stage            VARCHAR(32)    NOT NULL DEFAULT 'QUEUED',
    version          BIGINT         NOT NULL DEFAULT 0,
    last_event       VARCHAR(64),
    attempt_count    INTEGER        NOT NULL DEFAULT 0,
    target_generation BIGINT,
    message_id       VARCHAR(128),
    last_error       TEXT,
    started_at       TIMESTAMPTZ(6),
    finished_at      TIMESTAMPTZ(6),
    created_at       TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
    CONSTRAINT uq_rag_index_jobs_document_sha UNIQUE (document_id, content_sha256),
    CONSTRAINT rag_index_jobs_status_chk
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    CONSTRAINT rag_index_jobs_stage_chk
        CHECK (stage IN ('QUEUED', 'DISPATCHING', 'PREPARING', 'CHUNKING', 'SAVE_CHUNKS',
                         'VECTOR_INDEXING', 'COMMIT_INDEX', 'COMPLETED', 'SKIPPED'))
);

ALTER TABLE rag_index_jobs
    DROP CONSTRAINT IF EXISTS rag_index_jobs_document_id_fkey;
ALTER TABLE rag_index_jobs
    DROP CONSTRAINT IF EXISTS fk_rag_index_jobs_document;
ALTER TABLE rag_index_jobs
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE rag_index_jobs
    ADD COLUMN IF NOT EXISTS last_event VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_rag_index_jobs_status ON rag_index_jobs(status);
CREATE INDEX IF NOT EXISTS idx_rag_index_jobs_stage ON rag_index_jobs(stage);
CREATE INDEX IF NOT EXISTS idx_rag_index_jobs_document_id ON rag_index_jobs(document_id);
CREATE INDEX IF NOT EXISTS idx_rag_index_jobs_document_sha_version
    ON rag_index_jobs(document_id, content_sha256, version);

-- RAG 索引状态转移审计表：记录每次工作流跃迁的来源、目标、触发者与失败原因
CREATE TABLE IF NOT EXISTS rag_index_job_transitions (
    id              BIGSERIAL PRIMARY KEY,
    document_id     BIGINT         NOT NULL,
    job_id          BIGINT,
    outbox_id       BIGINT,
    content_sha256  CHAR(64)       NOT NULL,
    from_state      VARCHAR(32),
    to_state        VARCHAR(32)    NOT NULL,
    event           VARCHAR(64)    NOT NULL,
    trigger_type    VARCHAR(32)    NOT NULL,
    triggered_by    VARCHAR(255),
    success         BOOLEAN        NOT NULL DEFAULT TRUE,
    failure_reason  VARCHAR(64),
    error_message   TEXT,
    message_id      VARCHAR(128),
    metadata        JSONB          NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ(6) NOT NULL DEFAULT now()
);

ALTER TABLE rag_index_job_transitions
    ADD COLUMN IF NOT EXISTS job_id BIGINT;
ALTER TABLE rag_index_job_transitions
    ADD COLUMN IF NOT EXISTS outbox_id BIGINT;
ALTER TABLE rag_index_job_transitions
    ADD COLUMN IF NOT EXISTS metadata JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE rag_index_job_transitions
    ALTER COLUMN metadata TYPE JSONB
    USING CASE
        WHEN metadata IS NULL THEN '{}'::jsonb
        WHEN trim(metadata::text) = '' THEN '{}'::jsonb
        ELSE metadata::jsonb
    END;
ALTER TABLE rag_index_job_transitions
    ALTER COLUMN metadata SET DEFAULT '{}'::jsonb;

CREATE INDEX IF NOT EXISTS idx_rag_index_job_transitions_document_created
    ON rag_index_job_transitions(document_id, created_at);
CREATE INDEX IF NOT EXISTS idx_rag_index_job_transitions_document_sha_created
    ON rag_index_job_transitions(document_id, content_sha256, created_at);

-- RAG 索引 Outbox：确保文档入库与消息投递脱钩，但仍可可靠补发
CREATE TABLE IF NOT EXISTS rag_index_outbox (
    id               BIGSERIAL PRIMARY KEY,
    document_id      BIGINT         NOT NULL,
    content_sha256   CHAR(64)       NOT NULL,
    event_type       VARCHAR(32)    NOT NULL,
    status           VARCHAR(16)    NOT NULL DEFAULT 'NEW',
    attempt_count    INTEGER        NOT NULL DEFAULT 0,
    message_id       VARCHAR(128),
    last_error       TEXT,
    next_attempt_at  TIMESTAMPTZ(6),
    dispatched_at    TIMESTAMPTZ(6),
    consumed_at      TIMESTAMPTZ(6),
    created_at       TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
    CONSTRAINT uq_rag_index_outbox_document_sha_event UNIQUE (document_id, content_sha256, event_type),
    CONSTRAINT rag_index_outbox_status_chk
        CHECK (status IN ('NEW', 'SENDING', 'SENT', 'FAILED'))
);

ALTER TABLE rag_index_outbox
    DROP CONSTRAINT IF EXISTS rag_index_outbox_document_id_fkey;
ALTER TABLE rag_index_outbox
    DROP CONSTRAINT IF EXISTS fk_rag_index_outbox_document;
ALTER TABLE rag_index_outbox
    ADD COLUMN IF NOT EXISTS message_id VARCHAR(128);
ALTER TABLE rag_index_outbox
    ADD COLUMN IF NOT EXISTS consumed_at TIMESTAMPTZ(6);

CREATE INDEX IF NOT EXISTS idx_rag_index_outbox_status_next_attempt
    ON rag_index_outbox(status, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_rag_index_outbox_document_id
    ON rag_index_outbox(document_id);
CREATE INDEX IF NOT EXISTS idx_rag_index_outbox_message_id
    ON rag_index_outbox(message_id);

-- 离线索引消息失败审计：记录无法进入正常工作流的消息异常，尤其是反序列化失败
CREATE TABLE IF NOT EXISTS rag_index_message_failures (
    id                BIGSERIAL PRIMARY KEY,
    message_id        VARCHAR(128) NOT NULL,
    topic             VARCHAR(255) NOT NULL,
    delivery_attempt  INTEGER      NOT NULL,
    failure_type      VARCHAR(32)  NOT NULL,
    error_message     TEXT,
    payload_base64    TEXT,
    payload_preview   TEXT,
    properties_json   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at        TIMESTAMPTZ(6) NOT NULL DEFAULT now()
);

ALTER TABLE rag_index_message_failures
    ADD COLUMN IF NOT EXISTS properties_json JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE rag_index_message_failures
    ALTER COLUMN properties_json TYPE JSONB
    USING CASE
        WHEN properties_json IS NULL THEN '{}'::jsonb
        WHEN trim(properties_json::text) = '' THEN '{}'::jsonb
        ELSE properties_json::jsonb
    END;
ALTER TABLE rag_index_message_failures
    ALTER COLUMN properties_json SET DEFAULT '{}'::jsonb;

CREATE INDEX IF NOT EXISTS idx_rag_index_message_failures_message_created
    ON rag_index_message_failures(message_id, created_at);

-- RAG 切片表：保存切分后的上下文文本以及与 Milvus 的映射关系
CREATE TABLE IF NOT EXISTS rag_chunks (
    id          BIGSERIAL PRIMARY KEY,
    document_id BIGINT         NOT NULL,
    index_generation BIGINT    NOT NULL DEFAULT 1,
    chunk_index INTEGER        NOT NULL,
    chunk_text  TEXT           NOT NULL,
    chunk_hash  CHAR(64)       NOT NULL,
    char_count  INTEGER        NOT NULL DEFAULT 0,
    token_count INTEGER,
    vector_id   VARCHAR(128),
    metadata    JSONB          NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ(6) NOT NULL DEFAULT now()
);

ALTER TABLE rag_chunks
    DROP CONSTRAINT IF EXISTS rag_chunks_document_id_fkey;
ALTER TABLE rag_chunks
    DROP CONSTRAINT IF EXISTS fk_rag_chunks_document;

ALTER TABLE rag_chunks
    ADD COLUMN IF NOT EXISTS index_generation BIGINT NOT NULL DEFAULT 1;
ALTER TABLE rag_chunks
    ADD COLUMN IF NOT EXISTS metadata JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE rag_chunks
    ALTER COLUMN metadata TYPE JSONB
    USING CASE
        WHEN metadata IS NULL THEN '{}'::jsonb
        WHEN trim(metadata::text) = '' THEN '{}'::jsonb
        ELSE metadata::jsonb
    END;
ALTER TABLE rag_chunks
    ALTER COLUMN metadata SET DEFAULT '{}'::jsonb;
UPDATE rag_chunks
   SET index_generation = 1
 WHERE index_generation IS NULL;
ALTER TABLE rag_chunks
    DROP CONSTRAINT IF EXISTS uq_rag_chunks_document_chunk;
CREATE UNIQUE INDEX IF NOT EXISTS idx_rag_chunks_document_generation_chunk_unique
    ON rag_chunks(document_id, index_generation, chunk_index);
UPDATE rag_documents d
   SET indexed_generation = 1
 WHERE indexed_generation IS NULL
   AND EXISTS (
        SELECT 1
          FROM rag_chunks c
         WHERE c.document_id = d.id
   );

CREATE INDEX IF NOT EXISTS idx_rag_chunks_document_id ON rag_chunks(document_id);
CREATE INDEX IF NOT EXISTS idx_rag_chunks_document_generation ON rag_chunks(document_id, index_generation, chunk_index);
CREATE INDEX IF NOT EXISTS idx_rag_chunks_vector_id ON rag_chunks(vector_id);
CREATE INDEX IF NOT EXISTS idx_rag_chunks_chunk_hash ON rag_chunks(chunk_hash);
CREATE INDEX IF NOT EXISTS idx_rag_chunks_chunk_text_fts
    ON rag_chunks USING GIN (to_tsvector('simple', COALESCE(chunk_text, '')));
CREATE INDEX IF NOT EXISTS idx_rag_chunks_heading_path_text_fts
    ON rag_chunks USING GIN (to_tsvector('simple', COALESCE(metadata->>'headingPathText', '')));
-- trigram 扩展与相关索引通过 scripts/rag/pgsql/enable_pg_trgm.sql 手工启用，
-- 避免 Spring Boot 的 schema 初始化阶段因为数据库扩展权限或 SQL 分隔规则启动失败。

-- Embedding 缓存表：按 chunk hash + embedding 模型缓存向量，避免重复调用 embedding 模型
CREATE TABLE IF NOT EXISTS rag_embedding_cache (
    chunk_hash          CHAR(64)       NOT NULL,
    embedding_model     VARCHAR(255)   NOT NULL,
    embedding_dimension INTEGER        NOT NULL,
    embedding_json      TEXT           NOT NULL,
    created_at          TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
    PRIMARY KEY (chunk_hash, embedding_model, embedding_dimension)
);

CREATE INDEX IF NOT EXISTS idx_rag_embedding_cache_model
    ON rag_embedding_cache(embedding_model, embedding_dimension);
