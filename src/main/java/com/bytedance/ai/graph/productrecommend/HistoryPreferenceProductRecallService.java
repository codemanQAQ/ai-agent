package com.bytedance.ai.graph.productrecommend;

import com.bytedance.ai.graph.session.CandidateSnapshot;
import com.bytedance.ai.graph.session.CandidateSnapshotItem;
import com.bytedance.ai.graph.session.UnifiedQueryContext;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class HistoryPreferenceProductRecallService implements ProductRecallService {

    @Override
    public ProductRecallSource source() {
        return ProductRecallSource.HISTORY_SNAPSHOT;
    }

    @Override
    public List<ProductRecallCandidate> recall(ProductRecallRequest request) {
        UnifiedQueryContext context = request.queryContext();
        if (context == null) {
            return List.of();
        }
        CandidateSnapshot snapshot = context.candidateSnapshot();
        if (snapshot == null || snapshot.items().isEmpty()) {
            return List.of();
        }
        return snapshot.items().stream()
                .limit(request.limit())
                .map(this::toCandidate)
                .toList();
    }

    private ProductRecallCandidate toCandidate(CandidateSnapshotItem item) {
        double score = item.rank() <= 0 ? 0.3d : Math.max(0.1d, 0.6d - item.rank() * 0.03d);
        return new ProductRecallCandidate(
                item.productId(),
                null,
                item.skuId(),
                item.externalRef(),
                item.title(),
                null,
                List.of(),
                item.price(),
                null,
                item.imageUrl(),
                source(),
                score,
                score,
                Map.of("snapshotRank", item.rank()),
                List.of(new ProductRecallEvidence(
                        source(),
                        "candidate_snapshot",
                        item.title(),
                        item.reason(),
                        null,
                        null,
                        item.productId(),
                        Map.of("rank", item.rank())
                ))
        );
    }
}
