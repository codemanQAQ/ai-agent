package com.bytedance.ai.graph.intent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MainIntentDecisionNormalizerTest {

    private final MainIntentDecisionNormalizer normalizer = new MainIntentDecisionNormalizer();

    @Test
    void nullIntentWithLowDefaultConfidenceFallsBackToClarify() {
        MainIntentDecision normalized = normalizer.normalize(decision(null, 0.0d, false, true, "evil"));

        assertThat(normalized.intent()).isEqualTo(MainIntent.CLARIFY);
        assertThat(normalized.needClarify()).isTrue();
        assertThat(normalized.targetWorkflow()).isEqualTo("clarify_workflow");
    }

    @Test
    void clampsConfidenceAboveOne() {
        MainIntentDecision normalized = normalizer.normalize(decision(MainIntent.PRICE_QUERY, 1.4d, false, true, "evil"));

        assertThat(normalized.confidence()).isEqualTo(1.0d);
    }

    @Test
    void clampsConfidenceBelowZeroAndForcesClarify() {
        MainIntentDecision normalized = normalizer.normalize(decision(MainIntent.PRICE_QUERY, -0.2d, false, false, "evil"));

        assertThat(normalized.confidence()).isEqualTo(0.0d);
        assertThat(normalized.intent()).isEqualTo(MainIntent.CLARIFY);
    }

    @Test
    void lowConfidenceForcesClarify() {
        MainIntentDecision normalized = normalizer.normalize(decision(MainIntent.PRODUCT_SEARCH, 0.54d, false, false, "evil"));

        assertThat(normalized.intent()).isEqualTo(MainIntent.CLARIFY);
        assertThat(normalized.needClarify()).isTrue();
    }

    @Test
    void preservesNeedClarifyForExecutableIntent() {
        MainIntentDecision normalized = normalizer.normalize(new MainIntentDecision(
                MainIntent.FUZZY_RECOMMEND,
                0.9d,
                true,
                false,
                "product_recommend_workflow",
                "Need category or scene.",
                "你想找哪类商品或用于什么场景？",
                Map.of("positiveConstraints", Map.of("category", "")),
                List.of("category_or_scene")
        ));

        assertThat(normalized.intent()).isEqualTo(MainIntent.FUZZY_RECOMMEND);
        assertThat(normalized.subIntent()).isEqualTo(MainIntent.FUZZY_RECOMMEND.name());
        assertThat(normalized.needClarify()).isTrue();
        assertThat(normalized.clarifyQuestion()).isEqualTo("你想找哪类商品或用于什么场景？");
        assertThat(normalized.targetWorkflow()).isEqualTo("product_recommend_workflow");
        assertThat(normalized.missingSlots()).containsExactly("category_or_scene");
    }

    @Test
    void createOrderForcesWriteActionTrue() {
        MainIntentDecision normalized = normalizer.normalize(new MainIntentDecision(
                MainIntent.CREATE_ORDER,
                0.9d,
                false,
                false,
                "evil",
                null,
                Map.of("cartId", "cart-1"),
                List.of()
        ));

        assertThat(normalized.writeAction()).isTrue();
    }

    @Test
    void priceQueryForcesWriteActionFalse() {
        MainIntentDecision normalized = normalizer.normalize(decision(MainIntent.PRICE_QUERY, 0.9d, false, true, "evil"));

        assertThat(normalized.writeAction()).isFalse();
    }

    @Test
    void targetWorkflowIsRegeneratedByBackend() {
        MainIntentDecision normalized = normalizer.normalize(decision(MainIntent.PRICE_QUERY, 0.9d, false, false, "add_to_cart_workflow"));

        assertThat(normalized.targetWorkflow()).isEqualTo("product_recommend_workflow");
    }

    @Test
    void addToCartIsRewrittenToCartManageWithCartActionAdd() {
        MainIntentDecision normalized = normalizer.normalize(new MainIntentDecision(
                MainIntent.ADD_TO_CART,
                0.92d,
                false,
                true,
                "add_to_cart_workflow",
                "llm reason",
                Map.of("productName", "轻量通勤双肩包 14 寸防水"),
                List.of()
        ));

        assertThat(normalized.intent()).isEqualTo(MainIntent.CART_MANAGE);
        assertThat(normalized.subIntent()).isEqualTo(MainIntent.ADD_TO_CART.name());
        assertThat(normalized.targetWorkflow()).isEqualTo("cart_manage_workflow");
        assertThat(normalized.slots()).containsEntry("cart_action", "ADD");
        assertThat(normalized.slots()).containsEntry("product_name", "轻量通勤双肩包 14 寸防水");
        Map<?, ?> action = (Map<?, ?>) normalized.slots().get("action");
        assertThat(action.get("type")).isEqualTo("ADD");
        assertThat(action.get("targetRef")).isEqualTo("轻量通勤双肩包 14 寸防水");
        assertThat(normalized.slots()).doesNotContainKey("productName");
        assertThat(normalized.missingSlots()).isEmpty();
        assertThat(normalized.needClarify()).isFalse();
        assertThat(normalized.writeAction()).isTrue();
    }

    @Test
    void removeFromCartIsRewrittenToCartManageWithCartActionRemove() {
        MainIntentDecision normalized = normalizer.normalize(new MainIntentDecision(
                MainIntent.REMOVE_FROM_CART,
                0.88d,
                false,
                true,
                "remove_from_cart_workflow",
                "llm reason",
                Map.of("productRef", "洗面奶"),
                List.of()
        ));

        assertThat(normalized.intent()).isEqualTo(MainIntent.CART_MANAGE);
        assertThat(normalized.subIntent()).isEqualTo(MainIntent.REMOVE_FROM_CART.name());
        assertThat(normalized.targetWorkflow()).isEqualTo("cart_manage_workflow");
        assertThat(normalized.slots()).containsEntry("cart_action", "REMOVE");
        assertThat(normalized.slots()).containsEntry("product_ref", "洗面奶");
        Map<?, ?> action = (Map<?, ?>) normalized.slots().get("action");
        assertThat(action.get("type")).isEqualTo("REMOVE");
        assertThat(action.get("targetRef")).isEqualTo("洗面奶");
        assertThat(normalized.missingSlots()).isEmpty();
    }

    @Test
    void updateCartItemIsRewrittenToCartManageWithCartActionUpdateQuantity() {
        MainIntentDecision normalized = normalizer.normalize(new MainIntentDecision(
                MainIntent.UPDATE_CART_ITEM,
                0.81d,
                false,
                true,
                "update_cart_item_workflow",
                "llm reason",
                Map.of("cartItemId", "42", "quantity", 3),
                List.of()
        ));

        assertThat(normalized.intent()).isEqualTo(MainIntent.CART_MANAGE);
        assertThat(normalized.targetWorkflow()).isEqualTo("cart_manage_workflow");
        assertThat(normalized.slots()).containsEntry("cart_action", "UPDATE_QUANTITY");
        assertThat(normalized.slots()).containsEntry("cart_item_id", "42");
        assertThat(normalized.slots()).containsEntry("quantity", 3);
        Map<?, ?> action = (Map<?, ?>) normalized.slots().get("action");
        assertThat(action.get("type")).isEqualTo("UPDATE_QUANTITY");
        assertThat(action.get("quantity")).isEqualTo(3);
    }

    @Test
    void preservesUnifiedActionObjectWhenProvided() {
        MainIntentDecision normalized = normalizer.normalize(new MainIntentDecision(
                MainIntent.CART_MANAGE,
                0.91d,
                false,
                true,
                "evil",
                "ADD_TO_CART",
                "llm reason",
                null,
                Map.of("action", Map.of(
                        "type", "ADD_TO_CART",
                        "targetRef", "保湿乳",
                        "quantity", 2
                )),
                List.of()
        ));

        assertThat(normalized.intent()).isEqualTo(MainIntent.CART_MANAGE);
        assertThat(normalized.subIntent()).isEqualTo("ADD_TO_CART");
        assertThat(normalized.targetWorkflow()).isEqualTo("cart_manage_workflow");
        Map<?, ?> action = (Map<?, ?>) normalized.slots().get("action");
        assertThat(action.get("type")).isEqualTo("ADD_TO_CART");
        assertThat(action.get("targetRef")).isEqualTo("保湿乳");
        assertThat(action.get("quantity")).isEqualTo(2);
    }

    @Test
    void slotKeyNormalizationPrefersSnakeCaseWhenBothFormsPresent() {
        MainIntentDecision normalized = normalizer.normalize(new MainIntentDecision(
                MainIntent.ADD_TO_CART,
                0.9d,
                false,
                true,
                "evil",
                "llm reason",
                Map.of("productName", "from_camel", "product_name", "from_snake"),
                List.of()
        ));

        assertThat(normalized.slots()).containsEntry("product_name", "from_snake");
        assertThat(normalized.slots()).doesNotContainKey("productName");
    }

    @Test
    void nullCollectionsAreNormalized() {
        MainIntentDecision normalized = normalizer.normalize(new MainIntentDecision(
                MainIntent.PRICE_QUERY,
                0.9d,
                false,
                false,
                "evil",
                null,
                null,
                null
        ));

        assertThat(normalized.slots()).isEmpty();
        assertThat(normalized.missingSlots()).isEmpty();
        assertThat(normalized.reason()).isEmpty();
    }

    private MainIntentDecision decision(
            MainIntent intent,
            double confidence,
            boolean needClarify,
            boolean writeAction,
            String targetWorkflow
    ) {
        return new MainIntentDecision(
                intent,
                confidence,
                needClarify,
                writeAction,
                targetWorkflow,
                "llm reason",
                Map.of(),
                List.of()
        );
    }
}
