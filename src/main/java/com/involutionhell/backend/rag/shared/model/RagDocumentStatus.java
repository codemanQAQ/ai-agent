package com.involutionhell.backend.rag.shared.model;

/**
 * 文档索引状态。
 */
public enum RagDocumentStatus {
    PENDING,
    PROCESSING,
    INDEXED,
    FAILED,
    DELETING
}
