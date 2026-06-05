package com.bytedance.ai.retrieval.service;

import com.bytedance.ai.shared.metadata.RagSearchFilter;
import com.bytedance.ai.shared.metadata.RagChunkType;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagMilvusNativeExpressionBuilderTests {

    private final RagMilvusNativeExpressionBuilder builder =
            new RagMilvusNativeExpressionBuilder(new RagJsonCodec(JsonMapper.builder().build()));

    @Test
    void buildsPositiveOnlyExpression() {
        String expr = builder.build(RagSearchFilter.of("catalog://spu/", List.of("通勤"), null));
        assertThat(expr).contains("LIKE \"catalog://spu/%\"");
        assertThat(expr).contains("json_contains_all");
        assertThat(expr).contains("\"通勤\"");
    }

    @Test
    void buildsMustNotTagsAsNotJsonContainsAny() {
        String expr = builder.build(RagSearchFilter.of(
                null, null, null,
                List.of("含酒精"),
                List.of(),
                List.of()
        ));
        assertThat(expr).contains("not json_contains_any");
        assertThat(expr).contains("\"含酒精\"");
    }

    @Test
    void buildsMustNotBrandsAsNotIn() {
        String expr = builder.build(RagSearchFilter.of(
                null, null, null,
                List.of(),
                List.of("Apple", "Sony"),
                List.of()
        ));
        assertThat(expr).contains("[\"brand\"] not in");
        assertThat(expr).contains("Apple");
        assertThat(expr).contains("Sony");
    }

    @Test
    void buildsMustNotIngredientsAsNegatedLikeChain() {
        String expr = builder.build(RagSearchFilter.of(
                null, null, null,
                List.of(),
                List.of(),
                List.of("酒精", "香精")
        ));
        // 每个成分一条 not LIKE，AND 串联。
        assertThat(expr).containsPattern("not .*LIKE.*\"%酒精%\"");
        assertThat(expr).containsPattern("not .*LIKE.*\"%香精%\"");
        assertThat(expr.split(" AND ")).hasSize(2);
    }

    @Test
    void buildsProductScopeExpression() {
        String expr = builder.build(RagSearchFilter.of(
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of("p_beauty_001"),
                List.of("p_beauty_001"),
                List.of(1001L),
                List.of(RagChunkType.OFFICIAL_FAQ, RagChunkType.USER_REVIEW)
        ));

        assertThat(expr).contains("[\"externalRef\"] in");
        assertThat(expr).contains("[\"productId\"] in");
        assertThat(expr).contains("[\"spuId\"] in");
        assertThat(expr).contains("[\"catalogSpuId\"] in");
        assertThat(expr).contains("[\"chunkType\"] in");
        assertThat(expr).contains("p_beauty_001");
        assertThat(expr).contains("1001");
        assertThat(expr).contains("OFFICIAL_FAQ");
        assertThat(expr).contains("USER_REVIEW");
    }

    @Test
    void emptyFilterReturnsNull() {
        assertThat(builder.build(null)).isNull();
        assertThat(builder.build(RagSearchFilter.of(null, null, null))).isNull();
    }
}
