package com.bytedance.ai.graph.cartmanage;

import com.bytedance.ai.graph.cart.api.CartView;

/**
 * Result of a cart write operation (remove / update quantity / clear).
 *
 * @param success      true if the underlying cart aggregate accepted the mutation
 * @param updatedCart  the cart snapshot AFTER the mutation, never null on success
 * @param errorCode    machine-readable failure code, null on success
 * @param errorMessage human-readable failure detail, null on success
 */
public record CartMutationResult(
        boolean success,
        CartView updatedCart,
        String errorCode,
        String errorMessage
) {

    public static CartMutationResult ok(CartView updatedCart) {
        return new CartMutationResult(true, updatedCart, null, null);
    }

    public static CartMutationResult failure(String errorCode, String errorMessage) {
        return new CartMutationResult(false, null, errorCode, errorMessage);
    }
}
