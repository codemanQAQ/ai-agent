package com.involutionhell.backend.rag.indexing.workflow;

/**
 * 工作流触发来源。
 */
public enum IndexWorkflowTriggerType {
    API,
    MQ,
    RECOVERY,
    SYSTEM
}
