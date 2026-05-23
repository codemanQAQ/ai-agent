package com.bytedance.ai.agent.api;

import java.util.List;

/**
 * 商品对比矩阵，用于 SSE 结构化输出和答案生成上下文。
 */
public record CompareMatrixView(
        List<ProductColumn> products,
        List<AttributeRow> rows,
        String recommendedRefId,
        String recommendationReason
) {
    public CompareMatrixView {
        products = products == null ? List.of() : List.copyOf(products);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public record ProductColumn(
            String refId,
            Long spuId,
            String externalRef,
            String title
    ) {
    }

    public record AttributeRow(
            String attribute,
            List<String> values
    ) {
        public AttributeRow {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }
}
