package com.bytedance.ai.graph.ordermanage.jdbc;

import com.bytedance.ai.graph.ordermanage.MockOrderRecord;
import com.bytedance.ai.graph.ordermanage.MockOrderRepository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcMockOrderRepository implements MockOrderRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcMockOrderRepository(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public MockOrderRecord create(
            String orderNo,
            String userId,
            String conversationId,
            Map<String, Object> items,
            Map<String, Object> address,
            BigDecimal totalAmount
    ) {
        String sql = """
                INSERT INTO public.mock_orders
                (order_no, user_id, conversation_id, items_json, address_json, total_amount, status, created_at)
                VALUES (:orderNo, :userId, :conversationId, :items::jsonb, :address::jsonb, :totalAmount, 'CREATED', NOW())
                RETURNING *
                """;
        return jdbcTemplate.queryForObject(sql, new MapSqlParameterSource()
                .addValue("orderNo", orderNo)
                .addValue("userId", userId)
                .addValue("conversationId", conversationId)
                .addValue("items", toJson(items))
                .addValue("address", toJson(address))
                .addValue("totalAmount", totalAmount), rowMapper());
    }

    @Override
    public Optional<MockOrderRecord> findByOrderNo(String userId, String conversationId, String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return Optional.empty();
        }
        String sql = """
                SELECT *
                FROM public.mock_orders
                WHERE user_id = :userId
                  AND conversation_id = :conversationId
                  AND order_no = :orderNo
                ORDER BY created_at DESC
                LIMIT 1
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("conversationId", conversationId)
                        .addValue("orderNo", orderNo.trim()), rowMapper())
                .stream()
                .findFirst();
    }

    @Override
    public Optional<MockOrderRecord> findLatest(String userId, String conversationId) {
        String sql = """
                SELECT *
                FROM public.mock_orders
                WHERE user_id = :userId
                  AND conversation_id = :conversationId
                ORDER BY created_at DESC
                LIMIT 1
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("conversationId", conversationId), rowMapper())
                .stream()
                .findFirst();
    }

    private RowMapper<MockOrderRecord> rowMapper() {
        return new RowMapper<>() {
            private final TypeReference<Map<String, Object>> mapType = new TypeReference<>() {
            };

            @Override
            public MockOrderRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
                return new MockOrderRecord(
                        rs.getLong("id"),
                        rs.getString("order_no"),
                        rs.getString("user_id"),
                        rs.getString("conversation_id"),
                        fromJson(rs.getString("items_json")),
                        fromJson(rs.getString("address_json")),
                        rs.getBigDecimal("total_amount"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toLocalDateTime()
                );
            }

            private Map<String, Object> fromJson(String json) {
                try {
                    return objectMapper.readValue(json == null || json.isBlank() ? "{}" : json, mapType);
                } catch (Exception exception) {
                    return Map.of();
                }
            }
        };
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            return "{}";
        }
    }
}
