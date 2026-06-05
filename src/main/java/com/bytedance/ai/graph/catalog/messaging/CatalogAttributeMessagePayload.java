package com.bytedance.ai.graph.catalog.messaging;

/**
 * catalog 抽属性消息体。
 *
 * <p>消费端从中拿 {@code spuId}（主键）和 {@code externalRef}（可观测性）即可，
 * 其它属性靠 catalog_spu 实时读，避免消息体陈旧覆盖最新数据。
 *
 * @param spuId        所属 SPU id（必填）
 * @param externalRef  SPU 业务编号（仅用于日志/错误信息）
 * @param triggeredBy  触发来源，例如 "import" / "manual-retry"
 * @param enqueuedAtMs 入队时间戳（毫秒），用于消费端度量"消息滞留时长"
 */
public record CatalogAttributeMessagePayload(
        Long spuId,
        String externalRef,
        String triggeredBy,
        long enqueuedAtMs
) {
}
