package com.bytedance.ai.agent.tool;

import com.bytedance.ai.agent.api.IntentType;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ToolRegistry {

    private final List<AgentToolCallback> callbacks;

    public ToolRegistry(List<AgentToolCallback> callbacks) {
        this.callbacks = callbacks == null ? List.of() : List.copyOf(callbacks);
    }

    public List<AgentToolCallback> plan(IntentType intent) {
        if (intent == null) {
            return List.of();
        }
        return callbacks.stream()
                .filter(callback -> callback.handles().contains(intent))
                .toList();
    }
}
