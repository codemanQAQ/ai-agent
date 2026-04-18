package com.involutionhell.backend.rag.document.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 文档元数据仓储。
 */
public interface RagDocumentRepository {

    /**
     * 创建一条新的文档主记录，并初始化为待索引状态。
     */
    RagDocumentRecord save(
            String sourceType,
            String sourceUri,
            String externalRef,
            String title,
            String content,
            String contentSha256,
            Map<String, Object> metadata
    );

    /**
     * 更新现有文档内容，并把索引状态重置为待重新处理。
     */
    RagDocumentRecord update(
            Long id,
            String sourceType,
            String sourceUri,
            String externalRef,
            String title,
            String content,
            String contentSha256,
            Map<String, Object> metadata
    );

    /**
     * 按主键查询文档。
     */
    Optional<RagDocumentRecord> findById(Long id);

    /**
     * 查询长时间停留在待处理状态的文档，供补偿任务重新投递。
     */
    List<RagDocumentRecord> findPendingBefore(OffsetDateTime cutoff, int limit);

    /**
     * 查询长时间停留在处理中状态的文档，供补偿任务恢复。
     */
    List<RagDocumentRecord> findProcessingBefore(OffsetDateTime cutoff, int limit);

    /**
     * 查询已经失败一段时间的文档，供低频补偿或人工介入。
     */
    List<RagDocumentRecord> findFailedBefore(OffsetDateTime cutoff, int limit);

    /**
     * 查询长时间停留在删除中状态的文档，供两阶段删除补偿任务完成物理清理。
     */
    List<RagDocumentRecord> findDeletingBefore(OffsetDateTime cutoff, int limit);

    /**
     * 重置文档索引状态，使其回到初始待处理态。
     */
    void markPending(Long id);

    /**
     * 把文档重新放回待处理队列，并附带一条重排原因。
     */
    void requeue(Long id, String note);

    /**
     * 标记文档正在被当前一次索引尝试处理。
     */
    void markProcessing(Long id);

    /**
     * 标记文档索引成功，并写入当前生效 generation 及统计信息。
     */
    void markIndexed(Long id, Long indexedGeneration, int chunkCount, OffsetDateTime indexedAt);

    /**
     * 标记文档索引失败，并记录最终错误信息。
     */
    void markFailed(Long id, String errorMessage);

    /**
     * 将文档标记为删除中，阻止新的索引任务继续推进。
     */
    void markDeleting(Long id, String note);

    /**
     * 物理删除文档主记录。
     */
    void deleteById(Long id);
}
