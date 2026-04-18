package com.involutionhell.backend.rag.indexing.model;

/**
 * 索引失败分类结果。
 *
 * @param retryable 该失败是否适合交由后续恢复机制继续处理
 * @param reason 失败分类标识，用于日志和状态记录
 */
public record RagIndexFailure(
        boolean retryable,
        String reason
) {
}
