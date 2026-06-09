package com.bytedance.ai.graph.conversation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAgentConversationRepository implements AgentConversationRepository {

    private static final RowMapper<ConversationMessage> MESSAGE_ROW_MAPPER = (rs, _) -> new ConversationMessage(
            rs.getLong("id"),
            rs.getString("message_id"),
            rs.getLong("conversation_id"),
            rs.getString("role"),
            rs.getString("content"),
            rs.getString("status"),
            rs.getString("correlation_id"),
            rs.getInt("sequence_no"),
            toOffsetDateTime(rs.getTimestamp("created_at"))
    );

    private final JdbcTemplate jdbc;

    public JdbcAgentConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean existsConversation(String userId, String conversationId) {
        Integer count = jdbc.queryForObject(
                """
                        SELECT COUNT(*)
                          FROM agent_conversations
                         WHERE user_id = ?
                           AND conversation_id = ?
                        """,
                Integer.class,
                userId,
                conversationId
        );
        return count != null && count > 0;
    }

    @Override
    @Transactional
    public Long initConversation(String userId, String conversationId) {
        Optional<Long> existing = findConversationInternalId(userId, conversationId);
        if (existing.isPresent()) {
            return existing.get();
        }

        // 用 ON CONFLICT DO NOTHING 做幂等插入：并发同会话两路同时插入时不抛唯一键异常、也不会
        // abort 当前事务（旧实现 catch DuplicateKeyException 后在同一已 abort 的事务里再 SELECT 会
        // 连环失败 → GUIDE_GRAPH_NODE_FAILED，即 #1/#22 竞态崩溃的根因）。冲突目标用现有
        // 全局唯一键 conversation_id。
        KeyHolder keyHolder = new GeneratedKeyHolder();
        int inserted = jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    """
                            INSERT INTO agent_conversations (conversation_id, user_id, status, message_count)
                            VALUES (?, ?, 'ACTIVE', 0)
                            ON CONFLICT (conversation_id) DO NOTHING
                            """,
                    new String[]{"id"}
            );
            statement.setString(1, conversationId);
            statement.setString(2, userId);
            return statement;
        }, keyHolder);
        if (inserted > 0) {
            Number key = keyHolder.getKey();
            if (key != null) {
                return key.longValue();
            }
        }

        // 冲突未插入：先按 (user, conversation) 重查（并发同用户同会话→对方已插入的本人会话行）；
        // 仍无则按 conversation_id 全局重查（跨用户复用同一 conversationId 的情况，#20：返回既有会话而非崩溃）。
        return findConversationInternalId(userId, conversationId)
                .or(() -> findConversationInternalIdByConversation(conversationId))
                .orElseThrow(() -> new IllegalStateException("agent conversation was not initialized"));
    }

    @Override
    public List<ConversationMessage> loadRecentMessages(String userId, String conversationId, int limit) {
        return jdbc.query(
                """
                        SELECT m.*
                          FROM agent_conversations c
                          JOIN agent_conversation_messages m
                            ON m.conversation_id = c.id
                         WHERE c.user_id = ?
                           AND c.conversation_id = ?
                         ORDER BY m.sequence_no DESC
                         LIMIT ?
                        """,
                MESSAGE_ROW_MAPPER,
                userId,
                conversationId,
                Math.max(limit, 0)
        );
    }

    @Override
    @Transactional
    public ConversationMessage saveUserMessage(
            String userId,
            String conversationId,
            String turnId,
            String correlationId,
            String content
    ) {
        return saveMessage(userId, conversationId, turnId, correlationId, "user", content, "SUCCEEDED");
    }

    @Override
    @Transactional
    public ConversationMessage saveAssistantMessage(
            String userId,
            String conversationId,
            String turnId,
            String correlationId,
            String content,
            String status
    ) {
        return saveMessage(userId, conversationId, turnId, correlationId, "assistant", content,
                StringUtils.hasText(status) ? status : "SUCCEEDED");
    }

    private ConversationMessage saveMessage(
            String userId,
            String conversationId,
            String turnId,
            String correlationId,
            String role,
            String content,
            String status
    ) {
        Long internalConversationId = findConversationInternalId(userId, conversationId)
                .orElseGet(() -> initConversation(userId, conversationId));
        // 原子分配序号：用会话行计数器自增并 RETURNING 取值作为 sequence_no。UPDATE 持有该会话行的行锁，
        // 把同一会话的并发写消息串行化，避免旧 “MAX(sequence_no)+1 先读后插” 的竞态（两路同序号 →
        // 触发 uq_rag_messages_conversation_sequence 唯一键冲突 → 事务 abort → 节点失败，即 #1 残余崩溃）。
        Integer sequenceNo = jdbc.queryForObject(
                """
                        UPDATE agent_conversations
                           SET message_count = message_count + 1,
                               last_message_at = CURRENT_TIMESTAMP,
                               updated_at = CURRENT_TIMESTAMP
                         WHERE id = ?
                     RETURNING message_count
                        """,
                Integer.class,
                internalConversationId
        );
        String messageId = StringUtils.hasText(turnId)
                ? turnId + ":" + role + ":" + UUID.randomUUID()
                : UUID.randomUUID().toString();

        jdbc.update(
                """
                        INSERT INTO agent_conversation_messages (
                            message_id, conversation_id, role, content, status, correlation_id, sequence_no
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                messageId,
                internalConversationId,
                role,
                content,
                status,
                correlationId,
                sequenceNo
        );

        return jdbc.query(
                """
                        SELECT *
                          FROM agent_conversation_messages
                         WHERE message_id = ?
                        """,
                MESSAGE_ROW_MAPPER,
                messageId
        ).stream().findFirst().orElseThrow();
    }

    @Override
    @Transactional
    public void createOrUpdateTurn(
            String userId,
            String conversationId,
            String turnId,
            String requestId,
            String status,
            String intent,
            String targetWorkflow
    ) {
        String storedStatus = normalizeTurnStatus(status);
        // 单条 UPSERT 取代“先 exists 再 INSERT/UPDATE”：消除并发同 turn 的 TOCTOU 竞态——
        // 旧写法两路都判不存在 → 双 INSERT → 唯一键冲突 abort 事务 → 后续节点连环失败（#22 填地址轮竞态）。
        // 冲突目标 turn_id（已有唯一索引 agent_turn_turn_id_key）；冲突即按本轮最新状态更新。
        jdbc.update(
                """
                        INSERT INTO agent_turn (
                            turn_id, request_id, conversation_id, user_id, status, intent, target_workflow
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (turn_id) DO UPDATE SET
                            request_id = EXCLUDED.request_id,
                            user_id = EXCLUDED.user_id,
                            conversation_id = EXCLUDED.conversation_id,
                            status = EXCLUDED.status,
                            intent = EXCLUDED.intent,
                            target_workflow = EXCLUDED.target_workflow,
                            completed_at = CASE
                                WHEN EXCLUDED.status IN ('SUCCEEDED', 'FAILED', 'WAITING_CLARIFICATION', 'WAITING_CONFIRMATION')
                                THEN CURRENT_TIMESTAMP
                                ELSE agent_turn.completed_at
                            END
                        """,
                turnId,
                requestId,
                conversationId,
                userId,
                storedStatus,
                intent,
                targetWorkflow
        );
    }

    private String normalizeTurnStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return "PENDING";
        }
        return switch (status) {
            case "SUCCESS" -> "SUCCEEDED";
            default -> status;
        };
    }

    private Optional<Long> findConversationInternalId(String userId, String conversationId) {
        return jdbc.query(
                """
                        SELECT id
                          FROM agent_conversations
                         WHERE user_id = ?
                           AND conversation_id = ?
                        """,
                (rs, _) -> rs.getLong("id"),
                userId,
                conversationId
        ).stream().findFirst();
    }

    /** 仅按 conversation_id 全局查（用于跨用户复用同一 conversationId 时的兜底，避免崩溃）。 */
    private Optional<Long> findConversationInternalIdByConversation(String conversationId) {
        return jdbc.query(
                """
                        SELECT id
                          FROM agent_conversations
                         WHERE conversation_id = ?
                         ORDER BY id
                         LIMIT 1
                        """,
                (rs, _) -> rs.getLong("id"),
                conversationId
        ).stream().findFirst();
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
