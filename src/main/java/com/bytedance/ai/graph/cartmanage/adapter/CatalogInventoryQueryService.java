package com.bytedance.ai.graph.cartmanage.adapter;

import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogSkuView;
import com.bytedance.ai.graph.catalog.api.CatalogSpuView;
import com.bytedance.ai.graph.cartmanage.InventoryQueryService;
import com.bytedance.ai.graph.cartmanage.StockResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Real inventory probe for cart management, backed by catalog SPU/SKU stock.
 */
@Service
public class CatalogInventoryQueryService implements InventoryQueryService {

    private static final String ACTIVE = "ACTIVE";

    private final CatalogQueryFacade catalogQueryFacade;

    public CatalogInventoryQueryService(CatalogQueryFacade catalogQueryFacade) {
        this.catalogQueryFacade = catalogQueryFacade;
    }

    @Override
    public StockResult checkStock(String productId, String skuId, int requestedQuantity) {
        if (requestedQuantity <= 0) {
            return StockResult.outOfStock(productId, skuId, 0);
        }

        // productId/skuId 可能是数字主键，也可能是召回快照里的外部码（如 p_beauty_024 / s_p_beauty_024_1）。
        // 数字 → 按主键查；非数字 → 按 external_ref / sku_code 查，避免 parseId 失败直接判缺货。
        CatalogSpuView spu = resolveSpu(productId);
        if (spu == null || !ACTIVE.equalsIgnoreCase(nullToEmpty(spu.status()))) {
            return StockResult.outOfStock(productId, skuId, 0);
        }

        if (StringUtils.hasText(skuId)) {
            StockResult bySku = checkSkuStock(productId, skuId, requestedQuantity, spu.skus());
            if (bySku != null) {
                return bySku;
            }
            // 未匹配到具体 SKU → 回退到 SPU 级库存
        }

        int availableQty = spu.stock() == null ? 0 : spu.stock();
        return availableQty >= requestedQuantity
                ? StockResult.inStock(productId, skuId, availableQty)
                : StockResult.outOfStock(productId, skuId, availableQty);
    }

    private CatalogSpuView resolveSpu(String productId) {
        Long spuId = parseId(productId);
        try {
            if (spuId != null) {
                return catalogQueryFacade.getSpu(spuId);
            }
            return catalogQueryFacade.findSpuByExternalRef(productId).orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** 返回 null 表示没匹配到该 SKU（调用方回退到 SPU 库存）。 */
    private StockResult checkSkuStock(
            String productId,
            String skuId,
            int requestedQuantity,
            List<CatalogSkuView> skus
    ) {
        if (skus == null || skus.isEmpty()) {
            return null;
        }
        Long skuPrimaryId = parseId(skuId);
        return skus.stream()
                .filter(sku -> sku != null && (skuPrimaryId != null
                        ? skuPrimaryId.equals(sku.id())
                        : skuId.equalsIgnoreCase(sku.skuCode())))
                .findFirst()
                .map(sku -> {
                    int availableQty = sku.stock() == null ? 0 : sku.stock();
                    boolean active = ACTIVE.equalsIgnoreCase(nullToEmpty(sku.status()));
                    return active && availableQty >= requestedQuantity
                            ? StockResult.inStock(productId, skuId, availableQty)
                            : StockResult.outOfStock(productId, skuId, availableQty);
                })
                .orElse(null);
    }

    private Long parseId(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
