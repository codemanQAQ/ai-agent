package com.bytedance.ai.graph.session;

public record OrderState(
        String pendingOrderActionId,
        String lastOrderId
) {

    public static OrderState empty() {
        return new OrderState(null, null);
    }
}
