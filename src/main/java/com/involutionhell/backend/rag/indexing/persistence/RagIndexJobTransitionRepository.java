package com.involutionhell.backend.rag.indexing.persistence;

import com.involutionhell.backend.rag.indexing.persistence.RagIndexJobTransitionRecord;
import java.util.List;
import java.util.Map;

/**
 * 索引状态转移审计仓储。
 */
public interface RagIndexJobTransitionRepository {

    /**
     * 写入一条状态转移审计日志。
     */
    void save(
            Long documentId,
            Long jobId,
            Long outboxId,
            String contentSha256,
            String fromState,
            String toState,
            String event,
            String triggerType,
            String triggeredBy,
            boolean success,
            String failureReason,
            String errorMessage,
            String messageId,
            Map<String, Object> metadata
    );

    /**
     * 查询文档当前版本对应的全部状态转移历史。
     */
    List<RagIndexJobTransitionRecord> findByDocumentIdAndContentSha256(Long documentId, String contentSha256);

    /**
     * 删除指定文档的全部状态转移审计记录。
     */
    void deleteByDocumentId(Long documentId);

}
