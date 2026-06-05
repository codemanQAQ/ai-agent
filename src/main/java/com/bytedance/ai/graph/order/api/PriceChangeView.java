package com.bytedance.ai.graph.order.api;

import java.math.BigDecimal;

public record PriceChangeView(
        Long spuId,
        String externalRef,
        String title,
        BigDecimal cartUnitPrice,
        BigDecimal currentUnitPrice
) {
}
