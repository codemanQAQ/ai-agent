package com.bytedance.ai.indexing.model;

/**
 * 索引任务投递 Outbox 的状态。
 */
public enum RagIndexOutboxStatus {
    NEW,
    SENDING,
    SENT,
    FAILED
}
