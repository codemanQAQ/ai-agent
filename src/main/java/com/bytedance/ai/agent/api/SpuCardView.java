package com.bytedance.ai.agent.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * Agent turn 内用于客户端卡片渲染的 SPU 瘦视图。
 */
public record SpuCardView(
        Long spuId,
        String externalRef,
        String title,
        String brand,
        String image,
        BigDecimal priceMin,
        BigDecimal priceMax,
        Integer stock,
        Double score,
        List<String> badges,
        List<String> reasons,
        String refId
) {
    public SpuCardView {
        badges = badges == null ? List.of() : List.copyOf(badges);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
