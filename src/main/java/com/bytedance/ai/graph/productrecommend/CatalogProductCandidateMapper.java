package com.bytedance.ai.graph.productrecommend;

import com.bytedance.ai.graph.catalog.api.CatalogSkuView;
import com.bytedance.ai.graph.catalog.api.CatalogSpuView;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

final class CatalogProductCandidateMapper {

    private CatalogProductCandidateMapper() {
    }

    static ProductRecallCandidate toCandidate(
            CatalogSpuView spu,
            ProductRecallSource source,
            double rawScore,
            Map<String, Object> matchedSlots,
            List<ProductRecallEvidence> evidence
    ) {
        CatalogSkuView sku = firstAvailableSku(spu);
        String productId = StringUtils.hasText(spu.externalRef()) ? spu.externalRef() : String.valueOf(spu.id());
        String skuId = sku == null ? null : sku.skuCode();
        BigDecimal price = sku != null && sku.price() != null ? sku.price() : spu.priceMin();
        Integer stock = sku != null && sku.stock() != null ? sku.stock() : spu.stock();
        return new ProductRecallCandidate(
                productId,
                spu.id() == null ? null : String.valueOf(spu.id()),
                skuId,
                spu.externalRef(),
                spu.title(),
                spu.brand(),
                splitCategoryPath(spu.categoryPath()),
                price,
                stock,
                firstImage(spu.images()),
                source,
                rawScore,
                rawScore,
                matchedSlots,
                evidence
        );
    }

    private static CatalogSkuView firstAvailableSku(CatalogSpuView spu) {
        if (spu.skus() == null || spu.skus().isEmpty()) {
            return null;
        }
        return spu.skus().stream()
                .filter(sku -> sku.stock() == null || sku.stock() > 0)
                .findFirst()
                .orElse(spu.skus().getFirst());
    }

    private static List<String> splitCategoryPath(String categoryPath) {
        if (!StringUtils.hasText(categoryPath)) {
            return List.of();
        }
        return Arrays.stream(categoryPath.split("[>/｜|,，;；]+"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private static String firstImage(List<String> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        return images.stream()
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }
}
