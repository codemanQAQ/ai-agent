-- 检查当前生产路径的关键词候选检索 SQL 是否吃到 PostgreSQL 索引。
-- 用法：
--   psql "$DATABASE_URL" -v search_text='rocketmq async indexing' -v result_limit=20 -f scripts/rag/pgsql/explain_keyword_retrieval_current.sql
--
-- 关注计划里是否出现：
--   - idx_rag_chunks_chunk_text_fts
--   - idx_rag_documents_title_fts
--   - idx_rag_chunks_heading_path_text_fts
-- 以及已启用 pg_trgm 时的：
--   - idx_rag_documents_title_trgm
--   - idx_rag_chunks_chunk_text_trgm
--   - idx_rag_chunks_heading_path_text_trgm

\if :{?search_text}
\else
\set search_text 'rocketmq async indexing'
\endif

\if :{?result_limit}
\else
\set result_limit 20
\endif

SELECT CASE WHEN EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm')
            THEN 'true'
            ELSE 'false'
       END AS pg_trgm_enabled
\gset

\echo search_text = :search_text
\echo result_limit = :result_limit
\echo pg_trgm_enabled = :pg_trgm_enabled

\if :pg_trgm_enabled
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
WITH params AS (
    SELECT :'search_text'::text AS search_text,
           LOWER(:'search_text')::text AS search_text_lower,
           ('%' || replace(replace(replace(LOWER(:'search_text'), E'\\', E'\\\\'), '%', E'\\%'), '_', E'\\_') || '%')::text AS like_pattern
)
SELECT c.id AS chunk_id,
       c.document_id,
       d.title,
       d.source_type,
       d.source_uri,
       d.external_ref,
       c.chunk_index,
       c.chunk_text,
       c.vector_id,
       c.metadata,
       ts_rank_cd(
           setweight(to_tsvector('simple', COALESCE(c.metadata->>'headingPathText', '')), 'A')
           || setweight(to_tsvector('simple', COALESCE(d.title, '')), 'B')
           || setweight(to_tsvector('simple', COALESCE(c.chunk_text, '')), 'C'),
           plainto_tsquery('simple', p.search_text)
       ) AS fts_score,
       GREATEST(
           similarity(LOWER(COALESCE(c.metadata->>'headingPathText', '')), p.search_text_lower),
           similarity(LOWER(COALESCE(d.title, '')), p.search_text_lower),
           similarity(LOWER(COALESCE(c.chunk_text, '')), p.search_text_lower)
       ) AS trigram_score
  FROM rag_chunks c
  JOIN rag_documents d ON d.id = c.document_id
 CROSS JOIN params p
 WHERE d.indexed_generation IS NOT NULL
   AND c.index_generation = d.indexed_generation
   AND (
       (
           setweight(to_tsvector('simple', COALESCE(c.metadata->>'headingPathText', '')), 'A')
           || setweight(to_tsvector('simple', COALESCE(d.title, '')), 'B')
           || setweight(to_tsvector('simple', COALESCE(c.chunk_text, '')), 'C')
       ) @@ plainto_tsquery('simple', p.search_text)
       OR LOWER(COALESCE(c.metadata->>'headingPathText', '')) % p.search_text_lower
       OR LOWER(COALESCE(d.title, '')) % p.search_text_lower
       OR LOWER(COALESCE(c.chunk_text, '')) % p.search_text_lower
       OR LOWER(COALESCE(c.metadata->>'headingPathText', '')) LIKE p.like_pattern ESCAPE '\'
       OR LOWER(COALESCE(d.title, '')) LIKE p.like_pattern ESCAPE '\'
       OR LOWER(COALESCE(c.chunk_text, '')) LIKE p.like_pattern ESCAPE '\'
   )
 ORDER BY (fts_score * 10.0 + trigram_score * 3.0) DESC,
          c.document_id,
          c.chunk_index
 LIMIT :result_limit;
\else
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
WITH params AS (
    SELECT :'search_text'::text AS search_text,
           ('%' || replace(replace(replace(LOWER(:'search_text'), E'\\', E'\\\\'), '%', E'\\%'), '_', E'\\_') || '%')::text AS like_pattern
)
SELECT c.id AS chunk_id,
       c.document_id,
       d.title,
       d.source_type,
       d.source_uri,
       d.external_ref,
       c.chunk_index,
       c.chunk_text,
       c.vector_id,
       c.metadata,
       ts_rank_cd(
           setweight(to_tsvector('simple', COALESCE(c.metadata->>'headingPathText', '')), 'A')
           || setweight(to_tsvector('simple', COALESCE(d.title, '')), 'B')
           || setweight(to_tsvector('simple', COALESCE(c.chunk_text, '')), 'C'),
           plainto_tsquery('simple', p.search_text)
       ) AS fts_score,
       0.0 AS trigram_score
  FROM rag_chunks c
  JOIN rag_documents d ON d.id = c.document_id
 CROSS JOIN params p
 WHERE d.indexed_generation IS NOT NULL
   AND c.index_generation = d.indexed_generation
   AND (
       (
           setweight(to_tsvector('simple', COALESCE(c.metadata->>'headingPathText', '')), 'A')
           || setweight(to_tsvector('simple', COALESCE(d.title, '')), 'B')
           || setweight(to_tsvector('simple', COALESCE(c.chunk_text, '')), 'C')
       ) @@ plainto_tsquery('simple', p.search_text)
       OR LOWER(COALESCE(c.metadata->>'headingPathText', '')) LIKE p.like_pattern ESCAPE '\'
       OR LOWER(COALESCE(d.title, '')) LIKE p.like_pattern ESCAPE '\'
       OR LOWER(COALESCE(c.chunk_text, '')) LIKE p.like_pattern ESCAPE '\'
   )
 ORDER BY (fts_score * 10.0 + trigram_score * 3.0) DESC,
          c.document_id,
          c.chunk_index
 LIMIT :result_limit;
\endif
