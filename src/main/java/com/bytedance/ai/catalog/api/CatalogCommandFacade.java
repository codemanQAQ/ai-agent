package com.bytedance.ai.catalog.api;

/**
 * Catalog 写入侧 Facade（其它模块依赖此契约，不直连内部实现）。
 */
public interface CatalogCommandFacade {

    /**
     * 批量导入 SPU。逐条事务化，失败的条目记录到 {@link CatalogImportSummary#failures()} 中并继续；
     * 成功的条目会触发 {@code DocumentIndexRequestedEvent}（由 document 模块产生）以及在
     * {@code catalog_attribute_outbox} 写入一行 PENDING（由 catalog 自身的 dispatcher → RocketMQ
     * 链路异步消费）。
     */
    CatalogImportSummary importBatch(CatalogImportRequest request);

    /**
     * 人工触发对已存在 SPU 的属性重抽。
     */
    void requestAttributeExtraction(Long spuId);
}
