package com.bytedance.ai.agent.slot;

import com.bytedance.ai.agent.api.Slot;
import com.bytedance.ai.retrieval.spi.ProductSearchHit;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NegationRerankFilterTests {

    private final RagJsonCodec jsonCodec = new RagJsonCodec(JsonMapper.builder().build());
    private final NegationRerankFilter filter = new NegationRerankFilter(noChatModel(), jsonCodec);

    @Test
    void emptyMustNotKeepsAllHits() {
        List<ProductSearchHit> hits = List.of(hit("SPU-1", "无酒精配方"));
        NegationRerankFilter.Result r = filter.apply(hits, Slot.MustNot.empty());
        assertThat(r.keepHits()).hasSize(1);
        assertThat(r.excludedFacets()).isEmpty();
    }

    @Test
    void localFallbackExcludesHitsContainingForbiddenIngredient() {
        // 注意：本地降级是粗暴的 substring contains，"无酒精"也会被命中；
        // 实际生产路径靠 LLM rerank 处理语义否定，这里只验证 substring 匹配确实能剔含禁用词的命中。
        List<ProductSearchHit> hits = List.of(
                hit("SPU-1", "含酒精成分的清爽型防晒"),
                hit("SPU-2", "玻尿酸保湿型清爽防晒"),
                hit("SPU-3", "含香精和酒精的喷雾")
        );
        Slot.MustNot mn = new Slot.MustNot(List.of(), List.of(), List.of("酒精"));
        NegationRerankFilter.Result r = filter.apply(hits, mn);
        assertThat(r.keepHits()).extracting(ProductSearchHit::externalRef).containsExactly("SPU-2");
        assertThat(r.excludedFacets()).contains("酒精");
    }

    @Test
    void parseFromLlmJsonDropsTrueContains() {
        List<ProductSearchHit> hits = List.of(
                hit("SPU-1", "A"),
                hit("SPU-2", "B"),
                hit("SPU-3", "C")
        );
        Slot.MustNot mn = new Slot.MustNot(List.of(), List.of(), List.of("酒精"));
        NegationRerankFilter.Result r = filter.parse(hits, mn, """
                {"verdicts":[
                  {"index":0,"contains":true,"reason":"含酒精"},
                  {"index":1,"contains":false},
                  {"index":2,"contains":true,"reason":"标签里有酒精"}
                ]}
                """);
        assertThat(r.keepHits()).extracting(ProductSearchHit::externalRef).containsExactly("SPU-2");
        assertThat(r.excludedFacets()).containsExactlyInAnyOrder("含酒精", "标签里有酒精");
    }

    @Test
    void invalidLlmOutputKeepsAllHits() {
        List<ProductSearchHit> hits = List.of(hit("SPU-1", "A"));
        Slot.MustNot mn = new Slot.MustNot(List.of(), List.of(), List.of("酒精"));
        NegationRerankFilter.Result r = filter.parse(hits, mn, "not a json");
        assertThat(r.keepHits()).hasSize(1);
    }

    private ProductSearchHit hit(String externalRef, String snippet) {
        return new ProductSearchHit(null, 1L, externalRef, 0.5d, "TITLE", snippet, Map.of());
    }

    private static ObjectProvider<ChatModel> noChatModel() {
        return new ObjectProvider<>() {
            @Override public ChatModel getObject(Object... args) { return null; }
            @Override public ChatModel getIfAvailable() { return null; }
            @Override public ChatModel getIfUnique() { return null; }
            @Override public ChatModel getObject() { return null; }
        };
    }
}
