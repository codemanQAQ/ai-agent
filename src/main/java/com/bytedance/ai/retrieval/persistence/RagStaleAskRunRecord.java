package com.bytedance.ai.retrieval.persistence;

import java.time.OffsetDateTime;

public record RagStaleAskRunRecord(
        String runId,
        Long conversationId,
        Long assistantMessageId,
        OffsetDateTime startedAt
) {
}
