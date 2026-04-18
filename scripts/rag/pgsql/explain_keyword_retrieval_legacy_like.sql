-- 检查旧版关键词候选检索的计划，便于和当前 FTS / trigram 路径做性能对比。
-- 用法：
--   psql "$DATABASE_URL" -v search_text='rocketmq async indexing' -v result_limit=20 -f scripts/rag/pgsql/explain_keyword_retrieval_legacy_like.sql

\if :{?search_text}
\else
\set search_text 'rocketmq async indexing'
\endif

\if :{?result_limit}
\else
\set result_limit 20
\endif

\echo search_text = :search_text
\echo result_limit = :result_limit

EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
WITH params AS (
    SELECT LOWER(:'search_text')::text AS search_text
),
tokens AS (
    SELECT token
      FROM regexp_split_to_table((SELECT search_text FROM params), E'[^[:alnum:]_./:-]+') AS token
     WHERE token IS NOT NULL
       AND token <> ''
       AND length(token) >= 2
),
scored AS (
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
           SUM(
               CASE WHEN LOWER(c.chunk_text) LIKE ('%' || replace(replace(replace(t.token, E'\\', E'\\\\'), '%', E'\\%'), '_', E'\\_') || '%') ESCAPE '\' THEN 1 ELSE 0 END
             + CASE WHEN LOWER(COALESCE(d.title, '')) LIKE ('%' || replace(replace(replace(t.token, E'\\', E'\\\\'), '%', E'\\%'), '_', E'\\_') || '%') ESCAPE '\' THEN 1 ELSE 0 END
             + CASE WHEN LOWER(CAST(c.metadata AS text)) LIKE ('%' || replace(replace(replace(t.token, E'\\', E'\\\\'), '%', E'\\%'), '_', E'\\_') || '%') ESCAPE '\' THEN 1 ELSE 0 END
           ) AS candidate_score
      FROM rag_chunks c
      JOIN rag_documents d ON d.id = c.document_id
      JOIN tokens t ON TRUE
     WHERE d.indexed_generation IS NOT NULL
       AND c.index_generation = d.indexed_generation
     GROUP BY c.id, c.document_id, d.title, d.source_type, d.source_uri, d.external_ref, c.chunk_index, c.chunk_text, c.vector_id, c.metadata
)
SELECT *
  FROM scored
 WHERE candidate_score > 0
 ORDER BY candidate_score DESC,
          document_id,
          chunk_index
 LIMIT :result_limit;
