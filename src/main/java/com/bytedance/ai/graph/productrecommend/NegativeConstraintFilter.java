package com.bytedance.ai.graph.productrecommend;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class NegativeConstraintFilter {

    public ProductCandidateFilterResult filter(
            List<ProductRecallCandidate> candidates,
            Map<String, Object> negativeConstraints
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return new ProductCandidateFilterResult(List.of(), List.of());
        }
        if (negativeConstraints == null || negativeConstraints.isEmpty()) {
            return new ProductCandidateFilterResult(candidates, List.of());
        }

        List<ProductRecallCandidate> kept = new ArrayList<>();
        List<ProductCandidateExclusion> exclusions = new ArrayList<>();
        for (ProductRecallCandidate candidate : candidates) {
            String reason = exclusionReason(candidate, negativeConstraints);
            if (StringUtils.hasText(reason)) {
                exclusions.add(ProductCandidateExclusion.of(candidate, reason));
            } else {
                kept.add(candidate);
            }
        }
        return new ProductCandidateFilterResult(kept, exclusions);
    }

    private String exclusionReason(ProductRecallCandidate candidate, Map<String, Object> negativeConstraints) {
        if (containsAny(candidate.brand(), values(negativeConstraints, "brand", "brands", "品牌"))) {
            return "命中排除品牌";
        }
        if (containsAny(String.join("/", candidate.categoryPath()), values(negativeConstraints, "category", "categories", "类目", "品类"))) {
            return "命中排除类目";
        }
        BigDecimal maxPrice = decimal(firstPresent(
                negativeConstraints,
                "priceMax",
                "maxPrice",
                "price",
                "budget",
                "预算",
                "预算上限",
                "价格上限"
        ));
        if (maxPrice != null && candidate.price() != null && candidate.price().compareTo(maxPrice) > 0) {
            return "超过负向价格上限";
        }
        List<String> keywords = values(
                negativeConstraints,
                "attributes",
                "keywords",
                "tags",
                "ingredient",
                "ingredients",
                "成分",
                "reviewSignals",
                "评价信号",
                "差评",
                "不要"
        );
        if (containsAny(candidate.title(), keywords)
                || containsAny(String.join("/", candidate.categoryPath()), keywords)
                || evidenceContainsAny(candidate.evidence(), keywords)) {
            return "命中排除关键词";
        }
        return null;
    }

    private boolean evidenceContainsAny(List<ProductRecallEvidence> evidence, List<String> keywords) {
        if (evidence == null || evidence.isEmpty()) {
            return false;
        }
        for (ProductRecallEvidence item : evidence) {
            if (containsAny(item.title(), keywords) || containsAny(item.content(), keywords)) {
                return true;
            }
            if (item.metadata() != null && containsAny(item.metadata().toString(), keywords)) {
                return true;
            }
        }
        return false;
    }

    private Object firstPresent(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            if (values.containsKey(key) && values.get(key) != null) {
                return values.get(key);
            }
        }
        return null;
    }

    private List<String> values(Map<String, Object> values, String... keys) {
        List<String> result = new ArrayList<>();
        for (String key : keys) {
            Object value = firstPresent(values, key);
            if (value instanceof List<?> list) {
                list.stream()
                        .filter(item -> item != null && StringUtils.hasText(String.valueOf(item)))
                        .map(String::valueOf)
                        .forEach(result::add);
            } else if (value instanceof Map<?, ?> map) {
                map.values().stream()
                        .filter(item -> item != null && StringUtils.hasText(String.valueOf(item)))
                        .map(String::valueOf)
                        .forEach(result::add);
            } else if (value != null && StringUtils.hasText(String.valueOf(value))) {
                result.add(String.valueOf(value));
            }
        }
        return List.copyOf(result);
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (!StringUtils.hasText(text) || keywords == null || keywords.isEmpty()) {
            return false;
        }
        String normalizedText = normalize(text);
        return keywords.stream()
                .filter(StringUtils::hasText)
                .map(this::normalize)
                .anyMatch(normalizedText::contains);
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof Map<?, ?> map) {
            Object max = firstPresentFromRawMap(map, "max", "maxPrice", "priceMax", "上限");
            if (max != null) {
                return decimal(max);
            }
        }
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value).replaceAll("[^0-9.]", ""));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private Object firstPresentFromRawMap(Map<?, ?> values, String... keys) {
        for (String key : keys) {
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                if (entry.getKey() != null && key.equals(String.valueOf(entry.getKey())) && entry.getValue() != null) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }
}
