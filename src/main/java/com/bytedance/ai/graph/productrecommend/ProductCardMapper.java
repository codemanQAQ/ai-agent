package com.bytedance.ai.graph.productrecommend;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ProductCardMapper {

    public List<ProductCard> toCards(List<ProductRecallCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .map(this::toCard)
                .toList();
    }

    private ProductCard toCard(ProductRecallCandidate candidate) {
        return new ProductCard(
                candidate.productId(),
                candidate.skuId(),
                candidate.externalRef(),
                candidate.title(),
                candidate.brand(),
                candidate.price(),
                priceMax(candidate),
                candidate.stock(),
                candidate.imageUrl(),
                null,
                recommendReason(candidate),
                candidate.evidence()
        );
    }

    /** 多规格商品的价格上限（随 matchedSlots 透传）；与展示价相同或缺失时返回 null（前端按单价显示）。 */
    private java.math.BigDecimal priceMax(ProductRecallCandidate candidate) {
        Object raw = candidate.matchedSlots() == null ? null : candidate.matchedSlots().get("priceMax");
        java.math.BigDecimal max = switch (raw) {
            case java.math.BigDecimal b -> b;
            case Number n -> java.math.BigDecimal.valueOf(n.doubleValue());
            case String s -> parseDecimal(s);
            case null, default -> null;
        };
        if (max == null || candidate.price() == null || max.compareTo(candidate.price()) <= 0) {
            return null;
        }
        return max;
    }

    private java.math.BigDecimal parseDecimal(String s) {
        try {
            return new java.math.BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String recommendReason(ProductRecallCandidate candidate) {
        if (candidate.evidence().isEmpty()) {
            return "匹配当前推荐条件。";
        }
        ProductRecallEvidence evidence = candidate.evidence().getFirst();
        if (evidence.content() != null && !evidence.content().isBlank()) {
            return evidence.content();
        }
        if (evidence.title() != null && !evidence.title().isBlank()) {
            return evidence.title();
        }
        return "匹配当前推荐条件。";
    }
}
