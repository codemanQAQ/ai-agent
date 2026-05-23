package com.bytedance.ai.agent.persistence;

import java.time.OffsetDateTime;

/**
 * agent_turn 表的一行持久化视图。
 */
public record AgentTurnRecord(
        Long id,
        String turnId,
        String correlationId,
        String userId,
        String conversationId,
        String requestId,
        String userMessageId,
        String assistantMessageId,
        String status,
        String userMessage,
        String intent,
        String intentSource,
        Double intentConfidence,
        String slotsJson,
        String toolsCalled,
        String cardsEmitted,
        Boolean generatedByModel,
        String answerText,
        Integer tokensIn,
        Integer tokensOut,
        Integer latencyMs,
        String errorCode,
        String errorMessage,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt
) {
}
