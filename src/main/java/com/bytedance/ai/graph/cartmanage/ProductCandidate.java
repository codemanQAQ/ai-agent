package com.bytedance.ai.graph.cartmanage;

import java.math.BigDecimal;

public record ProductCandidate(
        String productId,
        String skuId,
        String productName,
        BigDecimal price,
        String brief,
        String spec,
        String externalRef
) {
}
