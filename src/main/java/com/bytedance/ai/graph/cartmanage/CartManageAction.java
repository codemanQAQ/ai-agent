package com.bytedance.ai.graph.cartmanage;

/**
 * Internal action of the cart_manage_workflow node. The main intent router only emits CART_MANAGE;
 * this enum is decided by the slot-filling LLM inside the workflow node.
 */
public enum CartManageAction {
    VIEW_CART,
    REMOVE_ITEM,
    UPDATE_QUANTITY,
    CLEAR_CART,
    /**
     * Adding a new product into the cart.
     */
    ADD,
    UNKNOWN
}
