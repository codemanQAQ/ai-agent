package com.bytedance.ai.catalog.api;

import java.util.List;

/**
 * Catalog 查询侧 Facade。
 */
public interface CatalogQueryFacade {

    /**
     * 查询 SPU 落地页详情（含全部 SKU）。
     *
     * @throws IllegalArgumentException 当 spu 不存在
     */
    CatalogSpuView getSpu(Long spuId);

    /**
     * 单独查询某 SPU 的 SKU 列表。
     */
    List<CatalogSkuView> listSkus(Long spuId);
}
