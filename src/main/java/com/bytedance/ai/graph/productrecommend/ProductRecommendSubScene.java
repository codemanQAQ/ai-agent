package com.bytedance.ai.graph.productrecommend;

import com.bytedance.ai.graph.api.GuideGraphIntent;

public enum ProductRecommendSubScene {

    FUZZY_RECOMMEND("fuzzyRecommendStrategy"),
    CONDITION_FILTER("conditionFilterStrategy"),
    MULTI_TURN_REFINE("multiTurnRefineStrategy"),
    PRODUCT_COMPARE("productCompareStrategy"),
    NEGATIVE_CONSTRAINT("negativeConstraintStrategy"),
    SCENE_BUNDLE_RECOMMEND("sceneBundleRecommendStrategy"),
    PHOTO_SEARCH("photoSearchStrategy"),
    DETAIL_FAQ_REVIEW_ANSWER("detailFaqReviewAnswerStrategy");

    private final String strategyName;

    ProductRecommendSubScene(String strategyName) {
        this.strategyName = strategyName;
    }

    public String strategyName() {
        return strategyName;
    }

    public static ProductRecommendSubScene from(GuideGraphIntent intent) {
        if (intent == null) {
            return FUZZY_RECOMMEND;
        }
        return switch (intent) {
            case CONDITION_FILTER -> CONDITION_FILTER;
            case MULTI_TURN_REFINE -> MULTI_TURN_REFINE;
            case PRODUCT_COMPARE -> PRODUCT_COMPARE;
            case NEGATIVE_CONSTRAINT -> NEGATIVE_CONSTRAINT;
            case SCENE_BUNDLE_RECOMMEND -> SCENE_BUNDLE_RECOMMEND;
            case PHOTO_SEARCH -> PHOTO_SEARCH;
            case PRODUCT_DETAIL_QUERY, PRICE_QUERY, INVENTORY_QUERY, REVIEW_SUMMARY -> DETAIL_FAQ_REVIEW_ANSWER;
            default -> FUZZY_RECOMMEND;
        };
    }
}
