package com.bytedance.ai.graph.conversation;

import java.time.OffsetDateTime;

public record ConversationMessage(
        Long id,
        String messageId,
        Long conversationId,
        String role,
        String content,
        String status,
        String correlationId,
        Integer sequenceNo,
        OffsetDateTime createdAt
) {
}
