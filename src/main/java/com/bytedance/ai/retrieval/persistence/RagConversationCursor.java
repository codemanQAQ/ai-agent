package com.bytedance.ai.retrieval.persistence;

import java.time.OffsetDateTime;

public record RagConversationCursor(
        OffsetDateTime lastMessageAt,
        Long id
) {
}
