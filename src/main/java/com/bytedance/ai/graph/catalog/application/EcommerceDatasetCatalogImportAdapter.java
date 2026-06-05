package com.bytedance.ai.graph.catalog.application;

import com.bytedance.ai.graph.catalog.api.CatalogImportRequest;
import com.bytedance.ai.graph.catalog.api.CatalogSpuCreateRequest;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Adapts the local ecommerce_agent_dataset JSON shape to the Catalog import contract.
 */
@Component
public class EcommerceDatasetCatalogImportAdapter {

    private static final int DEFAULT_STOCK = 100;
    private static final Map<String, String> CATEGORY_ALIASES = Map.of(
            "食品生活", "食品饮料"
    );

    private final RagJsonCodec jsonCodec;

    public EcommerceDatasetCatalogImportAdapter(RagJsonCodec jsonCodec) {
        this.jsonCodec = jsonCodec;
    }

    public CatalogImportRequest adaptDirectory(Path datasetRoot) {
        Path normalizedRoot = requireReadableDirectory(datasetRoot);
        try {
            List<CatalogSpuCreateRequest> items = Files.walk(normalizedRoot)
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> normalizedRoot.relativize(path).toString()))
                    .map(path -> adaptFile(normalizedRoot, path))
                    .toList();
            if (items.isEmpty()) {
                throw new IllegalArgumentException("数据集目录中没有 JSON 商品文件: " + normalizedRoot);
            }
            return new CatalogImportRequest(items);
        } catch (IOException exception) {
            throw new IllegalStateException("读取电商数据集目录失败: " + normalizedRoot, exception);
        }
    }

    public CatalogSpuCreateRequest adaptFile(Path jsonFile) {
        return adaptFile(null, jsonFile);
    }

    private CatalogSpuCreateRequest adaptFile(Path datasetRoot, Path jsonFile) {
        Path normalizedFile = requireReadableFile(jsonFile);
        try {
            String content = Files.readString(normalizedFile, StandardCharsets.UTF_8);
            DatasetProduct product = jsonCodec.read(content, DatasetProduct.class);
            return adaptProduct(product, directoryCategory(datasetRoot, normalizedFile));
        } catch (IOException exception) {
            throw new IllegalStateException("读取电商商品 JSON 失败: " + normalizedFile, exception);
        }
    }

    CatalogSpuCreateRequest adaptProduct(DatasetProduct product) {
        return adaptProduct(product, null);
    }

    CatalogSpuCreateRequest adaptProduct(DatasetProduct product, String directoryCategory) {
        validateProduct(product);
        List<CatalogSpuCreateRequest.SkuDraft> skuDrafts = product.skus().stream()
                .map(this::adaptSku)
                .toList();
        BigDecimal priceMin = priceMin(product, skuDrafts);
        BigDecimal priceMax = priceMax(product, skuDrafts);
        int totalStock = skuDrafts.stream()
                .map(CatalogSpuCreateRequest.SkuDraft::stock)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
        return new CatalogSpuCreateRequest(
                product.product_id().trim(),
                product.title().trim(),
                blankToNull(product.brand()),
                categoryPath(product, directoryCategory),
                priceMin,
                priceMax,
                totalStock,
                renderDescription(product),
                imageList(product.image_path()),
                null,
                skuDrafts
        );
    }

    private CatalogSpuCreateRequest.SkuDraft adaptSku(DatasetSku sku) {
        if (sku == null || !StringUtils.hasText(sku.sku_id())) {
            throw new IllegalArgumentException("SKU 编码不能为空");
        }
        BigDecimal price = sku.price() == null ? BigDecimal.ZERO : sku.price();
        return new CatalogSpuCreateRequest.SkuDraft(
                sku.sku_id().trim(),
                specJson(sku.properties()),
                price,
                DEFAULT_STOCK
        );
    }

    private Map<String, Object> specJson(Map<String, Object> properties) {
        if (properties == null || properties.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            if (StringUtils.hasText(entry.getKey())) {
                spec.put(entry.getKey(), entry.getValue());
            }
        }
        return spec;
    }

    private BigDecimal priceMin(DatasetProduct product, List<CatalogSpuCreateRequest.SkuDraft> skuDrafts) {
        return skuDrafts.stream()
                .map(CatalogSpuCreateRequest.SkuDraft::price)
                .filter(Objects::nonNull)
                .min(BigDecimal::compareTo)
                .orElse(product.base_price());
    }

    private BigDecimal priceMax(DatasetProduct product, List<CatalogSpuCreateRequest.SkuDraft> skuDrafts) {
        return skuDrafts.stream()
                .map(CatalogSpuCreateRequest.SkuDraft::price)
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(product.base_price());
    }

    private String categoryPath(DatasetProduct product, String directoryCategory) {
        String category = firstNonBlank(
                normalizeCategory(product.category()),
                normalizeCategory(directoryCategory)
        );
        String subCategory = blankToNull(product.sub_category());
        if (category == null) {
            return subCategory;
        }
        if (subCategory == null || category.equals(subCategory)) {
            return category;
        }
        return category + "/" + subCategory;
    }

    private String directoryCategory(Path datasetRoot, Path jsonFile) {
        if (datasetRoot == null) {
            return null;
        }
        Path normalizedRoot = datasetRoot.toAbsolutePath().normalize();
        Path normalizedFile = jsonFile.toAbsolutePath().normalize();
        if (!normalizedFile.startsWith(normalizedRoot)) {
            return null;
        }
        Path relative = normalizedRoot.relativize(normalizedFile);
        if (relative.getNameCount() == 0) {
            return null;
        }
        return stripCategoryPrefix(relative.getName(0).toString());
    }

    private String stripCategoryPrefix(String directoryName) {
        if (!StringUtils.hasText(directoryName)) {
            return null;
        }
        return directoryName.trim().replaceFirst("^\\d+[_-]?", "");
    }

    private String normalizeCategory(String value) {
        String category = blankToNull(value);
        if (category == null) {
            return null;
        }
        return CATEGORY_ALIASES.getOrDefault(category, category);
    }

    private List<String> imageList(String imagePath) {
        String normalized = blankToNull(imagePath);
        return normalized == null ? List.of() : List.of(normalized);
    }

    private String renderDescription(DatasetProduct product) {
        StringBuilder builder = new StringBuilder();
        DatasetRagKnowledge knowledge = product.rag_knowledge();
        if (knowledge != null && StringUtils.hasText(knowledge.marketing_description())) {
            builder.append("## 营销描述\n")
                    .append(knowledge.marketing_description().trim())
                    .append("\n");
        }
        if (knowledge != null && knowledge.official_faq() != null && !knowledge.official_faq().isEmpty()) {
            builder.append("\n## 官方 FAQ\n");
            for (DatasetFaq faq : knowledge.official_faq()) {
                if (faq == null) {
                    continue;
                }
                builder.append("- Q: ").append(nullToEmpty(faq.question())).append("\n")
                        .append("  A: ").append(nullToEmpty(faq.answer())).append("\n");
            }
        }
        if (knowledge != null && knowledge.user_reviews() != null && !knowledge.user_reviews().isEmpty()) {
            builder.append("\n## 用户评价\n");
            for (DatasetReview review : knowledge.user_reviews()) {
                if (review == null) {
                    continue;
                }
                builder.append("- ")
                        .append(nullToEmpty(review.nickname()))
                        .append("（")
                        .append(review.rating() == null ? "未评分" : review.rating() + "星")
                        .append("）：")
                        .append(nullToEmpty(review.content()))
                        .append("\n");
            }
        }
        String rendered = builder.toString().trim();
        return rendered.isBlank() ? "(暂无描述)" : rendered;
    }

    private void validateProduct(DatasetProduct product) {
        if (product == null) {
            throw new IllegalArgumentException("商品 JSON 不能为空");
        }
        if (!StringUtils.hasText(product.product_id())) {
            throw new IllegalArgumentException("product_id 不能为空");
        }
        if (!StringUtils.hasText(product.title())) {
            throw new IllegalArgumentException("title 不能为空: " + product.product_id());
        }
        if (product.skus() == null || product.skus().isEmpty()) {
            throw new IllegalArgumentException("skus 不能为空: " + product.product_id());
        }
    }

    private Path requireReadableDirectory(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("数据集目录不能为空");
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized) || !Files.isReadable(normalized)) {
            throw new IllegalArgumentException("数据集目录不存在或不可读: " + normalized);
        }
        return normalized;
    }

    private Path requireReadableFile(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("商品 JSON 文件不能为空");
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized) || !Files.isReadable(normalized)) {
            throw new IllegalArgumentException("商品 JSON 文件不存在或不可读: " + normalized);
        }
        return normalized;
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    record DatasetProduct(
            String product_id,
            String title,
            String brand,
            String category,
            String sub_category,
            BigDecimal base_price,
            String image_path,
            List<DatasetSku> skus,
            DatasetRagKnowledge rag_knowledge
    ) {
    }

    record DatasetSku(
            String sku_id,
            Map<String, Object> properties,
            BigDecimal price
    ) {
    }

    record DatasetRagKnowledge(
            String marketing_description,
            List<DatasetFaq> official_faq,
            List<DatasetReview> user_reviews
    ) {
    }

    record DatasetFaq(String question, String answer) {
    }

    record DatasetReview(String nickname, Integer rating, String content) {
    }
}
