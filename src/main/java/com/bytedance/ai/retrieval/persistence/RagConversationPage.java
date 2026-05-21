package com.bytedance.ai.retrieval.persistence;

import java.util.List;

public record RagConversationPage(
        List<RagConversationRecord> items,
        RagConversationCursor nextCursor
) {
}
