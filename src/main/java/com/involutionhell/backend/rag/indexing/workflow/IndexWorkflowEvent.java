package com.involutionhell.backend.rag.indexing.workflow;

/**
 * 索引工作流事件。
 */
public enum IndexWorkflowEvent {
    QUEUE,
    DISPATCH,
    START_ATTEMPT,
    ENTER_CHUNKING,
    ENTER_SAVE_CHUNKS,
    ENTER_VECTOR_INDEXING,
    ENTER_COMMIT_INDEX,
    SUCCEED,
    RETRY,
    FAIL,
    SKIP
}
