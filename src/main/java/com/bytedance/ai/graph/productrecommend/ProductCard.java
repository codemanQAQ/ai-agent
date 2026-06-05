package com.bytedance.ai.graph.productrecommend;

import java.math.BigDecimal;
import java.util.List;

public record ProductCard(
        String productId,
        String skuId,
        String externalRef,
        String title,
        String brand,
        BigDecimal price,
        Integer stock,
        String imageUrl,
        String spec,
        String recommendReason,
        List<ProductRecallEvidence> evidence
) {

    public ProductCard {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
