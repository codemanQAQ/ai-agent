package com.bytedance.ai.agent.api.events;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.api.Slot;

public record IntentDetectedPayload(
        IntentType intent,
        double confidence,
        String source,
        Slot slots
) {
}
