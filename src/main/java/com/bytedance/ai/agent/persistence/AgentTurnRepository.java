package com.bytedance.ai.agent.persistence;

import java.util.List;
import java.util.Optional;

public interface AgentTurnRepository {

    void createRunning(
            String turnId,
            String correlationId,
            String userId,
            String conversationId,
            String requestId,
            String userMessage
    );

    Optional<AgentTurnRecord> findByTurnId(String turnId);

    Optional<AgentTurnRecord> findByRequestId(String userId, String conversationId, String requestId);

    List<AgentTurnRecord> findRecentByConversationId(String conversationId, int limit);

    void attachConversationMessages(String turnId, String userMessageId, String assistantMessageId);

    void recordIntent(String turnId, String intent, String source, Double confidence, String slotsJson);

    void recordToolState(String turnId, String toolsCalledJson, String cardsEmittedJson);

    void markSucceeded(
            String turnId,
            String answerText,
            Boolean generatedByModel,
            Integer tokensIn,
            Integer tokensOut,
            Integer latencyMs
    );

    void markFailed(String turnId, String errorCode, String errorMessage, Integer latencyMs);
}
