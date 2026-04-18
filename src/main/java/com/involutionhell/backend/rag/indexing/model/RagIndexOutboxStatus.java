package com.involutionhell.backend.rag.indexing.model;

/**
 * 索引任务投递 Outbox 的状态。
 */
public enum RagIndexOutboxStatus {
    NEW,
    SENDING,
    SENT,
    FAILED
}
