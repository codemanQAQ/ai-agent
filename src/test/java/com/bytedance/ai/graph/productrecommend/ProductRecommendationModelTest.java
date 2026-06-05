package com.bytedance.ai.graph.productrecommend;

import com.bytedance.ai.graph.session.CandidateSnapshot;
import com.bytedance.ai.graph.session.CandidateSnapshotItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProductRecommendationModelTest {

    @Test
    void candidateSnapshotKeepsFullItemsAndDerivedProductIds() {
        CandidateSnapshot snapshot = new CandidateSnapshot(
                List.of(),
                List.of(
                        new CandidateSnapshotItem(
                                1,
                                "p_beauty_001",
                                "sku-1",
                                "external-1",
                                "温和洁面",
                                "150ml",
                                new BigDecimal("89.00"),
                                "image.jpg",
                                ProductRecallSource.CATALOG_KEYWORD,
                                "匹配油皮洁面需求"
                        )
                ),
                Instant.now()
        );

        assertThat(snapshot.productIds()).containsExactly("p_beauty_001");
        assertThat(snapshot.items()).hasSize(1);
        assertThat(snapshot.items().getFirst().rank()).isEqualTo(1);
        assertThat(snapshot.items().getFirst().skuId()).isEqualTo("sku-1");
    }

    @Test
    void candidateSnapshotStillSupportsLegacyProductIdsConstructor() {
        CandidateSnapshot snapshot = new CandidateSnapshot(List.of("p1", "p2", "p1"), Instant.now());

        assertThat(snapshot.productIds()).containsExactly("p1", "p2");
        assertThat(snapshot.items())
                .extracting(CandidateSnapshotItem::productId)
                .containsExactly("p1", "p2");
    }

    @Test
    void recallCandidateAndProductCardNormalizeCollectionFields() {
        ProductRecallEvidence evidence = new ProductRecallEvidence(
                ProductRecallSource.RAG_CHUNK,
                "official_faq",
                "使用方式",
                "适合日常清洁。",
                "chunk-1",
                "parent-1",
                "p1",
                Map.of("chunkType", "official_faq")
        );
        ProductRecallCandidate candidate = new ProductRecallCandidate(
                "p1",
                "spu-1",
                "sku-1",
                "external-1",
                "温和洁面",
                "品牌A",
                List.of("美妆护肤", "洁面"),
                new BigDecimal("89.00"),
                12,
                "image.jpg",
                ProductRecallSource.RAG_CHUNK,
                0.82,
                1.12,
                Map.of("肤质", "油皮"),
                List.of(evidence)
        );
        ProductCard card = new ProductCard(
                candidate.productId(),
                candidate.skuId(),
                candidate.externalRef(),
                candidate.title(),
                candidate.brand(),
                candidate.price(),
                candidate.stock(),
                candidate.imageUrl(),
                "150ml",
                "温和清洁且匹配油皮需求",
                candidate.evidence()
        );

        assertThat(candidate.categoryPath()).containsExactly("美妆护肤", "洁面");
        assertThat(candidate.matchedSlots()).containsEntry("肤质", "油皮");
        assertThat(card.evidence()).containsExactly(evidence);
    }
}
