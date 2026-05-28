package com.bytedance.ai.graph.cartmanage;

import java.util.List;

public interface ProductCatalogResolver {

    List<ProductCandidate> searchCandidates(String productName, int limit);

    static ProductCatalogResolver empty() {
        return (productName, limit) -> List.of();
    }
}
