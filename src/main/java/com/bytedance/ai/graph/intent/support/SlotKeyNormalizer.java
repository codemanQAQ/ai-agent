package com.bytedance.ai.graph.intent.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rewrites well-known camelCase slot keys to their canonical snake_case form. Unknown keys pass
 * through unchanged so non-cart intents (orders, etc.) keep their original wire format.
 *
 * <p>When both forms appear in the same payload (e.g. LLM accidentally produces {@code productName}
 * and {@code product_name}), the snake_case (canonical) form wins and the camelCase value is
 * dropped. This guarantees a single authoritative value reaches the workflow.
 */
public final class SlotKeyNormalizer {

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("productName", SlotKeys.PRODUCT_NAME),
            Map.entry("productRef", SlotKeys.PRODUCT_REF),
            Map.entry("productId", SlotKeys.PRODUCT_ID),
            Map.entry("skuId", SlotKeys.SKU_ID),
            Map.entry("expectedPrice", SlotKeys.EXPECTED_PRICE),
            Map.entry("cartAction", SlotKeys.CART_ACTION),
            Map.entry("cartItemId", SlotKeys.CART_ITEM_ID),
            Map.entry("itemIndex", SlotKeys.ITEM_INDEX),
            Map.entry("contextualReference", SlotKeys.CONTEXTUAL_REFERENCE)
    );

    private SlotKeyNormalizer() {
    }

    public static Map<String, Object> normalize(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> snakeFirst = new LinkedHashMap<>();
        Map<String, Object> camelFallback = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            String canonical = ALIASES.get(key);
            if (canonical == null) {
                // Either the key is already snake_case (or a non-cart key we leave alone).
                snakeFirst.putIfAbsent(key, entry.getValue());
            } else {
                // Defer camelCase aliases so an explicit snake_case sibling wins.
                camelFallback.putIfAbsent(canonical, entry.getValue());
            }
        }
        for (Map.Entry<String, Object> entry : camelFallback.entrySet()) {
            snakeFirst.putIfAbsent(entry.getKey(), entry.getValue());
        }
        addActionObjectIfMissing(snakeFirst);
        return Map.copyOf(snakeFirst);
    }

    private static void addActionObjectIfMissing(Map<String, Object> slots) {
        Object existingAction = slots.get(SlotKeys.ACTION);
        if (existingAction instanceof Map<?, ?>) {
            return;
        }
        Map<String, Object> action = new LinkedHashMap<>();
        putIfPresent(action, SlotKeys.ACTION_TYPE, slots.get(SlotKeys.CART_ACTION));
        putIfPresent(action, SlotKeys.ACTION_TARGET_REF, firstNonNull(
                slots.get(SlotKeys.PRODUCT_REF),
                slots.get(SlotKeys.PRODUCT_NAME),
                slots.get(SlotKeys.PRODUCT_ID)
        ));
        putIfPresent(action, SlotKeys.ACTION_QUANTITY, slots.get(SlotKeys.QUANTITY));
        putIfPresent(action, SlotKeys.ACTION_SKU_SPEC, firstNonNull(
                slots.get(SlotKeys.SPEC),
                slots.get(SlotKeys.SKU_ID)
        ));
        putIfPresent(action, SlotKeys.ACTION_ORDER_REF, firstNonNull(
                slots.get("orderRef"),
                slots.get("orderId"),
                slots.get("pendingOrderId")
        ));
        putIfPresent(action, SlotKeys.ACTION_ADDRESS_REF, slots.get("addressRef"));
        putIfPresent(action, SlotKeys.ACTION_SOURCE, slots.get("source"));
        if (!action.isEmpty()) {
            slots.put(SlotKeys.ACTION, Map.copyOf(action));
        }
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            target.put(key, value);
        }
    }
}
