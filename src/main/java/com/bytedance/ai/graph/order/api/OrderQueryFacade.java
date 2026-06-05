package com.bytedance.ai.graph.order.api;

public interface OrderQueryFacade {

    OrderView getOrder(String orderId);
}
