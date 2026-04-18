package com.involutionhell.backend.rag.retrieval.api;

import jakarta.validation.constraints.NotBlank;

/**
 * RAG 问答上下文中的单条对话消息。
 *
 * @param role 角色，通常为 user / assistant / system
 * @param content 消息内容
 */
public record RagConversationMessage(
        @NotBlank(message = "history.role 不能为空")
        String role,
        @NotBlank(message = "history.content 不能为空")
        String content
) {
}
