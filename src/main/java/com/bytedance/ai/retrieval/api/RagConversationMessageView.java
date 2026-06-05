package com.bytedance.ai.retrieval.api;

import java.time.OffsetDateTime;

public record RagConversationMessageView(
        String messageId,
        String role,
        String content,
        String status,
        int sequenceNo,
        OffsetDateTime createdAt
) {
}
