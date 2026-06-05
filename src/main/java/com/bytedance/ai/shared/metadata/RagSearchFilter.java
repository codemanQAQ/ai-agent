package com.bytedance.ai.shared.metadata;

import java.util.List;
import org.springframework.util.StringUtils;

/**
 * 检索过滤条件。
 *
 * @param sourceUriPrefix     文档来源 URI 的前缀匹配条件
 * @param tags                文档标签过滤条件（"且"：全部命中才召回）
 * @param headingPathContains 标题路径的包含匹配条件
 * @param mustNotTags         反选标签：命中其一即剔除（W2 反选加分项）
 * @param mustNotBrands       反选品牌：metadata.brand 命中即剔除
 * @param mustNotIngredients  反选成分 / 材质：在 chunk content 中出现即剔除（粗过滤，精过滤交给 NegationRerankFilter）
 * @param externalRefs        限定商品 externalRef
 * @param productIds          限定商品 productId
 * @param catalogSpuIds       限定 Catalog SPU ID
 * @param chunkTypes          限定 chunk 语义类型
 */
public record RagSearchFilter(
        String sourceUriPrefix,
        List<String> tags,
        String headingPathContains,
        List<String> mustNotTags,
        List<String> mustNotBrands,
        List<String> mustNotIngredients,
        List<String> externalRefs,
        List<String> productIds,
        List<Long> catalogSpuIds,
        List<RagChunkType> chunkTypes
) {

    public RagSearchFilter {
        tags = copyOrEmpty(tags);
        mustNotTags = copyOrEmpty(mustNotTags);
        mustNotBrands = copyOrEmpty(mustNotBrands);
        mustNotIngredients = copyOrEmpty(mustNotIngredients);
        externalRefs = copyOrEmpty(externalRefs);
        productIds = copyOrEmpty(productIds);
        catalogSpuIds = copyLongsOrEmpty(catalogSpuIds);
        chunkTypes = copyTypesOrEmpty(chunkTypes);
    }

    public static RagSearchFilter of(String sourceUriPrefix, List<String> tags, String headingPathContains) {
        return new RagSearchFilter(
                trimToNull(sourceUriPrefix),
                tags,
                trimToNull(headingPathContains),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    public static RagSearchFilter of(
            String sourceUriPrefix,
            List<String> tags,
            String headingPathContains,
            List<String> mustNotTags,
            List<String> mustNotBrands,
            List<String> mustNotIngredients
    ) {
        return new RagSearchFilter(
                trimToNull(sourceUriPrefix),
                tags,
                trimToNull(headingPathContains),
                mustNotTags,
                mustNotBrands,
                mustNotIngredients,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    public static RagSearchFilter of(
            String sourceUriPrefix,
            List<String> tags,
            String headingPathContains,
            List<String> mustNotTags,
            List<String> mustNotBrands,
            List<String> mustNotIngredients,
            List<String> externalRefs,
            List<String> productIds,
            List<Long> catalogSpuIds
    ) {
        return of(
                sourceUriPrefix,
                tags,
                headingPathContains,
                mustNotTags,
                mustNotBrands,
                mustNotIngredients,
                externalRefs,
                productIds,
                catalogSpuIds,
                List.of()
        );
    }

    public static RagSearchFilter of(
            String sourceUriPrefix,
            List<String> tags,
            String headingPathContains,
            List<String> mustNotTags,
            List<String> mustNotBrands,
            List<String> mustNotIngredients,
            List<String> externalRefs,
            List<String> productIds,
            List<Long> catalogSpuIds,
            List<RagChunkType> chunkTypes
    ) {
        return new RagSearchFilter(
                trimToNull(sourceUriPrefix),
                tags,
                trimToNull(headingPathContains),
                mustNotTags,
                mustNotBrands,
                mustNotIngredients,
                externalRefs,
                productIds,
                catalogSpuIds,
                chunkTypes
        );
    }

    public RagSearchFilter withMustNot(
            List<String> mustNotTags,
            List<String> mustNotBrands,
            List<String> mustNotIngredients
    ) {
        return new RagSearchFilter(
                this.sourceUriPrefix,
                this.tags,
                this.headingPathContains,
                mustNotTags,
                mustNotBrands,
                mustNotIngredients,
                this.externalRefs,
                this.productIds,
                this.catalogSpuIds,
                this.chunkTypes
        );
    }

    public boolean isEmpty() {
        return !StringUtils.hasText(sourceUriPrefix)
                && tags.isEmpty()
                && !StringUtils.hasText(headingPathContains)
                && mustNotTags.isEmpty()
                && mustNotBrands.isEmpty()
                && mustNotIngredients.isEmpty()
                && externalRefs.isEmpty()
                && productIds.isEmpty()
                && catalogSpuIds.isEmpty()
                && chunkTypes.isEmpty();
    }

    public boolean hasMustNot() {
        return !mustNotTags.isEmpty() || !mustNotBrands.isEmpty() || !mustNotIngredients.isEmpty();
    }

    private static List<String> copyOrEmpty(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
    }

    private static List<Long> copyLongsOrEmpty(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && value > 0)
                .toList();
    }

    private static List<RagChunkType> copyTypesOrEmpty(List<RagChunkType> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null)
                .distinct()
                .toList();
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
