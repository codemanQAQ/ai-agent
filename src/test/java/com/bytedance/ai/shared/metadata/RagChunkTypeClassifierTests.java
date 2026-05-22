package com.bytedance.ai.shared.metadata;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RagChunkTypeClassifierTests {

    private final RagChunkTypeClassifier classifier = new RagChunkTypeClassifier();

    // ---- catalog-spu 来源 ----

    @Test
    void catalogSpuChunkZeroWithoutHeadingIsTitle() {
        assertThat(classifier.classify("catalog-spu", 0, List.of(), null))
                .isEqualTo(RagChunkType.TITLE);
    }

    @Test
    void catalogSpuChunkUnderShangPinMiaoShuHeadingIsDesc() {
        assertThat(classifier.classify("catalog-spu", 3, List.of("商品描述"), null))
                .isEqualTo(RagChunkType.DESC);
    }

    @Test
    void catalogSpuChunkUnderSpecHeadingIsAttr() {
        assertThat(classifier.classify("catalog-spu", 5, List.of("规格"), null))
                .isEqualTo(RagChunkType.ATTR);
        assertThat(classifier.classify("catalog-spu", 5, List.of("Spec"), null))
                .isEqualTo(RagChunkType.ATTR);
    }

    @Test
    void catalogSpuChunkUnderReviewHeadingIsReview() {
        assertThat(classifier.classify("catalog-spu", 7, List.of("用户评论"), null))
                .isEqualTo(RagChunkType.REVIEW);
    }

    @Test
    void catalogSpuChunkUnderUnknownHeadingFallsBackToBody() {
        assertThat(classifier.classify("catalog-spu", 2, List.of("促销标语"), null))
                .isEqualTo(RagChunkType.BODY);
    }

    // ---- 非 catalog-spu 来源 ----

    @Test
    void nonCatalogSourceAlwaysReturnsBodyByDefault() {
        assertThat(classifier.classify("markdown", 0, List.of(), null))
                .isEqualTo(RagChunkType.BODY);
        assertThat(classifier.classify("markdown", 4, List.of("规格"), null))
                .as("非 catalog-spu 不走启发式，避免误把通用 markdown 误判")
                .isEqualTo(RagChunkType.BODY);
    }

    // ---- 显式声明优先 ----

    @Test
    void explicitChunkTypeInBlockMetadataOverridesHeuristic() {
        Map<String, Object> blockMetadata = Map.of("chunkType", "IMAGE");
        assertThat(classifier.classify("catalog-spu", 0, List.of(), blockMetadata))
                .isEqualTo(RagChunkType.IMAGE);
    }

    @Test
    void invalidExplicitChunkTypeFallsBackToBody() {
        Map<String, Object> blockMetadata = Map.of("chunkType", "无效类型");
        assertThat(classifier.classify("catalog-spu", 0, List.of(), blockMetadata))
                .as("非法 chunkType 应安全退化到 BODY 而非抛错")
                .isEqualTo(RagChunkType.BODY);
    }

    // ---- 枚举 parseOrBody ----

    @Test
    void parseOrBodyAcceptsLowerAndMixedCase() {
        assertThat(RagChunkType.parseOrBody("title")).isEqualTo(RagChunkType.TITLE);
        assertThat(RagChunkType.parseOrBody(" Attr ")).isEqualTo(RagChunkType.ATTR);
        assertThat(RagChunkType.parseOrBody(null)).isEqualTo(RagChunkType.BODY);
        assertThat(RagChunkType.parseOrBody("")).isEqualTo(RagChunkType.BODY);
    }
}
