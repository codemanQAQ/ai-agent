package com.bytedance.ai.graph.catalog.application;

import com.bytedance.ai.graph.catalog.api.CatalogImportRequest;
import com.bytedance.ai.graph.catalog.api.CatalogSpuCreateRequest;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EcommerceDatasetCatalogImportAdapterTests {

    private final EcommerceDatasetCatalogImportAdapter adapter =
            new EcommerceDatasetCatalogImportAdapter(new RagJsonCodec(JsonMapper.builder().build()));

    @TempDir
    private Path tempDir;

    @Test
    void adaptsDatasetJsonToCatalogImportRequest() throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("2_数码电子/data"));
        Files.writeString(dataDir.resolve("p_digital_001.json"), """
                {
                  "product_id": "p_digital_001",
                  "title": "Apple iPhone 17 Pro 256GB",
                  "brand": "Apple 苹果",
                  "category": "数码电子",
                  "sub_category": "智能手机",
                  "base_price": 8999.0,
                  "image_path": "2_数码电子/images/p_digital_001_live.jpg",
                  "skus": [
                    {
                      "sku_id": "s_p_digital_001_1",
                      "properties": {
                        "存储": "256GB",
                        "颜色": "宇宙橙"
                      },
                      "price": 8999.0
                    },
                    {
                      "sku_id": "s_p_digital_001_2",
                      "properties": {
                        "存储": "512GB",
                        "颜色": "深空黑"
                      },
                      "price": 10499.0
                    }
                  ],
                  "rag_knowledge": {
                    "marketing_description": "适合内容创作者和商务人群。",
                    "official_faq": [
                      {
                        "question": "256GB 是否够用？",
                        "answer": "多数日常用户够用。"
                      }
                    ],
                    "user_reviews": [
                      {
                        "nickname": "张小明",
                        "rating": 5,
                        "content": "性能很强。"
                      }
                    ]
                  }
                }
                """);

        CatalogImportRequest request = adapter.adaptDirectory(tempDir);

        assertThat(request.items()).hasSize(1);
        CatalogSpuCreateRequest item = request.items().getFirst();
        assertThat(item.externalRef()).isEqualTo("p_digital_001");
        assertThat(item.title()).isEqualTo("Apple iPhone 17 Pro 256GB");
        assertThat(item.brand()).isEqualTo("Apple 苹果");
        assertThat(item.categoryPath()).isEqualTo("数码电子/智能手机");
        assertThat(item.priceMin()).isEqualByComparingTo(new BigDecimal("8999.0"));
        assertThat(item.priceMax()).isEqualByComparingTo(new BigDecimal("10499.0"));
        assertThat(item.stock()).isEqualTo(200);
        assertThat(item.images()).containsExactly("2_数码电子/images/p_digital_001_live.jpg");
        assertThat(item.descriptionMd()).isEqualTo("""
                ## 营销描述
                适合内容创作者和商务人群。

                ## 官方 FAQ
                - Q: 256GB 是否够用？
                  A: 多数日常用户够用。

                ## 用户评价
                - 张小明（5星）：性能很强。""");
        assertThat(item.skus()).hasSize(2);
        assertThat(item.skus().getFirst().skuCode()).isEqualTo("s_p_digital_001_1");
        assertThat(item.skus()).allSatisfy(sku -> assertThat(sku.stock()).isEqualTo(100));
        assertThat(item.skus().getFirst().specJson())
                .containsEntry("存储", "256GB")
                .containsEntry("颜色", "宇宙橙");

        CatalogImportRequest secondRequest = adapter.adaptDirectory(tempDir);
        assertThat(secondRequest.items().getFirst().descriptionMd())
                .as("RAG Markdown must stay deterministic for stable rag_documents.content_sha256")
                .isEqualTo(item.descriptionMd());
    }

    @Test
    void sortsFilesDeterministically() throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Files.writeString(dataDir.resolve("b.json"), productJson("p_b", "B"));
        Files.writeString(dataDir.resolve("a.json"), productJson("p_a", "A"));

        CatalogImportRequest request = adapter.adaptDirectory(tempDir);

        assertThat(request.items())
                .extracting(CatalogSpuCreateRequest::externalRef)
                .containsExactly("p_a", "p_b");
    }

    @Test
    void normalizesDirectoryAndProductCategoryNames() throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("4_食品生活/data"));
        Files.writeString(dataDir.resolve("p_food_001.json"), """
                {
                  "product_id": "p_food_001",
                  "title": "测试咖啡",
                  "brand": "TestBrand",
                  "category": "食品生活",
                  "sub_category": "咖啡",
                  "base_price": 39,
                  "image_path": "4_食品生活/images/p_food_001_live.jpg",
                  "skus": [
                    {
                      "sku_id": "s_p_food_001_1",
                      "properties": {
                        "口味": "原味"
                      },
                      "price": 39
                    }
                  ],
                  "rag_knowledge": {
                    "marketing_description": "测试描述"
                  }
                }
                """);

        CatalogImportRequest request = adapter.adaptDirectory(tempDir);

        assertThat(request.items()).hasSize(1);
        assertThat(request.items().getFirst().categoryPath()).isEqualTo("食品饮料/咖啡");
    }

    private String productJson(String productId, String title) {
        return """
                {
                  "product_id": "%s",
                  "title": "%s",
                  "brand": "Brand",
                  "category": "美妆护肤",
                  "sub_category": "精华",
                  "base_price": 99,
                  "image_path": "image.jpg",
                  "skus": [
                    {
                      "sku_id": "sku_%s",
                      "properties": {
                        "容量": "30ml"
                      },
                      "price": 99
                    }
                  ],
                  "rag_knowledge": {
                    "marketing_description": "描述"
                  }
                }
                """.formatted(productId, title, productId);
    }
}
