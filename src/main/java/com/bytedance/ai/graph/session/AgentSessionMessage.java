package com.bytedance.ai.graph.session;

import com.bytedance.ai.graph.conversation.ConversationMessage;

import java.time.OffsetDateTime;

public record AgentSessionMessage(
        String role,
        String content,
        String status,
        String correlationId,
        Integer sequenceNo,
        OffsetDateTime createdAt
) {

    public static AgentSessionMessage from(ConversationMessage message) {
        return new AgentSessionMessage(
                message.role(),
                message.content(),
                message.status(),
                message.correlationId(),
                message.sequenceNo(),
                message.createdAt()
        );
    }
}
