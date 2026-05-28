package com.bytedance.ai.retrieval.spi;

/**
 * 给 graph 编排链路用的会话操作 SPI。
 *
 * <p>本 SPI 只覆盖 {@code rag_conversations} + {@code rag_conversation_messages}
 * 的会话/消息一对生命周期，**不**触碰 {@code rag_ask_runs}——后者属于既有 ask 链路。
 * {@code agent_turn} 表由 graph 会话仓储维护，幂等也由它负责。
 *
 * <p>调用顺序：
 * <ol>
 *   <li>{@link #beginTurn} —— 进入会话、写 user 消息、占位 assistant 消息（status=STREAMING），
 *       返回消息 id 与历史；同时刷新会话统计。</li>
 *   <li>{@link #completeTurn} —— assistant 消息落地为 SUCCEEDED 并写最终答案文本。</li>
 *   <li>{@link #failTurn} —— assistant 消息标记 FAILED，附带错误原因。</li>
 * </ol>
 */
public interface AgentConversationSpi {

    /**
     * 开启一轮 graph 会话。
     *
     * <p>调用方需保证已在 agent_turn 表完成幂等检查；命中历史时不应进入本方法。
     *
     * @param userId          用户 id（必填）
     * @param conversationId  会话业务 id（必填；不存在则自动创建会话）
     * @param userMessage     本轮用户消息原文（必填）
     * @param correlationId   端到端 trace 关联 id，会写入消息行的 correlation 列
     * @return 会话上下文（含 message id 与历史）
     */
    AgentTurnConversationState beginTurn(
            String userId,
            String conversationId,
            String userMessage,
            String correlationId
    );

    /**
     * 标记 assistant 消息为 SUCCEEDED 并写入最终答案文本。
     */
    void completeTurn(String assistantMessageId, String answerText);

    /**
     * 标记 assistant 消息为 FAILED，附带错误原因。
     */
    void failTurn(String assistantMessageId, String errorCode, String errorMessage);
}
