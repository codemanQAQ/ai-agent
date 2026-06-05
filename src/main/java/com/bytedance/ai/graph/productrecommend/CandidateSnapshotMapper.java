package com.bytedance.ai.graph.productrecommend;

import com.bytedance.ai.graph.session.CandidateSnapshot;
import com.bytedance.ai.graph.session.CandidateSnapshotItem;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;

@Component
public class CandidateSnapshotMapper {

    public CandidateSnapshot toSnapshot(List<ProductRecallCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return CandidateSnapshot.empty();
        }
        List<CandidateSnapshotItem> items = IntStream.range(0, candidates.size())
                .mapToObj(index -> toItem(index + 1, candidates.get(index)))
                .toList();
        return new CandidateSnapshot(List.of(), items, Instant.now());
    }

    private CandidateSnapshotItem toItem(int rank, ProductRecallCandidate candidate) {
        return new CandidateSnapshotItem(
                rank,
                candidate.productId(),
                candidate.skuId(),
                candidate.externalRef(),
                candidate.title(),
                null,
                candidate.price(),
                candidate.imageUrl(),
                candidate.source(),
                reason(candidate)
        );
    }

    private String reason(ProductRecallCandidate candidate) {
        if (candidate.evidence().isEmpty()) {
            return null;
        }
        ProductRecallEvidence evidence = candidate.evidence().getFirst();
        if (evidence.content() != null && !evidence.content().isBlank()) {
            return evidence.content();
        }
        return evidence.title();
    }
}
