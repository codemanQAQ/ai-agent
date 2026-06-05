package com.bytedance.ai.retrieval.api;

/**
 * RAG SSE 流内错误事件。
 */
public record RagStreamErrorView(
        String code,
        String message,
        boolean degraded
) {
}
