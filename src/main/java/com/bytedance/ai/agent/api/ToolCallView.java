package com.bytedance.ai.agent.api;

/**
 * Agent 工具调用快照，用于持久化 tools_called 与事件输出。
 */
public record ToolCallView(
        IntentType intent,
        String toolName,
        Object args,
        Long latencyMs
) {
}
