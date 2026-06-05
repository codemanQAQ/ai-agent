package com.bytedance.ai.indexing.model;

/**
 * rag_index_jobs 的作业状态。
 */
public enum RagIndexJobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED
}
