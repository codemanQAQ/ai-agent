package com.involutionhell.backend.rag.document.api;

/**
 * 文档删除后发出的待处理索引清理事件。
 */
public record DocumentIndexCleanupRequestedEvent(Long documentId) {
}
