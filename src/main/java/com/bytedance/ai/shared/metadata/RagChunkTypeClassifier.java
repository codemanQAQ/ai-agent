package com.bytedance.ai.shared.metadata;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 把切片归类到 {@link RagChunkType}。
 *
 * <p>策略：
 * <ul>
 *   <li>显式声明的 chunkType（来自上游 chunker 或导入侧）优先使用。</li>
 *   <li>否则按 {@code sourceType} 走规则：
 *     <ul>
 *       <li>{@code catalog-spu}：第一段（chunkIndex=0 且无 heading）视为 {@link RagChunkType#TITLE}；
 *           headingPath 中匹配"规格 / spec / attributes / 参数"归 {@link RagChunkType#ATTR}；
 *           匹配"商品描述 / description / details / 介绍"归 {@link RagChunkType#DESC}；
 *           匹配"评论 / reviews / review"归 {@link RagChunkType#REVIEW}；
 *           其它归 {@link RagChunkType#BODY}。</li>
 *       <li>其它来源：统一归 {@link RagChunkType#BODY}，由 retrieval 后续按需扩展。</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>分类决策来自 catalog 模块约定的 markdown 模板（{@code SpuMarkdownRenderer}）：
 * {@code # 标题 / ## 商品描述 / ## 规格}。模板若调整需同步本类的关键词集合。
 */
@Component
public class RagChunkTypeClassifier {

    private static final Set<String> ATTR_KEYWORDS = Set.of("规格", "参数", "属性", "spec", "specs", "specification", "attributes");
    private static final Set<String> DESC_KEYWORDS = Set.of("商品描述", "详情", "介绍", "description", "details");
    private static final Set<String> REVIEW_KEYWORDS = Set.of("评论", "用户评价", "reviews", "review");

    /**
     * 主分类入口。所有参数允许为 null / 空集合。
     *
     * @param sourceType    rag_documents.source_type
     * @param chunkIndex    切片顺序号（0-based）
     * @param headingPath   切片所在的 markdown heading 层级
     * @param blockMetadata 切片块级 metadata；若上游显式塞了 chunkType 则优先采纳
     */
    public RagChunkType classify(
            String sourceType,
            int chunkIndex,
            List<String> headingPath,
            java.util.Map<String, Object> blockMetadata
    ) {
        RagChunkType explicit = readExplicit(blockMetadata);
        if (explicit != null) {
            return explicit;
        }
        if (isCatalogSpu(sourceType)) {
            return classifyCatalogSpu(chunkIndex, headingPath);
        }
        return RagChunkType.BODY;
    }

    private RagChunkType readExplicit(java.util.Map<String, Object> blockMetadata) {
        if (blockMetadata == null || blockMetadata.isEmpty()) {
            return null;
        }
        Object value = blockMetadata.get("chunkType");
        if (value == null) {
            return null;
        }
        RagChunkType parsed = RagChunkType.parseOrBody(String.valueOf(value));
        // 显式声明即使是 BODY 也尊重；只在 metadata 不含 key 时才走启发式。
        return parsed;
    }

    private RagChunkType classifyCatalogSpu(int chunkIndex, List<String> headingPath) {
        if (headingPath == null || headingPath.isEmpty()) {
            // chunkIndex=0 且没有 heading 通常对应渲染模板里的 H1 标题段。
            return chunkIndex == 0 ? RagChunkType.TITLE : RagChunkType.BODY;
        }
        for (String segment : headingPath) {
            if (!StringUtils.hasText(segment)) {
                continue;
            }
            String lower = segment.toLowerCase(Locale.ROOT);
            if (containsAny(lower, ATTR_KEYWORDS)) {
                return RagChunkType.ATTR;
            }
            if (containsAny(lower, DESC_KEYWORDS)) {
                return RagChunkType.DESC;
            }
            if (containsAny(lower, REVIEW_KEYWORDS)) {
                return RagChunkType.REVIEW;
            }
        }
        // 有 heading 但都不命中：可能是促销标语等，归 BODY 保险。
        return RagChunkType.BODY;
    }

    private boolean isCatalogSpu(String sourceType) {
        return "catalog-spu".equalsIgnoreCase(sourceType);
    }

    private boolean containsAny(String text, Set<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
