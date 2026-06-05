package com.bytedance.ai.graph.productrecommend;

import com.bytedance.ai.graph.session.UnifiedQueryContext;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ProductCandidatePostProcessor {

    private final RrfFusionService fusionService;
    private final PositiveConstraintFilter positiveConstraintFilter;
    private final NegativeConstraintFilter negativeConstraintFilter;
    private final LightweightProductRanker ranker;
    private final CandidateSnapshotMapper snapshotMapper;

    public ProductCandidatePostProcessor(
            RrfFusionService fusionService,
            NegativeConstraintFilter negativeConstraintFilter,
            LightweightProductRanker ranker,
            CandidateSnapshotMapper snapshotMapper
    ) {
        this(fusionService, new PositiveConstraintFilter(), negativeConstraintFilter, ranker, snapshotMapper);
    }

    @Autowired
    public ProductCandidatePostProcessor(
            RrfFusionService fusionService,
            PositiveConstraintFilter positiveConstraintFilter,
            NegativeConstraintFilter negativeConstraintFilter,
            LightweightProductRanker ranker,
            CandidateSnapshotMapper snapshotMapper
    ) {
        this.fusionService = fusionService;
        this.positiveConstraintFilter = positiveConstraintFilter;
        this.negativeConstraintFilter = negativeConstraintFilter;
        this.ranker = ranker;
        this.snapshotMapper = snapshotMapper;
    }

    public ProductCandidatePostProcessResult process(
            List<ProductRecallCandidate> recalledCandidates,
            UnifiedQueryContext queryContext,
            int limit
    ) {
        return process(recalledCandidates, queryContext, null, limit);
    }

    public ProductCandidatePostProcessResult process(
            List<ProductRecallCandidate> recalledCandidates,
            UnifiedQueryContext queryContext,
            ProductRecallPlan recallPlan,
            int limit
    ) {
        Map<String, Object> positiveConstraints = queryContext == null ? Map.of() : queryContext.positiveConstraints();
        Map<String, Object> negativeConstraints = queryContext == null ? Map.of() : queryContext.negativeConstraints();
        List<ProductRecallCandidate> fused = fusionService.fuse(recalledCandidates);
        ProductCandidateFilterResult positiveFiltered = recallPlan != null && recallPlan.enforcePositiveConstraints()
                ? positiveConstraintFilter.filter(fused, positiveConstraints)
                : new ProductCandidateFilterResult(fused, List.of());
        ProductCandidateFilterResult negativeFiltered = negativeConstraintFilter.filter(positiveFiltered.candidates(), negativeConstraints);
        List<ProductRecallCandidate> boosted = boostMultiTurnSnapshotCandidates(negativeFiltered.candidates(), queryContext, recallPlan);
        List<ProductRecallCandidate> ranked = ranker.rank(boosted, positiveConstraints, negativeConstraints);
        List<ProductRecallCandidate> topCandidates = ranked.stream()
                .limit(limit <= 0 ? 5 : limit)
                .toList();
        return new ProductCandidatePostProcessResult(
                topCandidates,
                concat(positiveFiltered.exclusions(), negativeFiltered.exclusions()),
                snapshotMapper.toSnapshot(topCandidates)
        );
    }

    private List<ProductRecallCandidate> boostMultiTurnSnapshotCandidates(
            List<ProductRecallCandidate> candidates,
            UnifiedQueryContext queryContext,
            ProductRecallPlan recallPlan
    ) {
        if (candidates == null || candidates.isEmpty()
                || recallPlan == null
                || recallPlan.subScene() != ProductRecommendSubScene.MULTI_TURN_REFINE
                || queryContext == null
                || queryContext.candidateSnapshot().productIds().isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        Set<String> snapshotProductIds = new LinkedHashSet<>(queryContext.candidateSnapshot().productIds());
        return candidates.stream()
                .map(candidate -> {
                    boolean fromSnapshot = candidate.source() == ProductRecallSource.HISTORY_SNAPSHOT
                            || snapshotProductIds.contains(candidate.productId())
                            || snapshotProductIds.contains(candidate.externalRef());
                    if (!fromSnapshot) {
                        return candidate;
                    }
                    return candidate.withRankScore(Math.max(candidate.rankScore(), candidate.rawScore()) + 0.7d);
                })
                .toList();
    }

    private List<ProductCandidateExclusion> concat(
            List<ProductCandidateExclusion> first,
            List<ProductCandidateExclusion> second
    ) {
        if ((first == null || first.isEmpty()) && (second == null || second.isEmpty())) {
            return List.of();
        }
        java.util.ArrayList<ProductCandidateExclusion> result = new java.util.ArrayList<>();
        if (first != null) {
            result.addAll(first);
        }
        if (second != null) {
            result.addAll(second);
        }
        return List.copyOf(result);
    }
}
