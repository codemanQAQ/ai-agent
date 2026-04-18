package com.involutionhell.backend.rag.indexing.workflow;

/**
 * 索引工作流状态转移异常。
 */
public class IndexWorkflowTransitionException extends RuntimeException {

    public IndexWorkflowTransitionException(String message) {
        super(message);
    }

    public IndexWorkflowTransitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
