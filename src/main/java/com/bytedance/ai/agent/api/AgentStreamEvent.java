package com.bytedance.ai.agent.api;

/**
 * Agent SSE 流中的统一事件包。
 *
 * @param id             事件 ID，W1 不持久化
 * @param event          SSE event name
 * @param correlationId  本次 turn 链路追踪 ID
 * @param data           事件负载
 */
public record AgentStreamEvent(
        String id,
        String event,
        String correlationId,
        Object data
) {
}
