package com.bytedance.ai.graph.intent.support;

/**
 * Canonical snake_case slot key constants used across main-intent and cart-manage workflows.
 *
 * <p>Both the main intent LLM and the cart-manage slot-filling LLM may emit camelCase or
 * snake_case slot names; {@link SlotKeyNormalizer} converts everything to the canonical form
 * below before the slots map reaches downstream nodes.
 */
public final class SlotKeys {

    public static final String CART_ACTION = "cart_action";
    public static final String CART_ITEM_ID = "cart_item_id";
    public static final String PRODUCT_NAME = "product_name";
    public static final String PRODUCT_REF = "product_ref";
    public static final String PRODUCT_ID = "product_id";
    public static final String SKU_ID = "sku_id";
    public static final String EXPECTED_PRICE = "expected_price";
    public static final String ITEM_INDEX = "item_index";
    public static final String QUANTITY = "quantity";
    public static final String CONTEXTUAL_REFERENCE = "contextual_reference";
    public static final String SPEC = "spec";

    /** Canonical cart_action slot values. */
    public static final String CART_ACTION_ADD = "ADD";
    public static final String CART_ACTION_REMOVE = "REMOVE";
    public static final String CART_ACTION_UPDATE_QUANTITY = "UPDATE_QUANTITY";
    public static final String CART_ACTION_VIEW_CART = "VIEW_CART";
    public static final String CART_ACTION_CLEAR_CART = "CLEAR_CART";

    private SlotKeys() {
    }
}
