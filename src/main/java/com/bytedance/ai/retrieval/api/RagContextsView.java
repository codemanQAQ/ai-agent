package com.bytedance.ai.retrieval.api;

import java.util.List;

/**
 * RAG 最终上下文事件。
 */
public record RagContextsView(
        List<RagContextView> contexts
) {
}
