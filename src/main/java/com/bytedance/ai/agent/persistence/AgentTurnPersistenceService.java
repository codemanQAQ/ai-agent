package com.bytedance.ai.agent.persistence;

import com.bytedance.ai.shared.support.RagJsonCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * agent_turn 的写入门面，把 JSON 编码和基础参数校验收敛在 agent 模块内部。
 */
@Service
public class AgentTurnPersistenceService {

    private final AgentTurnRepository repository;
    private final RagJsonCodec jsonCodec;

    public AgentTurnPersistenceService(AgentTurnRepository repository, RagJsonCodec jsonCodec) {
        this.repository = repository;
        this.jsonCodec = jsonCodec;
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentTurnRecord createRunning(
            String turnId,
            String correlationId,
            String userId,
            String conversationId,
            String requestId,
            String userMessage
    ) {
        requireText(turnId, "turnId 不能为空");
        requireText(correlationId, "correlationId 不能为空");
        requireText(userId, "userId 不能为空");
        requireText(conversationId, "conversationId 不能为空");
        requireText(userMessage, "userMessage 不能为空");
        repository.createRunning(turnId, correlationId, userId, conversationId, emptyToNull(requestId), userMessage);
        return repository.findByTurnId(turnId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Optional<AgentTurnRecord> findByTurnId(String turnId) {
        if (!StringUtils.hasText(turnId)) {
            return Optional.empty();
        }
        return repository.findByTurnId(turnId);
    }

    @Transactional(readOnly = true)
    public Optional<AgentTurnRecord> findByRequestId(String userId, String conversationId, String requestId) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(conversationId) || !StringUtils.hasText(requestId)) {
            return Optional.empty();
        }
        return repository.findByRequestId(userId, conversationId, requestId);
    }

    @Transactional(readOnly = true)
    public List<AgentTurnRecord> findRecentByConversationId(String conversationId, int limit) {
        if (!StringUtils.hasText(conversationId) || limit <= 0) {
            return List.of();
        }
        return repository.findRecentByConversationId(conversationId, limit);
    }

    @Transactional(readOnly = true)
    public Optional<AgentTurnRecord> findLatestMemorySummary(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return Optional.empty();
        }
        return repository.findLatestMemorySummary(conversationId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void attachConversationMessages(String turnId, String userMessageId, String assistantMessageId) {
        requireText(turnId, "turnId 不能为空");
        repository.attachConversationMessages(turnId, emptyToNull(userMessageId), emptyToNull(assistantMessageId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void recordIntent(String turnId, String intent, String source, Double confidence, Object slots) {
        requireText(turnId, "turnId 不能为空");
        repository.recordIntent(turnId, emptyToNull(intent), emptyToNull(source), confidence, jsonCodec.write(slots == null ? new java.util.LinkedHashMap<>() : slots));
    }

    @Transactional(rollbackFor = Exception.class)
    public void recordToolState(String turnId, Object toolsCalled, Object cardsEmitted) {
        requireText(turnId, "turnId 不能为空");
        repository.recordToolState(
                turnId,
                jsonCodec.write(toJsonArrayDefault(toolsCalled)),
                jsonCodec.write(toJsonArrayDefault(cardsEmitted))
        );
    }

    @Transactional(rollbackFor = Exception.class)
    public void markSucceeded(
            String turnId,
            String answerText,
            Boolean generatedByModel,
            Integer tokensIn,
            Integer tokensOut,
            Integer latencyMs
    ) {
        markSucceeded(turnId, answerText, generatedByModel, tokensIn, tokensOut, latencyMs, null, null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markSucceeded(
            String turnId,
            String answerText,
            Boolean generatedByModel,
            Integer tokensIn,
            Integer tokensOut,
            Integer latencyMs,
            String memorySummary,
            Integer memorySummaryMessageCount,
            String memorySummaryModel
    ) {
        requireText(turnId, "turnId 不能为空");
        repository.markSucceeded(
                turnId,
                answerText,
                generatedByModel,
                tokensIn,
                tokensOut,
                latencyMs,
                emptyToNull(memorySummary),
                memorySummaryMessageCount,
                emptyToNull(memorySummaryModel)
        );
    }

    @Transactional(rollbackFor = Exception.class)
    public void markFailed(String turnId, String errorCode, String errorMessage, Integer latencyMs) {
        requireText(turnId, "turnId 不能为空");
        repository.markFailed(turnId, emptyToNull(errorCode), emptyToNull(errorMessage), latencyMs);
    }

    private Object toJsonArrayDefault(Object value) {
        return value == null ? List.of() : value;
    }

    private String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }
}
