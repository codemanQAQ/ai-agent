package com.bytedance.ai.graph.ordermanage;

public enum OrderManageStatus {
    WAITING_ADDRESS,
    WAITING_CONFIRMATION,
    CREATING,
    ORDER_CREATED,
    CANCELLED,
    FAILED,
    EXPIRED
}
