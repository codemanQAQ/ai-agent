package com.bytedance.ai.graph.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 客户端显式传入的历史消息；当前主图默认读取服务端会话历史。
 */
public record AgentConversationTurn(
        @NotBlank(message = "role 不能为空")
        @Size(max = 16, message = "role 最长 16")
        String role,
        @NotBlank(message = "content 不能为空")
        @Size(max = 2000, message = "content 最长 2000")
        String content
) {
}
