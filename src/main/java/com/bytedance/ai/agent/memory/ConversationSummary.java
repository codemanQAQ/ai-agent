package com.bytedance.ai.agent.memory;

import java.util.Optional;

public record ConversationSummary(
        Optional<String> summary,
        Integer messageCount,
        String model
) {
    public ConversationSummary {
        summary = summary == null ? Optional.empty() : summary;
    }

    public static ConversationSummary empty() {
        return new ConversationSummary(Optional.empty(), null, null);
    }
}
