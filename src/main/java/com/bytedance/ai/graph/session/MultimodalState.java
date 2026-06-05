package com.bytedance.ai.graph.session;

import java.util.List;
import java.util.Map;

public record MultimodalState(
        Map<String, Object> current,
        List<Map<String, Object>> history
) {

    public MultimodalState {
        current = current == null ? Map.of() : Map.copyOf(current);
        history = history == null ? List.of() : List.copyOf(history);
    }

    public static MultimodalState empty() {
        return new MultimodalState(Map.of(), List.of());
    }
}
