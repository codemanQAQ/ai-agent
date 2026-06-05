package com.bytedance.ai.graph.session;

import com.bytedance.ai.shared.support.RagJsonCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class JdbcAgentSessionStateRepository implements AgentSessionStateRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RagJsonCodec jsonCodec;

    public JdbcAgentSessionStateRepository(JdbcTemplate jdbcTemplate, RagJsonCodec jsonCodec) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public Optional<AgentSessionState> find(String userId, String conversationId) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(conversationId)) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                        SELECT state_json
                          FROM agent_session_state
                         WHERE user_id = ?
                           AND conversation_id = ?
                        """,
                this::firstState,
                userId,
                conversationId
        );
    }

    @Override
    public void save(AgentSessionState state) {
        if (state == null || !StringUtils.hasText(state.userId()) || !StringUtils.hasText(state.conversationId())) {
            return;
        }
        String payload = jsonCodec.write(state);
        Integer existing = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                          FROM agent_session_state
                         WHERE user_id = ?
                           AND conversation_id = ?
                        """,
                Integer.class,
                state.userId(),
                state.conversationId()
        );
        if (existing != null && existing > 0) {
            jdbcTemplate.update("""
                            UPDATE agent_session_state
                               SET state_json = ?,
                                   updated_at = CURRENT_TIMESTAMP
                             WHERE user_id = ?
                               AND conversation_id = ?
                            """,
                    payload,
                    state.userId(),
                    state.conversationId()
            );
        } else {
            jdbcTemplate.update("""
                            INSERT INTO agent_session_state (user_id, conversation_id, state_json)
                            VALUES (?, ?, ?)
                            """,
                    state.userId(),
                    state.conversationId(),
                    payload
            );
        }
    }

    private Optional<AgentSessionState> firstState(ResultSet resultSet) throws SQLException {
        if (!resultSet.next()) {
            return Optional.empty();
        }
        return Optional.of(jsonCodec.read(resultSet.getString("state_json"), AgentSessionState.class));
    }
}
