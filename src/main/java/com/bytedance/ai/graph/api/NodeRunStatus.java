package com.bytedance.ai.graph.api;

public enum NodeRunStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED,
    WAITING_CLARIFICATION,
    WAITING_CONFIRMATION
}
