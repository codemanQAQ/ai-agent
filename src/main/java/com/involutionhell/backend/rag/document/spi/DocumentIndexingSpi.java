package com.involutionhell.backend.rag.document.spi;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 提供给 indexing 模块的文档索引协作契约。
 *
 * <p>这个接口是 document 模块对 indexing 暴露的受控能力面。它只表达“索引流程需要
 * 文档模块提供什么能力”，不暴露文档模块内部 repository、表结构或 JDBC 实现细节。
 */
public interface DocumentIndexingSpi {

    Optional<DocumentIndexingView> findById(Long id);

    List<DocumentIndexingView> findPendingBefore(OffsetDateTime cutoff, int limit);

    List<DocumentIndexingView> findProcessingBefore(OffsetDateTime cutoff, int limit);

    List<DocumentIndexingView> findFailedBefore(OffsetDateTime cutoff, int limit);

    List<DocumentIndexingView> findDeletingBefore(OffsetDateTime cutoff, int limit);

    void markPending(Long id);

    void requeue(Long id, String note);

    void markProcessing(Long id);

    void markIndexed(Long id, Long indexedGeneration, int chunkCount, OffsetDateTime indexedAt);

    void markFailed(Long id, String errorMessage);

    void markDeleting(Long id, String note);

    void deleteById(Long id);
}
