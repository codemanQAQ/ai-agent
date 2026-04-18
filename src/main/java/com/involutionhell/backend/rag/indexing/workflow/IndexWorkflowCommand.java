package com.involutionhell.backend.rag.indexing.workflow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次索引工作流事件的触发上下文。
 */
public record IndexWorkflowCommand(
        Long documentId,
        String contentSha256,
        IndexWorkflowTriggerType triggerType,
        String triggeredBy,
        String messageId,
        Long targetGeneration,
        Integer chunkCount,
        String failureReason,
        String errorMessage,
        String note,
        Map<String, Object> metadata
) {

    public IndexWorkflowCommand {
        metadata = metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public static IndexWorkflowCommand of(
            Long documentId,
            String contentSha256,
            IndexWorkflowTriggerType triggerType,
            String triggeredBy
    ) {
        return new IndexWorkflowCommand(
                documentId,
                contentSha256,
                triggerType,
                triggeredBy,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of()
        );
    }

    public IndexWorkflowCommand withMessageId(String messageId) {
        return new IndexWorkflowCommand(
                documentId,
                contentSha256,
                triggerType,
                triggeredBy,
                messageId,
                targetGeneration,
                chunkCount,
                failureReason,
                errorMessage,
                note,
                metadata
        );
    }

    public IndexWorkflowCommand withTargetGeneration(Long targetGeneration) {
        return new IndexWorkflowCommand(
                documentId,
                contentSha256,
                triggerType,
                triggeredBy,
                messageId,
                targetGeneration,
                chunkCount,
                failureReason,
                errorMessage,
                note,
                metadata
        );
    }

    public IndexWorkflowCommand withChunkCount(Integer chunkCount) {
        return new IndexWorkflowCommand(
                documentId,
                contentSha256,
                triggerType,
                triggeredBy,
                messageId,
                targetGeneration,
                chunkCount,
                failureReason,
                errorMessage,
                note,
                metadata
        );
    }

    public IndexWorkflowCommand withFailure(String failureReason, String errorMessage) {
        return new IndexWorkflowCommand(
                documentId,
                contentSha256,
                triggerType,
                triggeredBy,
                messageId,
                targetGeneration,
                chunkCount,
                failureReason,
                errorMessage,
                note,
                metadata
        );
    }

    public IndexWorkflowCommand withNote(String note) {
        return new IndexWorkflowCommand(
                documentId,
                contentSha256,
                triggerType,
                triggeredBy,
                messageId,
                targetGeneration,
                chunkCount,
                failureReason,
                errorMessage,
                note,
                metadata
        );
    }

    public IndexWorkflowCommand withMetadata(String key, Object value) {
        Map<String, Object> enriched = new LinkedHashMap<>(metadata);
        enriched.put(key, value);
        return new IndexWorkflowCommand(
                documentId,
                contentSha256,
                triggerType,
                triggeredBy,
                messageId,
                targetGeneration,
                chunkCount,
                failureReason,
                errorMessage,
                note,
                enriched
        );
    }
}
