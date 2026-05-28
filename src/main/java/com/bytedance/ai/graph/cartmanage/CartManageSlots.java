package com.bytedance.ai.graph.cartmanage;

import java.math.BigDecimal;

/**
 * Structured slots extracted from the user's natural-language cart-management request.
 *
 * <p>Filled by the cart-manage slot-filling LLM. The backend uses these slots together with the
 * server-side cart snapshot to resolve the actual cart item; the LLM must NOT invent any id.
 */
public record CartManageSlots(
        CartManageAction action,
        Integer itemIndex,
        String productName,
        String productId,
        String skuId,
        Integer quantity,
        Boolean contextualReference,
        BigDecimal expectedPrice,
        String reason
) {

    public CartManageSlots(
            CartManageAction action,
            Integer itemIndex,
            String productName,
            String productId,
            String skuId,
            Integer quantity,
            Boolean contextualReference,
            String reason
    ) {
        this(action, itemIndex, productName, productId, skuId, quantity, contextualReference, null, reason);
    }

    public static CartManageSlots unknown(String reason) {
        return new CartManageSlots(CartManageAction.UNKNOWN, null, null, null, null, null, false, null, reason);
    }
}
