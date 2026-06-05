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
    private static final Set<String> MARKETING_KEYWORDS = Set.of("营销描述", "卖点", "marketing", "marketing_description");
    private static final Set<String> FAQ_KEYWORDS = Set.of("官方 faq", "faq", "问答", "常见问题");
    private static final Set<String> REVIEW_KEYWORDS = Set.of("评论", "用户评价", "reviews", "review");
    private static final Set<String> REVIEW_SUMMARY_KEYWORDS = Set.of("评价摘要", "评价总结", "review_summary", "review summary");

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
            // 没有 heading 通常意味着 markdown 主体直接是单段（SpuMarkdownRenderer 渲染异常时才走这里）。
            return chunkIndex == 0 ? RagChunkType.TITLE : RagChunkType.BODY;
        }
        String headingText = headingPath.stream()
                .filter(StringUtils::hasText)
                .map(segment -> segment.toLowerCase(Locale.ROOT))
                .reduce("", (left, right) -> left + "/" + right);
        if (containsAny(headingText, ATTR_KEYWORDS)) {
            return RagChunkType.ATTR;
        }
        if (containsAny(headingText, MARKETING_KEYWORDS)) {
            return RagChunkType.MARKETING_DESCRIPTION;
        }
        if (containsAny(headingText, FAQ_KEYWORDS)) {
            return RagChunkType.OFFICIAL_FAQ;
        }
        if (containsAny(headingText, REVIEW_SUMMARY_KEYWORDS)) {
            return RagChunkType.REVIEW_SUMMARY;
        }
        if (containsAny(headingText, REVIEW_KEYWORDS)) {
            return RagChunkType.USER_REVIEW;
        }
        if (containsAny(headingText, DESC_KEYWORDS)) {
            return RagChunkType.DESC;
        }
        // 仅在 H1 根标题下（headingPath.size()==1）且未命中任何关键词，
        // 视为 SpuMarkdownRenderer 模板里"标题 + 品牌 + 类目 + 价格"那一段，归 TITLE。
        if (headingPath.size() == 1) {
            return RagChunkType.TITLE;
        }
        // 进入 H2 / 更深层级但都不命中：保守归 BODY。
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
