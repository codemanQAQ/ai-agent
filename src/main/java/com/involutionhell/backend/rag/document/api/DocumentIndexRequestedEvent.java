package com.involutionhell.backend.rag.document.api;

/**
 * 文档侧发出的索引请求事件。
 */
public record DocumentIndexRequestedEvent(
        Long documentId,
        String contentSha256,
        String triggeredBy
) {
}
