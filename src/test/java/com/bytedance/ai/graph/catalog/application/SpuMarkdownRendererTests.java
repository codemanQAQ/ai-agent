package com.bytedance.ai.graph.catalog.application;

import com.bytedance.ai.graph.catalog.api.CatalogSpuCreateRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpuMarkdownRendererTests {

    private final SpuMarkdownRenderer renderer = new SpuMarkdownRenderer();

    @Test
    void rendersFullSpuWithAllSections() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("color", "黑色");
        CatalogSpuCreateRequest request = new CatalogSpuCreateRequest(
                "SPU-1",
                "测试双肩包",
                "Acme",
                "服装/箱包/双肩包",
                new BigDecimal("199"),
                new BigDecimal("299"),
                10,
                "面向都市通勤场景的轻量双肩包。",
                List.of("https://example.com/1.jpg"),
                "https://example.com/v.mp4",
                List.of(new CatalogSpuCreateRequest.SkuDraft(
                        "SKU-A",
                        spec,
                        new BigDecimal("199"),
                        5
                ))
        );

        String rendered = renderer.render(request);

        assertThat(rendered).contains("# 测试双肩包");
        assertThat(rendered).contains("**品牌**：Acme");
        assertThat(rendered).contains("**类目**：服装/箱包/双肩包");
        assertThat(rendered).contains("**价格**：¥199 ~ ¥299");
        assertThat(rendered).contains("## 商品描述");
        assertThat(rendered).contains("面向都市通勤场景的轻量双肩包");
        assertThat(rendered).contains("## 规格");
        assertThat(rendered).contains("color=黑色");
        assertThat(rendered).contains("¥199");
        assertThat(rendered).contains("库存 5");
    }

    @Test
    void rendersGracefullyWhenOptionalFieldsMissing() {
        CatalogSpuCreateRequest request = new CatalogSpuCreateRequest(
                "SPU-2",
                "极简商品",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(new CatalogSpuCreateRequest.SkuDraft("SKU-Z", null, new BigDecimal("9.9"), 1))
        );

        String rendered = renderer.render(request);

        assertThat(rendered).contains("# 极简商品");
        assertThat(rendered).doesNotContain("**品牌**");
        assertThat(rendered).doesNotContain("**类目**");
        assertThat(rendered).doesNotContain("**价格**");
        assertThat(rendered).contains("(暂无描述)");
        assertThat(rendered).contains("默认规格");
        assertThat(rendered).contains("¥9.9");
    }

    @Test
    void rendersSinglePointPriceWhenMinEqualsMax() {
        CatalogSpuCreateRequest request = new CatalogSpuCreateRequest(
                "SPU-3",
                "定价商品",
                null,
                null,
                new BigDecimal("88"),
                new BigDecimal("88"),
                null,
                "简短描述",
                null,
                null,
                List.of(new CatalogSpuCreateRequest.SkuDraft("SKU", null, new BigDecimal("88"), 0))
        );

        String rendered = renderer.render(request);

        assertThat(rendered).contains("**价格**：¥88");
        assertThat(rendered).doesNotContain("~");
    }

    @Test
    void rendersDeterministicallyForSameInput() {
        CatalogSpuCreateRequest request = buildRequest();
        String first = renderer.render(request);
        String second = renderer.render(request);

        assertThat(first)
                .as("相同入参应产出相同字节，保证 rag_documents.content_sha256 稳定")
                .isEqualTo(second);
    }

    private CatalogSpuCreateRequest buildRequest() {
        return new CatalogSpuCreateRequest(
                "SPU-4",
                "稳定渲染商品",
                "Brand",
                "类目",
                new BigDecimal("10"),
                new BigDecimal("20"),
                100,
                "描述",
                List.of(),
                null,
                List.of(new CatalogSpuCreateRequest.SkuDraft("SKU", Map.of("k", "v"), new BigDecimal("15"), 3))
        );
    }
}
