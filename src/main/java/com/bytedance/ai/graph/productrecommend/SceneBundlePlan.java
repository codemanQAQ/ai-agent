package com.bytedance.ai.graph.productrecommend;

import java.util.List;

public record SceneBundlePlan(
        String scenario,
        String audience,
        String usageContext,
        List<SceneBundleRole> roles,
        String reason
) {

    public SceneBundlePlan {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
