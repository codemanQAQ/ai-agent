package com.bytedance.ai.graph.session;

import java.util.List;

public record UnifiedQueryScope(
        List<String> productIds,
        List<String> externalRefs,
        List<Long> catalogSpuIds
) {

    public UnifiedQueryScope {
        productIds = productIds == null ? List.of() : List.copyOf(productIds);
        externalRefs = externalRefs == null ? List.of() : List.copyOf(externalRefs);
        catalogSpuIds = catalogSpuIds == null ? List.of() : List.copyOf(catalogSpuIds);
    }

    public static UnifiedQueryScope empty() {
        return new UnifiedQueryScope(List.of(), List.of(), List.of());
    }
}
