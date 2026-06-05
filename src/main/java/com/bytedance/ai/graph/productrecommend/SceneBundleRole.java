package com.bytedance.ai.graph.productrecommend;

import java.util.Map;

public record SceneBundleRole(
        String roleId,
        String name,
        String query,
        Map<String, Object> constraints,
        String reason
) {

    public SceneBundleRole {
        constraints = constraints == null ? Map.of() : Map.copyOf(constraints);
    }
}
