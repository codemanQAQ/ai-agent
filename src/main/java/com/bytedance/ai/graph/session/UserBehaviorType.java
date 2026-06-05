package com.bytedance.ai.graph.session;

import java.util.Locale;

public enum UserBehaviorType {
    CLICK_PRODUCT,
    ADD_TO_CART,
    SKIP_PRODUCT,
    NOT_INTERESTED;

    public static UserBehaviorType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("behaviorType is required");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "CLICK", "CLICK_PRODUCT", "PRODUCT_CLICK" -> CLICK_PRODUCT;
            case "ADD", "ADD_CART", "ADD_TO_CART", "CART_ADD" -> ADD_TO_CART;
            case "SKIP", "SKIP_PRODUCT" -> SKIP_PRODUCT;
            case "NOT_INTERESTED", "DISLIKE", "NEGATIVE" -> NOT_INTERESTED;
            default -> throw new IllegalArgumentException("Unsupported behaviorType: " + value);
        };
    }
}
