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
        return Map.copyOf(snakeFirst);
    }
}
