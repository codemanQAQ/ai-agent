package com.bytedance.ai.graph.session;

public record CartState(
        String lastCartAction,
        String pendingActionId
) {

    public static CartState empty() {
        return new CartState(null, null);
    }
}
