package com.bytedance.ai.graph.intent;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MainIntentWorkflowMappingTest {

    @ParameterizedTest
    @EnumSource(MainIntent.class)
    void mapsEveryMainIntentToWorkflow(MainIntent intent) {
        assertThat(MainIntentWorkflowMapping.targetWorkflowOf(intent)).isNotBlank();
    }

    @ParameterizedTest
    @EnumSource(MainIntent.class)
    void mapsEveryMainIntentToOneOfCurrentTopLevelWorkflows(MainIntent intent) {
        assertThat(MainIntentWorkflowMapping.targetWorkflowOf(intent))
                .isIn(Set.of(
                        MainIntentWorkflowMapping.PRODUCT_RECOMMEND_WORKFLOW,
                        MainIntentWorkflowMapping.CART_MANAGE_WORKFLOW,
                        MainIntentWorkflowMapping.ORDER_MANAGE_WORKFLOW,
                        MainIntentWorkflowMapping.CLARIFY_WORKFLOW
                ));
    }
}
