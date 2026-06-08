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
        // 展示价用 SPU 最低价（"起"价），并把价格区间随 matchedSlots 透传，供卡片显示 109~129 这类区间，
        // 避免多规格商品只显示某个 SKU 价而被误解为唯一价格。
        BigDecimal price = spu.priceMin() != null ? spu.priceMin()
                : (sku != null ? sku.price() : null);
        Integer stock = sku != null && sku.stock() != null ? sku.stock() : spu.stock();
        Map<String, Object> slots = new java.util.LinkedHashMap<>(matchedSlots == null ? Map.of() : matchedSlots);
        if (spu.priceMin() != null) {
            slots.put("priceMin", spu.priceMin());
        }
        if (spu.priceMax() != null) {
            slots.put("priceMax", spu.priceMax());
        }
        matchedSlots = slots;
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
