package com.bytedance.ai.agent.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * 规则 / LLM 抽取出的商品检索槽位。
 */
public record Slot(
        List<String> must,
        List<String> mustNot,
        PriceRange priceRange,
        String categoryHint,
        List<String> brands,
        String scenario
) {
    public Slot {
        must = copyOrEmpty(must);
        mustNot = copyOrEmpty(mustNot);
        brands = copyOrEmpty(brands);
    }

    public static Slot empty() {
        return new Slot(List.of(), List.of(), null, null, List.of(), null);
    }

    public boolean isEmpty() {
        return must.isEmpty()
                && mustNot.isEmpty()
                && (priceRange == null || priceRange.isEmpty())
                && !hasText(categoryHint)
                && brands.isEmpty()
                && !hasText(scenario);
    }

    private static List<String> copyOrEmpty(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(Slot::hasText)
                .map(String::trim)
                .toList();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record PriceRange(BigDecimal min, BigDecimal max) {
        public boolean isEmpty() {
            return min == null && max == null;
        }
    }
}
