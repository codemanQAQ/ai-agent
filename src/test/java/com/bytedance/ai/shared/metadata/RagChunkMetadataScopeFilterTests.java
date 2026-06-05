package com.bytedance.ai.shared.metadata;

import com.bytedance.ai.shared.support.RagJsonCodec;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class RagChunkMetadataScopeFilterTests {

    private final RagChunkMetadataHelper helper = new RagChunkMetadataHelper(
            new RagJsonCodec(JsonMapper.builder().build())
    );

    @Test
    void matchesProductScopeFilterFields() {
        RagChunkMetadataView view = helper.parse("""
                {"externalRef":"p_beauty_001","productId":"p_beauty_001","catalogSpuId":1001,"brand":"BrandA","chunkType":"OFFICIAL_FAQ"}
                """);

        assertThat(helper.matches(
                "catalog://spu/1001",
                view,
                RagSearchFilter.of(
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("p_beauty_001"),
                        List.of("p_beauty_001"),
                        List.of(1001L),
                        List.of(RagChunkType.OFFICIAL_FAQ)
                )
        )).isTrue();

        assertThat(helper.matches(
                "catalog://spu/1001",
                view,
                RagSearchFilter.of(
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("p_beauty_002"),
                        List.of(),
                        List.of(),
                        List.of()
                )
        )).isFalse();

        assertThat(helper.matches(
                "catalog://spu/1001",
                view,
                RagSearchFilter.of(
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(RagChunkType.USER_REVIEW)
                )
        )).isFalse();
    }
}
