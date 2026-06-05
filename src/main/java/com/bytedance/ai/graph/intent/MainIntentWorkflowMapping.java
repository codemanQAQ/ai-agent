package com.bytedance.ai.graph.intent;

import java.util.EnumMap;
import java.util.Map;

public final class MainIntentWorkflowMapping {

    public static final String PRODUCT_RECOMMEND_WORKFLOW = "product_recommend_workflow";
    public static final String CART_MANAGE_WORKFLOW = "cart_manage_workflow";
    public static final String ORDER_MANAGE_WORKFLOW = "order_manage_workflow";
    public static final String CLARIFY_WORKFLOW = "clarify_workflow";

    private static final Map<MainIntent, String> TARGETS = new EnumMap<>(MainIntent.class);

    static {
        TARGETS.put(MainIntent.FUZZY_RECOMMEND, PRODUCT_RECOMMEND_WORKFLOW);
        TARGETS.put(MainIntent.CONDITION_FILTER, PRODUCT_RECOMMEND_WORKFLOW);
        TARGETS.put(MainIntent.MULTI_TURN_REFINE, PRODUCT_RECOMMEND_WORKFLOW);
        TARGETS.put(MainIntent.NEGATIVE_CONSTRAINT, PRODUCT_RECOMMEND_WORKFLOW);
        TARGETS.put(MainIntent.SCENE_BUNDLE_RECOMMEND, PRODUCT_RECOMMEND_WORKFLOW);
        TARGETS.put(MainIntent.PHOTO_SEARCH, PRODUCT_RECOMMEND_WORKFLOW);

        // Legacy product-level intents are folded into the single recommendation workflow.
        // Product detail, FAQ, review, price, and inventory questions are handled there as
        // strategies after state merge, instead of by separate top-level graph nodes.
        TARGETS.put(MainIntent.PRODUCT_RECOMMEND, PRODUCT_RECOMMEND_WORKFLOW);
        TARGETS.put(MainIntent.PRODUCT_SEARCH, PRODUCT_RECOMMEND_WORKFLOW);
        TARGETS.put(MainIntent.PRODUCT_COMPARE, PRODUCT_RECOMMEND_WORKFLOW);
        TARGETS.put(MainIntent.PRODUCT_DETAIL_QUERY, PRODUCT_RECOMMEND_WORKFLOW);
        TARGETS.put(MainIntent.PRICE_QUERY, PRODUCT_RECOMMEND_WORKFLOW);
        TARGETS.put(MainIntent.INVENTORY_QUERY, PRODUCT_RECOMMEND_WORKFLOW);
        TARGETS.put(MainIntent.REVIEW_SUMMARY, PRODUCT_RECOMMEND_WORKFLOW);

        TARGETS.put(MainIntent.ORDER_QUERY, ORDER_MANAGE_WORKFLOW);
        TARGETS.put(MainIntent.LOGISTICS_QUERY, ORDER_MANAGE_WORKFLOW);

        // Defensive routing: legacy granular cart intents share the unified cart_manage_workflow node.
        TARGETS.put(MainIntent.CART_MANAGE, CART_MANAGE_WORKFLOW);
        TARGETS.put(MainIntent.ADD_TO_CART, CART_MANAGE_WORKFLOW);
        TARGETS.put(MainIntent.REMOVE_FROM_CART, CART_MANAGE_WORKFLOW);
        TARGETS.put(MainIntent.UPDATE_CART_ITEM, CART_MANAGE_WORKFLOW);
        TARGETS.put(MainIntent.CREATE_ORDER, ORDER_MANAGE_WORKFLOW);
        TARGETS.put(MainIntent.CONFIRM_ORDER, ORDER_MANAGE_WORKFLOW);
        TARGETS.put(MainIntent.CANCEL_ORDER, ORDER_MANAGE_WORKFLOW);
        TARGETS.put(MainIntent.ORDER_MANAGE, ORDER_MANAGE_WORKFLOW);

        TARGETS.put(MainIntent.POLICY_QA, CLARIFY_WORKFLOW);

        TARGETS.put(MainIntent.CLARIFY, CLARIFY_WORKFLOW);
        TARGETS.put(MainIntent.OTHER, CLARIFY_WORKFLOW);
        TARGETS.put(MainIntent.SMALL_TALK, CLARIFY_WORKFLOW);
        TARGETS.put(MainIntent.UNKNOWN, CLARIFY_WORKFLOW);
    }

    private MainIntentWorkflowMapping() {
    }

    public static String targetWorkflowOf(MainIntent intent) {
        return TARGETS.getOrDefault(intent == null ? MainIntent.OTHER : intent, CLARIFY_WORKFLOW);
    }
}
