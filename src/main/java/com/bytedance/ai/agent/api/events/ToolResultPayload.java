package com.bytedance.ai.agent.api.events;

import com.bytedance.ai.agent.api.SpuCardView;

import java.util.List;
import java.util.Map;

public record ToolResultPayload(
        String toolName,
        List<SpuCardView> cards,
        Map<String, Object> facetsApplied
) {
    public ToolResultPayload {
        cards = cards == null ? List.of() : List.copyOf(cards);
        facetsApplied = facetsApplied == null ? Map.of() : Map.copyOf(facetsApplied);
    }
}
