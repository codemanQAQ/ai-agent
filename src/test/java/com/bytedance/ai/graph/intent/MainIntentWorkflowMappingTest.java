package com.bytedance.ai.graph.intent;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class MainIntentWorkflowMappingTest {

    @ParameterizedTest
    @EnumSource(MainIntent.class)
    void mapsEveryMainIntentToWorkflow(MainIntent intent) {
        assertThat(MainIntentWorkflowMapping.targetWorkflowOf(intent)).isNotBlank();
    }
}
