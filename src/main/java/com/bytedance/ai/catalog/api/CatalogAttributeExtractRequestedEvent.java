package com.bytedance.ai.catalog.api;

/**
 * catalog 模块内部事件：SPU 创建或人工触发后，由 worker 异步抽取结构化属性。
 *
 * <p>事件本身只携带 SPU id，worker 自行查询最新描述与状态机。
 *
 * @param spuId       目标 SPU id
 * @param triggeredBy 触发来源（"import" / "manual-retry" 等），仅作可观测性字段
 */
public record CatalogAttributeExtractRequestedEvent(
        Long spuId,
        String triggeredBy
) {
}
