package com.bytedance.ai.graph.productrecommend;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RrfFusionService {

    private static final int RRF_K = 60;

    public List<ProductRecallCandidate> fuse(List<ProductRecallCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<ProductRecallSource, List<ProductRecallCandidate>> bySource = new LinkedHashMap<>();
        for (ProductRecallCandidate candidate : candidates) {
            if (candidate != null) {
                bySource.computeIfAbsent(candidate.source(), ignored -> new ArrayList<>()).add(candidate);
            }
        }

        Map<String, Accumulator> byKey = new LinkedHashMap<>();
        for (Map.Entry<ProductRecallSource, List<ProductRecallCandidate>> entry : bySource.entrySet()) {
            List<ProductRecallCandidate> ranked = entry.getValue().stream()
                    .sorted(Comparator.comparingDouble(ProductRecallCandidate::rawScore).reversed())
                    .toList();
            for (int index = 0; index < ranked.size(); index++) {
                ProductRecallCandidate candidate = ranked.get(index);
                String key = dedupeKey(candidate);
                if (!StringUtils.hasText(key)) {
                    continue;
                }
                double rrfScore = 1.0d / (RRF_K + index + 1);
                byKey.computeIfAbsent(key, ignored -> new Accumulator(candidate))
                        .add(candidate, rrfScore);
            }
        }

        return byKey.values().stream()
                .map(Accumulator::toCandidate)
                .sorted(Comparator.comparingDouble(ProductRecallCandidate::rankScore).reversed())
                .toList();
    }

    private String dedupeKey(ProductRecallCandidate candidate) {
        if (StringUtils.hasText(candidate.productId())) {
            return "product:" + candidate.productId();
        }
        if (StringUtils.hasText(candidate.externalRef())) {
            return "external:" + candidate.externalRef();
        }
        if (StringUtils.hasText(candidate.spuId())) {
            return "spu:" + candidate.spuId();
        }
        if (StringUtils.hasText(candidate.skuId())) {
            return "sku:" + candidate.skuId();
        }
        return null;
    }

    private static final class Accumulator {

        private ProductRecallCandidate best;
        private double score;
        private final Map<String, Object> matchedSlots = new LinkedHashMap<>();
        private final List<ProductRecallEvidence> evidence = new ArrayList<>();

        private Accumulator(ProductRecallCandidate first) {
            this.best = first;
        }

        private void add(ProductRecallCandidate candidate, double rrfScore) {
            if (candidate.rawScore() > best.rawScore()) {
                best = candidate;
            }
            score += rrfScore;
            matchedSlots.putAll(candidate.matchedSlots());
            candidate.evidence().stream()
                    .filter(Objects::nonNull)
                    .forEach(evidence::add);
        }

        private ProductRecallCandidate toCandidate() {
            return new ProductRecallCandidate(
                    best.productId(),
                    best.spuId(),
                    best.skuId(),
                    best.externalRef(),
                    best.title(),
                    best.brand(),
                    best.categoryPath(),
                    best.price(),
                    best.stock(),
                    best.imageUrl(),
                    best.source(),
                    best.rawScore(),
                    score,
                    matchedSlots,
                    evidence
            );
        }
    }
}
