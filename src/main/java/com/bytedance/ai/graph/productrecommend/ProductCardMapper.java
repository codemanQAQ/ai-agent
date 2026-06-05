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
                candidate.stock(),
                candidate.imageUrl(),
                null,
                recommendReason(candidate),
                candidate.evidence()
        );
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
