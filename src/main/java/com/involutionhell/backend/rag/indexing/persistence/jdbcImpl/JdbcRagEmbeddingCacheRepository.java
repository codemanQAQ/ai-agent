package com.involutionhell.backend.rag.indexing.persistence.jdbcImpl;

import com.involutionhell.backend.rag.indexing.persistence.RagEmbeddingCacheRepository;
import com.involutionhell.backend.rag.indexing.persistence.RagEmbeddingCacheDraft;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 基于 JDBC 的 embedding 缓存实现。
 */
@Repository
public class JdbcRagEmbeddingCacheRepository implements RagEmbeddingCacheRepository {

    private final JdbcTemplate jdbc;

    public JdbcRagEmbeddingCacheRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, String> findEmbeddingJsonByChunkHashes(List<String> chunkHashes, String embeddingModel, int embeddingDimension) {
        if (chunkHashes == null || chunkHashes.isEmpty()) {
            return Map.of();
        }

        // IN 子句动态占位，保持批量命中缓存时只打一条 SQL。
        String placeholders = String.join(",", Collections.nCopies(chunkHashes.size(), "?"));
        String sql = """
                SELECT chunk_hash, embedding_json
                  FROM rag_embedding_cache
                 WHERE embedding_model = ?
                   AND embedding_dimension = ?
                   AND chunk_hash IN (%s)
                """.formatted(placeholders);

        List<Object> args = new java.util.ArrayList<>();
        args.add(embeddingModel);
        args.add(embeddingDimension);
        args.addAll(chunkHashes);

        return jdbc.query(sql, rs -> {
            Map<String, String> results = new LinkedHashMap<>();
            while (rs.next()) {
                results.put(rs.getString("chunk_hash"), rs.getString("embedding_json"));
            }
            return results;
        }, args.toArray());
    }

    @Override
    public void saveAll(List<RagEmbeddingCacheDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return;
        }

        for (RagEmbeddingCacheDraft draft : drafts) {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(upsertSql(connection));
                statement.setString(1, draft.chunkHash());
                statement.setString(2, draft.embeddingModel());
                statement.setInt(3, draft.embeddingDimension());
                statement.setString(4, draft.embeddingJson());
                if (isPostgreSql(connection)) {
                    statement.setString(5, draft.embeddingJson());
                }
                return statement;
            });
        }
    }

    private String upsertSql(Connection connection) throws java.sql.SQLException {
        if (isPostgreSql(connection)) {
            // 生产环境 PostgreSQL 走 ON CONFLICT，重复 chunk hash 只更新缓存内容。
            return """
                    INSERT INTO rag_embedding_cache (
                        chunk_hash, embedding_model, embedding_dimension, embedding_json
                    ) VALUES (?, ?, ?, ?)
                    ON CONFLICT (chunk_hash, embedding_model, embedding_dimension)
                    DO UPDATE SET embedding_json = ?, updated_at = now()
                    """;
        }
        return """
                -- H2 测试环境没有 ON CONFLICT，退化为 MERGE 语法。
                MERGE INTO rag_embedding_cache (
                    chunk_hash, embedding_model, embedding_dimension, embedding_json
                ) KEY (chunk_hash, embedding_model, embedding_dimension)
                VALUES (?, ?, ?, ?)
                """;
    }

    private boolean isPostgreSql(Connection connection) throws java.sql.SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
    }
}
