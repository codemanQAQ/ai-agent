package com.bytedance.ai.graph.cart.api;

public interface CartQueryFacade {

    CartView getActiveCart(String userId, String conversationId);
}
