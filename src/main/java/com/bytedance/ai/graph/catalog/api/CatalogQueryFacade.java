package com.bytedance.ai.graph.catalog.api;

import java.util.List;
import java.util.Optional;

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
     * 按业务编号查询 SPU 落地页详情；不存在时返回空，供检索回填等容错场景使用。
     */
    Optional<CatalogSpuView> findSpuByExternalRef(String externalRef);

    /**
     * PostgreSQL/catalog 关键词搜索，供购物车等写操作解析商品名时使用。
     */
    default List<CatalogSpuView> searchActiveSpus(String keyword, int limit) {
        return List.of();
    }

    /**
     * 单独查询某 SPU 的 SKU 列表。
     */
    List<CatalogSkuView> listSkus(Long spuId);
}
