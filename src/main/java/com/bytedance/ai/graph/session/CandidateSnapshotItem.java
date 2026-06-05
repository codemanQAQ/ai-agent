package com.bytedance.ai.graph.session;

import com.bytedance.ai.graph.productrecommend.ProductRecallSource;

import java.math.BigDecimal;

public record CandidateSnapshotItem(
        int rank,
        String productId,
        String skuId,
        String externalRef,
        String title,
        String spec,
        BigDecimal price,
        String imageUrl,
        ProductRecallSource source,
        String reason
) {
}
