package com.bytedance.ai.graph.catalog.api;

import java.util.List;

/**
 * 批量导入结果汇总。
 *
 * @param total      请求中总条数
 * @param succeeded  成功条数
 * @param failed     失败条数
 * @param succeededIds  成功导入的 SPU id 列表（顺序与 items 对齐）
 * @param failures   每条失败的明细
 */
public record CatalogImportSummary(
        int total,
        int succeeded,
        int failed,
        List<Long> succeededIds,
        List<Failure> failures
) {
    /**
     * 单条失败的明细。
     *
     * @param externalRef 失败的业务编号
     * @param reason      失败原因
     */
    public record Failure(
            String externalRef,
            String reason
    ) {
    }
}
