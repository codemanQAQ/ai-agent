package com.bytedance.ai.graph.api;

import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;

public enum GuideGraphIntent {
    FUZZY_RECOMMEND,
    CONDITION_FILTER,
    MULTI_TURN_REFINE,
    NEGATIVE_CONSTRAINT,
    SCENE_BUNDLE_RECOMMEND,
    PHOTO_SEARCH,

    PRODUCT_RECOMMEND,
    PRODUCT_SEARCH,
    PRODUCT_COMPARE,
    PRODUCT_DETAIL_QUERY,

    PRICE_QUERY,
    INVENTORY_QUERY,

    ADD_TO_CART,
    REMOVE_FROM_CART,
    UPDATE_CART_ITEM,
    CART_MANAGE,

    CREATE_ORDER,
    CONFIRM_ORDER,
    CANCEL_ORDER,
    ORDER_MANAGE,

    ORDER_QUERY,
    LOGISTICS_QUERY,

    POLICY_QA,
    REVIEW_SUMMARY,

    CLARIFY,
    OTHER,
    SMALL_TALK,
    UNKNOWN;

    public static Optional<GuideGraphIntent> parse(String value) {
        if (!StringUtils.hasText(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(GuideGraphIntent.valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
