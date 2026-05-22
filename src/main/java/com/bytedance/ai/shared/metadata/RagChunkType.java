package com.bytedance.ai.shared.metadata;

import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Chunk 在 RAG 检索语义下的"角色分类"。
 *
 * <p>独立于 markdown block type（text/code/table 等）：block type 描述"长什么样"，
 * chunk type 描述"代表什么"。检索阶段可按 chunkType 做权重加成或反选过滤，
 * 例如 {@link #ATTR} 命中通常意味着用户在询问参数 / 属性。
 *
 * <p>对于非电商语义的通用文档（markdown 上传等），分类为 {@link #BODY}；
 * 对于 catalog-spu 文档由 {@code RagChunkTypeClassifier} 按 heading 自动归类。
 */
public enum RagChunkType {
    /** 商品标题切片：通常对应 H1 段位，权重最高的关键命中。 */
    TITLE,
    /** 结构化属性切片：参数、规格、成分，整体不切分；反选语义主要看这一类。 */
    ATTR,
    /** 商品长描述切片：故事化卖点、使用说明，召回最常命中。 */
    DESC,
    /** 用户评论切片：辅助参考，权重低于 ATTR/DESC。 */
    REVIEW,
    /** 图像 chunk：由 Doubao-embedding-vision 多模态向量化，与文本共存同一 collection。 */
    IMAGE,
    /** 默认 / 通用 markdown 正文切片：未匹配到具体业务角色时使用。 */
    BODY;

    /**
     * 大小写 + 空白容错的解析，遇到非法值时退回 {@link #BODY} 而不是抛错，
     * 让检索链路对脏 metadata 保持健壮。
     */
    public static RagChunkType parseOrBody(String value) {
        if (!StringUtils.hasText(value)) {
            return BODY;
        }
        try {
            return RagChunkType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return BODY;
        }
    }
}
