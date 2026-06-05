package com.bytedance.ai.shared.metadata;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RagChunkTypeClassifierTests {

    private final RagChunkTypeClassifier classifier = new RagChunkTypeClassifier();

    @Test
    void classifiesStableEcommerceChunkTypesFromHeadings() {
        assertThat(classifier.classify("catalog-spu", 1, List.of("营销描述"), Map.of()))
                .isEqualTo(RagChunkType.MARKETING_DESCRIPTION);
        assertThat(classifier.classify("catalog-spu", 2, List.of("官方 FAQ"), Map.of()))
                .isEqualTo(RagChunkType.OFFICIAL_FAQ);
        assertThat(classifier.classify("catalog-spu", 3, List.of("用户评价"), Map.of()))
                .isEqualTo(RagChunkType.USER_REVIEW);
        assertThat(classifier.classify("catalog-spu", 4, List.of("用户评价摘要"), Map.of()))
                .isEqualTo(RagChunkType.REVIEW_SUMMARY);
    }
}
