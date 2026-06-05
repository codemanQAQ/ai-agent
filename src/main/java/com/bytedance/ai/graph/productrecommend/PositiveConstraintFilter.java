package com.bytedance.ai.graph.productrecommend;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PositiveConstraintFilter {

    public ProductCandidateFilterResult filter(
            List<ProductRecallCandidate> candidates,
            Map<String, Object> positiveConstraints
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return new ProductCandidateFilterResult(List.of(), List.of());
        }
        if (positiveConstraints == null || positiveConstraints.isEmpty()) {
            return new ProductCandidateFilterResult(candidates, List.of());
        }

        List<ProductRecallCandidate> kept = new ArrayList<>();
        List<ProductCandidateExclusion> exclusions = new ArrayList<>();
        for (ProductRecallCandidate candidate : candidates) {
            String reason = mismatchReason(candidate, positiveConstraints);
            if (StringUtils.hasText(reason)) {
                exclusions.add(ProductCandidateExclusion.of(candidate, reason));
            } else {
                kept.add(candidate);
            }
        }
        return new ProductCandidateFilterResult(kept, exclusions);
    }

    private String mismatchReason(ProductRecallCandidate candidate, Map<String, Object> constraints) {
        if (!matchesAny(candidate.productId(), values(constraints, "productIds", "productId"))) {
            return "不满足商品范围条件";
        }
        if (!matchesAny(candidate.externalRef(), values(constraints, "externalRefs", "externalRef", "productRefs"))) {
            return "不满足商品范围条件";
        }
        // 品牌/子品牌（如"特仑苏"）常出现在标题而非 brand 字段（brand="蒙牛"），
        // 故品牌约束对 brand 或 title 任一命中即可，避免精确商品名查询被全部过滤。
        if (!matchesTextAny(firstPresent(constraints, "brand", "品牌"), candidate.brand(), candidate.title())) {
            return "不满足品牌条件";
        }
        if (!matchesTextAny(firstPresent(constraints, "category", "categoryPath", "类目"),
                String.join("/", candidate.categoryPath()), candidate.title())) {
            return "不满足类目条件";
        }
        if (!matchesPrice(candidate, constraints)) {
            return "不满足价格条件";
        }
        if (!matchesStock(candidate, constraints)) {
            return "不满足库存条件";
        }
        if (!matchesSpecs(candidate, firstPresent(constraints, "specs", "skuSpec", "规格"))) {
            return "不满足规格条件";
        }
        return null;
    }

    private boolean matchesText(String actual, Object expected) {
        String expectedText = text(expected);
        if (!StringUtils.hasText(expectedText)) {
            return true;
        }
        return containsIgnoreCase(actual, expectedText);
    }

    /** 期望值为空则放行；否则只要任一候选字段包含期望值即视为命中。 */
    private boolean matchesTextAny(Object expected, String... actuals) {
        String expectedText = text(expected);
        if (!StringUtils.hasText(expectedText)) {
            return true;
        }
        for (String actual : actuals) {
            if (containsIgnoreCase(actual, expectedText)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesPrice(ProductRecallCandidate candidate, Map<String, Object> constraints) {
        BigDecimal price = candidate.price();
        BigDecimal min = decimal(firstPresent(constraints, "priceMin", "minPrice", "价格下限"));
        BigDecimal max = decimal(firstPresent(constraints, "priceMax", "maxPrice", "预算", "价格上限"));
        if (price == null) {
            return min == null && max == null;
        }
        if (min != null && price.compareTo(min) < 0) {
            return false;
        }
        return max == null || price.compareTo(max) <= 0;
    }

    private boolean matchesStock(ProductRecallCandidate candidate, Map<String, Object> constraints) {
        Object onlyInStock = firstPresent(constraints, "inStock", "onlyInStock", "有货");
        if (onlyInStock == null || !Boolean.parseBoolean(String.valueOf(onlyInStock))) {
            return true;
        }
        return candidate.stock() != null && candidate.stock() > 0;
    }

    private boolean matchesSpecs(ProductRecallCandidate candidate, Object expectedSpecs) {
        Map<String, Object> specs = map(expectedSpecs);
        if (specs.isEmpty()) {
            return true;
        }
        String searchable = (candidate.matchedSlots().toString() + " " + candidate.evidence()).toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Object> entry : specs.entrySet()) {
            String key = text(entry.getKey());
            String value = text(entry.getValue());
            if (!StringUtils.hasText(value)) {
                continue;
            }
            boolean matched = searchable.contains(value.toLowerCase(Locale.ROOT))
                    && (!StringUtils.hasText(key) || searchable.contains(key.toLowerCase(Locale.ROOT)));
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesAny(String actual, List<String> expectedValues) {
        if (expectedValues == null || expectedValues.isEmpty()) {
            return true;
        }
        if (!StringUtils.hasText(actual)) {
            return false;
        }
        return expectedValues.stream()
                .filter(StringUtils::hasText)
                .anyMatch(expected -> actual.equals(expected) || containsIgnoreCase(actual, expected));
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
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (String key : keys) {
            Object value = firstPresent(values, key);
            if (value instanceof List<?> list) {
                list.stream()
                        .filter(item -> item != null && StringUtils.hasText(String.valueOf(item)))
                        .map(String::valueOf)
                        .forEach(result::add);
            } else if (value != null && StringUtils.hasText(String.valueOf(value))) {
                result.add(String.valueOf(value));
            }
        }
        return List.copyOf(result);
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key != null && item != null) {
                result.put(String.valueOf(key), item);
            }
        });
        return Map.copyOf(result);
    }

    private boolean containsIgnoreCase(String actual, String expected) {
        return StringUtils.hasText(actual)
                && StringUtils.hasText(expected)
                && actual.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        String text = text(value);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return new BigDecimal(text.replaceAll("[^0-9.]", ""));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }
}
