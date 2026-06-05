package com.bytedance.ai.graph.session;

import java.util.Optional;

public interface AgentSessionStateRepository {

    Optional<AgentSessionState> find(String userId, String conversationId);

    void save(AgentSessionState state);
}
