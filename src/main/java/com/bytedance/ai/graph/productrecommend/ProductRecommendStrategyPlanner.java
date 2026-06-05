package com.bytedance.ai.graph.productrecommend;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ProductRecommendStrategyPlanner {

    public ProductRecallPlan plan(ProductRecommendSubScene subScene) {
        ProductRecommendSubScene resolved = subScene == null ? ProductRecommendSubScene.FUZZY_RECOMMEND : subScene;
        return switch (resolved) {
            case FUZZY_RECOMMEND -> new ProductRecallPlan(
                    resolved,
                    List.of(
                            ProductRecallSource.CATALOG_KEYWORD,
                            ProductRecallSource.RAG_CHUNK,
                            ProductRecallSource.HISTORY_SNAPSHOT,
                            ProductRecallSource.PREFERENCE
                    ),
                    10,
                    5,
                    false,
                    "宽召回：目录关键词、RAG 知识片段和历史候选共同生成模糊推荐候选。"
            );
            case CONDITION_FILTER -> new ProductRecallPlan(
                    resolved,
                    List.of(
                            ProductRecallSource.CATALOG_FILTER,
                            ProductRecallSource.RAG_CHUNK,
                            ProductRecallSource.CATALOG_KEYWORD,
                            ProductRecallSource.HISTORY_SNAPSHOT
                    ),
                    12,
                    5,
                    true,
                    "条件筛选：结构化过滤优先，RAG 和关键词召回补充证据。"
            );
            case MULTI_TURN_REFINE -> new ProductRecallPlan(
                    resolved,
                    List.of(
                            ProductRecallSource.HISTORY_SNAPSHOT,
                            ProductRecallSource.CATALOG_FILTER,
                            ProductRecallSource.RAG_CHUNK,
                            ProductRecallSource.CATALOG_KEYWORD
                    ),
                    10,
                    5,
                    true,
                    "多轮细化：优先使用上一轮候选范围，再叠加本轮条件。"
            );
            case PRODUCT_COMPARE, DETAIL_FAQ_REVIEW_ANSWER -> new ProductRecallPlan(
                    resolved,
                    List.of(
                            ProductRecallSource.HISTORY_SNAPSHOT,
                            ProductRecallSource.RAG_CHUNK,
                            ProductRecallSource.CATALOG_KEYWORD
                    ),
                    8,
                    resolved == ProductRecommendSubScene.PRODUCT_COMPARE ? 4 : 3,
                    true,
                    "详情/对比：围绕候选快照和 RAG 证据获取少量高相关商品。"
            );
            case NEGATIVE_CONSTRAINT -> new ProductRecallPlan(
                    resolved,
                    List.of(
                            ProductRecallSource.HISTORY_SNAPSHOT,
                            ProductRecallSource.CATALOG_KEYWORD,
                            ProductRecallSource.RAG_CHUNK,
                            ProductRecallSource.CATALOG_FILTER
                    ),
                    10,
                    5,
                    false,
                    "反选排除：保留宽召回能力，并交给负向过滤器剔除不想要的候选。"
            );
            case SCENE_BUNDLE_RECOMMEND -> new ProductRecallPlan(
                    resolved,
                    List.of(
                            ProductRecallSource.CATALOG_KEYWORD,
                            ProductRecallSource.RAG_CHUNK,
                            ProductRecallSource.CATALOG_FILTER,
                            ProductRecallSource.HISTORY_SNAPSHOT
                    ),
                    12,
                    6,
                    false,
                    "场景组合：跨类目宽召回，后续组合编排再分角色。"
            );
            case PHOTO_SEARCH -> new ProductRecallPlan(
                    resolved,
                    List.of(
                            ProductRecallSource.IMAGE_VECTOR,
                            ProductRecallSource.RAG_CHUNK,
                            ProductRecallSource.CATALOG_KEYWORD,
                            ProductRecallSource.HISTORY_SNAPSHOT
                    ),
                    10,
                    5,
                    false,
                    "拍照找货：图片向量优先，Caption/文本约束通过 RAG 和关键词补充。"
            );
        };
    }
}
