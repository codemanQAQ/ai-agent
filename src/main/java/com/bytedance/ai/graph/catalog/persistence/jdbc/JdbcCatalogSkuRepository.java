package com.bytedance.ai.graph.catalog.persistence.jdbc;

import com.bytedance.ai.graph.catalog.persistence.CatalogSkuRecord;
import com.bytedance.ai.graph.catalog.persistence.CatalogSkuRepository;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Spring JDBC 的 catalog_sku 仓储实现。
 *
 * <p>每条 SPU 通常只带 1~10 个 SKU，单行 insert + KeyHolder 即可；
 * 不引入 batchUpdate 避免在 PG jsonb 列与 H2 普通列之间适配复杂度。
 */
@Repository
public class JdbcCatalogSkuRepository implements CatalogSkuRepository {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final JdbcTemplate jdbc;
    private final RagJsonCodec jsonCodec;

    public JdbcCatalogSkuRepository(JdbcTemplate jdbc, RagJsonCodec jsonCodec) {
        this.jdbc = jdbc;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public List<CatalogSkuRecord> saveAll(Long spuId, List<SkuDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        List<CatalogSkuRecord> saved = new ArrayList<>(drafts.size());
        for (SkuDraft draft : drafts) {
            saved.add(insertOne(spuId, draft));
        }
        return saved;
    }

    private CatalogSkuRecord insertOne(Long spuId, SkuDraft draft) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(insertSql(connection), Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, spuId);
            statement.setString(2, draft.skuCode());
            statement.setString(3, jsonCodec.write(draft.specJson() == null ? java.util.Map.of() : draft.specJson()));
            statement.setBigDecimal(4, draft.price());
            statement.setInt(5, draft.stock());
            statement.setString(6, STATUS_ACTIVE);
            return statement;
        }, keyHolder);

        Number id = extractId(keyHolder);
        if (id == null) {
            throw new IllegalStateException("创建 catalog_sku 失败，未返回主键");
        }
        return findById(id.longValue());
    }

    @Override
    public List<CatalogSkuRecord> findBySpuId(Long spuId) {
        return jdbc.query(
                "SELECT * FROM catalog_sku WHERE spu_id = ? ORDER BY id",
                rowMapper(),
                spuId
        );
    }

    private CatalogSkuRecord findById(long id) {
        List<CatalogSkuRecord> results = jdbc.query(
                "SELECT * FROM catalog_sku WHERE id = ?",
                rowMapper(),
                id
        );
        return results.stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("创建 catalog_sku 后查询失败: " + id));
    }

    private String insertSql(Connection connection) throws SQLException {
        if (isPostgreSql(connection)) {
            return """
                    INSERT INTO catalog_sku (
                        spu_id, sku_code, spec_json, price, stock, status
                    ) VALUES (?, ?, CAST(? AS jsonb), ?, ?, ?)
                    """;
        }
        return """
                INSERT INTO catalog_sku (
                    spu_id, sku_code, spec_json, price, stock, status
                ) VALUES (?, ?, ?, ?, ?, ?)
                """;
    }

    private boolean isPostgreSql(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
    }

    private Number extractId(KeyHolder keyHolder) {
        if (keyHolder.getKeys() != null) {
            Object id = keyHolder.getKeys().get("id");
            if (id instanceof Number number) {
                return number;
            }
        }
        return keyHolder.getKey();
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }

    private RowMapper<CatalogSkuRecord> rowMapper() {
        return (rs, rowNum) -> new CatalogSkuRecord(
                rs.getLong("id"),
                rs.getLong("spu_id"),
                rs.getString("sku_code"),
                jsonCodec.readMap(rs.getString("spec_json")),
                rs.getBigDecimal("price"),
                rs.getInt("stock"),
                rs.getString("status"),
                toOffsetDateTime(rs.getTimestamp("created_at")),
                toOffsetDateTime(rs.getTimestamp("updated_at"))
        );
    }
}
