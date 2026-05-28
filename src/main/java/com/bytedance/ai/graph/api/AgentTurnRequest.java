package com.bytedance.ai.graph.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Graph 单轮请求。
 *
 * @param userId         业务用户 ID
 * @param conversationId 客户端侧会话 ID
 * @param message        用户原文
 * @param turnId         客户端可传的 turn 幂等 ID；为空时服务端生成
 * @param requestId      可选幂等请求 ID；为空时服务端生成
 * @param imageRef       图片检索预留字段
 * @param history        客户端覆盖历史的预留字段；当前主图默认读取服务端会话历史
 */
public record AgentTurnRequest(
        @NotBlank(message = "userId 不能为空")
        @Size(max = 64, message = "userId 最长 64")
        String userId,
        @NotBlank(message = "conversationId 不能为空")
        @Size(max = 64, message = "conversationId 最长 64")
        String conversationId,
        @NotBlank(message = "message 不能为空")
        @Size(max = 2000, message = "message 最长 2000")
        String message,
        @Size(max = 64, message = "turnId 最长 64")
        String turnId,
        @Size(max = 64, message = "requestId 最长 64")
        String requestId,
        @Size(max = 64, message = "imageRef 最长 64")
        String imageRef,
        List<@Valid AgentConversationTurn> history
) {
}
