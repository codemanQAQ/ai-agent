package com.bytedance.ai.retrieval.spi;

import java.util.List;

/**
 * 商品检索 SPI，供 agent 等上层模块调用。
 *
 * <p>实现由 retrieval 内部 {@code ProductSearchSpiAdapter} 提供，包装 {@code HybridRagRetriever}
 * 与 catalog 实时数据。调用方不感知 retrieval 内部检索预算、scorer、joiner 等细节。
 */
public interface ProductSearchSpi {

    /**
     * 执行一次商品检索；返回结果已按综合分降序排好。
     *
     * @throws IllegalArgumentException 当 query 为空
     */
    List<ProductSearchHit> search(ProductSearchRequest request);
}
