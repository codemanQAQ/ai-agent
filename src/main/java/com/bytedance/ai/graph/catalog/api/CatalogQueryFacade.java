package com.bytedance.ai.graph.catalog.api;

import java.math.BigDecimal;
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
     * 按价格区间浏览在售商品（无类目/关键词时的兜底，如"送礼 预算500"）。min/max 任一 null 表示不限该侧。
     */
    default List<CatalogSpuView> browseActiveSpusByPrice(BigDecimal priceMin, BigDecimal priceMax, int limit) {
        return List.of();
    }

    /**
     * 单独查询某 SPU 的 SKU 列表。
     */
    List<CatalogSkuView> listSkus(Long spuId);

    /**
     * 在售商品的去重顶级类目（category_path 的第一段）。用于动态生成意图/组合推荐里的类目清单，
     * 避免把类目写死在 prompt 或代码里——扩充类目只需新增数据。
     */
    default List<String> listActiveTopCategories() {
        return List.of();
    }
}
