package com.involutionhell.backend.rag.indexing.model;

/**
 * 离线索引任务的阶段状态。
 */
public enum RagIndexStage {
    QUEUED,
    DISPATCHING,
    PREPARING,
    CHUNKING,
    SAVE_CHUNKS,
    VECTOR_INDEXING,
    COMMIT_INDEX,
    COMPLETED,
    SKIPPED
}
