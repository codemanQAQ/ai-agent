package com.bytedance.ai.graph.intent;

import java.util.EnumMap;
import java.util.Map;

public final class MainIntentWorkflowMapping {

    private static final Map<MainIntent, String> TARGETS = new EnumMap<>(MainIntent.class);

    static {
        TARGETS.put(MainIntent.PRODUCT_RECOMMEND, "product_recommend_workflow");
        TARGETS.put(MainIntent.PRODUCT_SEARCH, "product_search_workflow");
        TARGETS.put(MainIntent.PRODUCT_COMPARE, "product_compare_workflow");
        TARGETS.put(MainIntent.PRODUCT_DETAIL_QUERY, "product_detail_query_workflow");

        TARGETS.put(MainIntent.PRICE_QUERY, "price_query_workflow");
        TARGETS.put(MainIntent.INVENTORY_QUERY, "inventory_query_workflow");
        TARGETS.put(MainIntent.ORDER_QUERY, "order_query_workflow");
        TARGETS.put(MainIntent.LOGISTICS_QUERY, "logistics_query_workflow");

        // Defensive routing: legacy granular cart intents share the unified cart_manage_workflow node.
        TARGETS.put(MainIntent.CART_MANAGE, "cart_manage_workflow");
        TARGETS.put(MainIntent.ADD_TO_CART, "cart_manage_workflow");
        TARGETS.put(MainIntent.REMOVE_FROM_CART, "cart_manage_workflow");
        TARGETS.put(MainIntent.UPDATE_CART_ITEM, "cart_manage_workflow");
        TARGETS.put(MainIntent.CREATE_ORDER, "order_manage_workflow");
        TARGETS.put(MainIntent.CONFIRM_ORDER, "order_manage_workflow");
        TARGETS.put(MainIntent.CANCEL_ORDER, "order_manage_workflow");

        TARGETS.put(MainIntent.POLICY_QA, "policy_qa_workflow");
        TARGETS.put(MainIntent.REVIEW_SUMMARY, "review_summary_workflow");

        TARGETS.put(MainIntent.CLARIFY, "clarify_workflow");
        TARGETS.put(MainIntent.SMALL_TALK, "small_talk_workflow");
        TARGETS.put(MainIntent.UNKNOWN, "clarify_workflow");
    }

    private MainIntentWorkflowMapping() {
    }

    public static String targetWorkflowOf(MainIntent intent) {
        return TARGETS.getOrDefault(intent == null ? MainIntent.UNKNOWN : intent, "clarify_workflow");
    }
}
