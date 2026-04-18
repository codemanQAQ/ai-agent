-- 为 PostgreSQL RAG 关键词检索启用 pg_trgm，并补齐对应 trigram 索引。
-- 用法：
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f scripts/rag/pgsql/enable_pg_trgm.sql

BEGIN;

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_rag_documents_title_trgm
    ON rag_documents USING GIN (LOWER(COALESCE(title, '')) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_rag_chunks_chunk_text_trgm
    ON rag_chunks USING GIN (LOWER(COALESCE(chunk_text, '')) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_rag_chunks_heading_path_text_trgm
    ON rag_chunks USING GIN (LOWER(COALESCE(metadata->>'headingPathText', '')) gin_trgm_ops);

ANALYZE rag_documents;
ANALYZE rag_chunks;

COMMIT;
