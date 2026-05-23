package com.bytedance.ai.agent.tool;

import com.bytedance.ai.agent.api.IntentType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistryTests {

    @Test
    void plansCallbacksByIntent() {
        AgentToolCallback search = callback("search_products", IntentType.RECOMMEND_VAGUE, IntentType.FILTER_BY_ATTR);
        AgentToolCallback compare = callback("compare_products", IntentType.COMPARE);

        ToolRegistry registry = new ToolRegistry(List.of(search, compare));

        assertThat(registry.plan(IntentType.FILTER_BY_ATTR)).containsExactly(search);
        assertThat(registry.plan(IntentType.COMPARE)).containsExactly(compare);
        assertThat(registry.plan(IntentType.OUT_OF_SCOPE)).isEmpty();
    }

    private AgentToolCallback callback(String name, IntentType... handles) {
        return new AgentToolCallback() {
            @Override
            public Set<IntentType> handles() {
                return Set.of(handles);
            }

            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description(name)
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return "{}";
            }
        };
    }
}
