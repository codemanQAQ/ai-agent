package com.bytedance.ai.agent.api.events;

import com.bytedance.ai.agent.api.CompareMatrixView;
import com.bytedance.ai.agent.api.SpuCardView;

import java.util.List;
import java.util.Map;

/**
 * tool.result 事件负载。
 *
 * @param toolName        触发的工具名（search_products / compare_products / ...）
 * @param cards           工具返回的商品卡片
 * @param facetsApplied   工具实际应用的正向过滤项（priceRange / brands / must / mustNot ...）
 * @param compareMatrix   对比矩阵（compare_products 专用）
 * @param excludedFacets  W2 反选加分项：本轮被剔除的属性 / 品牌 / 成分；
 *                         客户端据此展示「已为您排除：酒精、香精」🔗
 */
public record ToolResultPayload(
        String toolName,
        List<SpuCardView> cards,
        Map<String, Object> facetsApplied,
        CompareMatrixView compareMatrix,
        List<String> excludedFacets
) {
    public ToolResultPayload {
        cards = cards == null ? List.of() : List.copyOf(cards);
        facetsApplied = facetsApplied == null ? Map.of() : Map.copyOf(facetsApplied);
        excludedFacets = excludedFacets == null ? List.of() : List.copyOf(excludedFacets);
    }

    public ToolResultPayload(String toolName, List<SpuCardView> cards, Map<String, Object> facetsApplied) {
        this(toolName, cards, facetsApplied, null, List.of());
    }

    public ToolResultPayload(
            String toolName,
            List<SpuCardView> cards,
            Map<String, Object> facetsApplied,
            CompareMatrixView compareMatrix
    ) {
        this(toolName, cards, facetsApplied, compareMatrix, List.of());
    }
}
