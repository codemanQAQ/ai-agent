package com.involutionhell.backend.rag.retrieval.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * RAG 问答请求。
 *
 * @param question 用户问题
 * @param topK 希望返回的上下文数量
 * @param sourceUriPrefix 按来源 URI 前缀过滤
 * @param tags 按文档标签过滤
 * @param headingPathContains 按标题路径关键字过滤
 * @param history 对话历史消息列表，供 query compression 使用
 */
public record RagAskRequest(
        @NotBlank(message = "问题不能为空")
        String question,
        @Min(value = 1, message = "topK 最小为 1")
        @Max(value = 10, message = "topK 最大为 10")
        Integer topK,
        String sourceUriPrefix,
        List<String> tags,
        String headingPathContains,
        List<@Valid RagConversationMessage> history
) {
}
