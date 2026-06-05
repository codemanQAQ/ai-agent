package com.bytedance.ai.graph.productrecommend;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class LightweightProductRanker {

    public List<ProductRecallCandidate> rank(
            List<ProductRecallCandidate> candidates,
            Map<String, Object> positiveConstraints,
            Map<String, Object> negativeConstraints
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .map(candidate -> candidate.withRankScore(score(candidate, positiveConstraints, negativeConstraints)))
                .sorted(Comparator.comparingDouble(ProductRecallCandidate::rankScore).reversed()
                        .thenComparing(ProductRecallCandidate::title, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    private double score(
            ProductRecallCandidate candidate,
            Map<String, Object> positiveConstraints,
            Map<String, Object> negativeConstraints
    ) {
        double recallScore = candidate.rankScore() > 0 ? candidate.rankScore() : candidate.rawScore();
        double constraintMatchScore = candidate.matchedSlots().size() * 0.05d;
        double sourceBoost = sourceBoost(candidate.source());
        double stockBoost = stockBoost(candidate.stock());
        double priceBoost = priceBoost(candidate.price(), positiveConstraints);
        double negativePenalty = negativePenalty(candidate, negativeConstraints);
        return recallScore + constraintMatchScore + sourceBoost + stockBoost + priceBoost - negativePenalty;
    }

    private double sourceBoost(ProductRecallSource source) {
        if (source == null) {
            return 0.0d;
        }
        return switch (source) {
            case CATALOG_FILTER -> 0.18d;
            case IMAGE_VECTOR -> 0.16d;
            case RAG_CHUNK -> 0.14d;
            case CATALOG_KEYWORD -> 0.1d;
            case HISTORY_SNAPSHOT -> 0.08d;
            case PREFERENCE -> 0.06d;
        };
    }

    private double stockBoost(Integer stock) {
        if (stock == null) {
            return 0.0d;
        }
        if (stock <= 0) {
            return -0.5d;
        }
        return Math.min(0.08d, stock * 0.005d);
    }

    private double priceBoost(BigDecimal price, Map<String, Object> positiveConstraints) {
        BigDecimal maxPrice = decimal(firstPresent(positiveConstraints, "priceMax", "maxPrice", "预算", "价格上限"));
        if (price == null || maxPrice == null || maxPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return 0.0d;
        }
        if (price.compareTo(maxPrice) > 0) {
            return -0.15d;
        }
        return 0.05d;
    }

    private double negativePenalty(ProductRecallCandidate candidate, Map<String, Object> negativeConstraints) {
        if (negativeConstraints == null || negativeConstraints.isEmpty()) {
            return 0.0d;
        }
        return candidate.evidence().stream()
                .filter(evidence -> evidence.metadata() != null && !evidence.metadata().isEmpty())
                .anyMatch(evidence -> evidence.metadata().toString().contains("negative"))
                ? 0.1d
                : 0.0d;
    }

    private Object firstPresent(Map<String, Object> values, String... keys) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            if (values.containsKey(key) && values.get(key) != null) {
                return values.get(key);
            }
        }
        return null;
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value).replaceAll("[^0-9.]", ""));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
