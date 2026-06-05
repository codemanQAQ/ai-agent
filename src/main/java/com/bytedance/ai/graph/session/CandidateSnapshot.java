package com.bytedance.ai.graph.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record CandidateSnapshot(
        List<String> productIds,
        List<CandidateSnapshotItem> items,
        Instant updatedAt
) {

    public CandidateSnapshot {
        items = items == null ? List.of() : List.copyOf(items);
        productIds = normalizeProductIds(productIds, items);
    }

    public CandidateSnapshot(List<String> productIds, Instant updatedAt) {
        this(productIds, minimalItems(productIds), updatedAt);
    }

    public static CandidateSnapshot empty() {
        return new CandidateSnapshot(List.of(), List.of(), null);
    }

    private static List<String> normalizeProductIds(List<String> productIds, List<CandidateSnapshotItem> items) {
        Set<String> normalized = new LinkedHashSet<>();
        if (productIds != null) {
            productIds.stream()
                    .filter(CandidateSnapshot::hasText)
                    .forEach(normalized::add);
        }
        if (items != null) {
            items.stream()
                    .map(CandidateSnapshotItem::productId)
                    .filter(CandidateSnapshot::hasText)
                    .forEach(normalized::add);
        }
        return List.copyOf(normalized);
    }

    private static List<CandidateSnapshotItem> minimalItems(List<String> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return List.of();
        }
        List<CandidateSnapshotItem> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int rank = 1;
        for (String productId : productIds) {
            if (hasText(productId) && seen.add(productId)) {
                result.add(new CandidateSnapshotItem(rank++, productId, null, null, null, null, null, null, null, null));
            }
        }
        return List.copyOf(result);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
