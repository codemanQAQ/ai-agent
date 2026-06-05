package com.bytedance.ai.graph.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UserPreferenceMemoryServiceTest {

    private final UserPreferenceMemoryService service = new UserPreferenceMemoryService();

    @Test
    void feedbackUpdatesPositiveAndNegativePreferenceMemory() {
        UserBehaviorFeedbackResult click = service.accept(new UserBehaviorFeedbackRequest(
                "u1",
                "c1",
                "run-1",
                "p1",
                "sku1",
                null,
                "click",
                1,
                Map.of("source", "recommendation")
        ));
        UserBehaviorFeedbackResult dislike = service.accept(new UserBehaviorFeedbackRequest(
                "u1",
                "c1",
                "run-1",
                "p2",
                null,
                null,
                "not_interested",
                2,
                Map.of()
        ));

        assertThat(click.behaviorType()).isEqualTo(UserBehaviorType.CLICK_PRODUCT);
        assertThat(dislike.behaviorType()).isEqualTo(UserBehaviorType.NOT_INTERESTED);
        Map<String, Object> memory = service.memory("u1");
        assertThat(((List<?>) memory.get("positiveProductIds")).stream().map(String::valueOf).toList())
                .containsExactly("p1");
        assertThat(((List<?>) memory.get("negativeProductIds")).stream().map(String::valueOf).toList())
                .containsExactly("p2");
        assertThat(memory).containsEntry("feedbackCount", 2);
    }
}
