package com.bytedance.ai.graph.session;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CandidateSnapshotReferenceResolver {

    public Map<String, Object> augment(
            Map<String, Object> constraints,
            CandidateSnapshot candidateSnapshot,
            String queryText
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (constraints != null) {
            result.putAll(constraints);
        }
        if (candidateSnapshot == null || candidateSnapshot.items().isEmpty()) {
            return Map.copyOf(result);
        }

        List<Integer> ranks = ranks(result, queryText);
        if (ranks.size() > 1) {
            List<Integer> resolvedRanks = new ArrayList<>();
            for (Integer rank : ranks) {
                CandidateSnapshotItem item = byRank(candidateSnapshot, rank);
                if (item != null) {
                    putListValue(result, "productIds", item.productId());
                    putListValue(result, "externalRefs", item.externalRef());
                    resolvedRanks.add(item.rank());
                }
            }
            if (!resolvedRanks.isEmpty()) {
                result.put("candidateRanks", List.copyOf(resolvedRanks));
                result.put("compareProductIds", result.get("productIds"));
            }
        } else if (ranks.size() == 1) {
            Integer rank = ranks.getFirst();
            CandidateSnapshotItem item = byRank(candidateSnapshot, rank);
            if (item != null) {
                putListValue(result, "productIds", item.productId());
                putListValue(result, "externalRefs", item.externalRef());
                result.put("candidateRank", item.rank());
                putIfPresent(result, "referenceProductId", item.productId());
                putIfPresent(result, "referenceExternalRef", item.externalRef());
                applyCheaperThanReference(result, queryText, item.price());
            }
        } else if (asksForCheaper(queryText)) {
            BigDecimal maxReferencePrice = maxSnapshotPrice(candidateSnapshot);
            if (maxReferencePrice != null && !result.containsKey("priceMax")) {
                result.put("priceMax", maxReferencePrice);
                result.put("priceReference", "candidateSnapshotMaxPrice");
            }
        }

        if (mentionsSimilarity(queryText) && !ranks.isEmpty()) {
            Object productIds = result.get("productIds");
            if (productIds != null) {
                result.put("similarToProductIds", productIds);
            }
        }
        return Map.copyOf(result);
    }

    private List<Integer> ranks(Map<String, Object> constraints, String queryText) {
        List<Integer> fromConstraints = integerList(firstPresent(
                constraints,
                "candidateRanks",
                "candidateIndexes",
                "snapshotRanks",
                "itemIndexes",
                "ranks"
        ));
        if (!fromConstraints.isEmpty()) {
            return fromConstraints;
        }
        Integer singleRank = integer(firstPresent(
                constraints,
                "candidateRank",
                "candidateIndex",
                "snapshotRank",
                "itemIndex",
                "rank",
                "序号"
        ));
        if (singleRank != null && singleRank > 0) {
            return List.of(singleRank);
        }
        return ranksFromText(queryText);
    }

    private List<Integer> ranksFromText(String queryText) {
        String text = normalize(queryText);
        if (text.isEmpty()) {
            return List.of();
        }
        List<Integer> ranks = new ArrayList<>();
        if (containsAny(text, "刚才那个", "刚才的", "这款", "这个", "上一个", "上一款")) {
            ranks.add(1);
        }
        if (containsAny(text, "第一个", "第一款", "第1个", "第1款", "1号", "一号")) {
            ranks.add(1);
        }
        if (containsAny(text, "第二个", "第二款", "第2个", "第2款", "2号", "二号")) {
            ranks.add(2);
        }
        if (containsAny(text, "第三个", "第三款", "第3个", "第3款", "3号", "三号")) {
            ranks.add(3);
        }
        if (containsAny(text, "第四个", "第四款", "第4个", "第4款", "4号", "四号")) {
            ranks.add(4);
        }
        if (containsAny(text, "第五个", "第五款", "第5个", "第5款", "5号", "五号")) {
            ranks.add(5);
        }
        return ranks.stream().distinct().toList();
    }

    private CandidateSnapshotItem byRank(CandidateSnapshot snapshot, int rank) {
        return snapshot.items().stream()
                .filter(item -> item.rank() == rank)
                .findFirst()
                .orElse(null);
    }

    private void applyCheaperThanReference(Map<String, Object> result, String queryText, BigDecimal referencePrice) {
        if (!asksForCheaper(queryText) || referencePrice == null || result.containsKey("priceMax")) {
            return;
        }
        result.put("priceMax", referencePrice);
        result.put("priceReference", "candidateRank");
    }

    private BigDecimal maxSnapshotPrice(CandidateSnapshot snapshot) {
        return snapshot.items().stream()
                .map(CandidateSnapshotItem::price)
                .filter(price -> price != null && price.compareTo(BigDecimal.ZERO) > 0)
                .max(BigDecimal::compareTo)
                .orElse(null);
    }

    private boolean asksForCheaper(String queryText) {
        String text = normalize(queryText);
        return containsAny(text, "更便宜", "便宜点", "低价", "预算低", "价格低", "省钱", "更实惠");
    }

    private boolean mentionsSimilarity(String queryText) {
        String text = normalize(queryText);
        return containsAny(text, "类似", "相似", "差不多", "同款", "接近");
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

    private Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value).replaceAll("[^0-9]", ""));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private List<Integer> integerList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(this::integer)
                    .filter(item -> item != null && item > 0)
                    .distinct()
                    .toList();
        }
        Integer single = integer(value);
        return single == null || single <= 0 ? List.of() : List.of(single);
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private void putListValue(Map<String, Object> target, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        List<String> values = new ArrayList<>();
        Object current = target.get(key);
        if (current instanceof List<?> list) {
            list.stream()
                    .filter(item -> item != null && !String.valueOf(item).isBlank())
                    .map(String::valueOf)
                    .forEach(values::add);
        } else if (current != null && !String.valueOf(current).isBlank()) {
            values.add(String.valueOf(current));
        }
        if (!values.contains(value)) {
            values.add(value);
        }
        target.put(key, List.copyOf(values));
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }
}
