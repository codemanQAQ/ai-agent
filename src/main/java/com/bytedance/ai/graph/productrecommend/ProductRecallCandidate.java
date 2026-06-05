package com.bytedance.ai.graph.productrecommend;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ProductRecallCandidate(
        String productId,
        String spuId,
        String skuId,
        String externalRef,
        String title,
        String brand,
        List<String> categoryPath,
        BigDecimal price,
        Integer stock,
        String imageUrl,
        ProductRecallSource source,
        double rawScore,
        double rankScore,
        Map<String, Object> matchedSlots,
        List<ProductRecallEvidence> evidence
) {

    public ProductRecallCandidate {
        categoryPath = categoryPath == null ? List.of() : List.copyOf(categoryPath);
        matchedSlots = matchedSlots == null ? Map.of() : Map.copyOf(matchedSlots);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public ProductRecallCandidate withRankScore(double newRankScore) {
        return new ProductRecallCandidate(
                productId,
                spuId,
                skuId,
                externalRef,
                title,
                brand,
                categoryPath,
                price,
                stock,
                imageUrl,
                source,
                rawScore,
                newRankScore,
                matchedSlots,
                evidence
        );
    }
}
