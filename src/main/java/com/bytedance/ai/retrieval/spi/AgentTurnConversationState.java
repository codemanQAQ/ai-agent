package com.bytedance.ai.retrieval.spi;

import java.util.List;

/**
 * agent turn 开始时返回的会话上下文。
 *
 * <p>对应 {@code rag_conversations} / {@code rag_conversation_messages} 的两条新增 / 既有行；
 * agent 自身的 {@code agent_turn} 行由 agent 模块单独维护，不在此结构里出现。
 *
 * <p>幂等检查由 agent 模块在 {@code agent_turn} 上完成（命中时上层应**跳过**对
 * {@link AgentConversationSpi#beginTurn} 的调用，直接从历史 agent_turn 行恢复消息 id），
 * 因此这里**不**承担 replay 语义。
 *
 * @param conversationInternalId  rag_conversations.id（持久化层引用，agent 通常不直接用）
 * @param userMessageId           本轮用户消息的业务 messageId
 * @param assistantMessageId      本轮 assistant 占位消息的业务 messageId（initial status STREAMING）
 * @param history                 该会话中已成功的历史消息（最新在后），用于 prompt 上下文与摘要
 */
public record AgentTurnConversationState(
        Long conversationInternalId,
        String userMessageId,
        String assistantMessageId,
        List<ConversationTurn> history
) {
    /**
     * 历史会话中的一轮消息（role + content）。
     */
    public record ConversationTurn(String role, String content) {
    }
}
