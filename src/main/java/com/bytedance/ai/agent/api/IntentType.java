package com.bytedance.ai.agent.api;

/**
 * Agent W1/W2/W3 规划的 9 类意图。
 */
public enum IntentType {
    RECOMMEND_VAGUE,
    FILTER_BY_ATTR,
    REFINE,
    COMPARE,
    EXCLUDE,
    SCENARIO_BUNDLE,
    CART_OP,
    IMAGE_SEARCH,
    OUT_OF_SCOPE
}
