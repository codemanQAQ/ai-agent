package com.involutionhell.backend.rag.indexing.model;

import java.util.List;
import java.util.Map;

/**
 * 文本分块后的中间模型。
 *
 * @param chunkIndex 分块在当前文档版本内的顺序号
 * @param text 分块正文内容
 * @param hash 分块内容的稳定哈希
 * @param charCount 分块字符数
 * @param tokenCount 分块估算 token 数
 * @param headingPath 分块所属标题层级路径
 * @param blockType 分块所属 Markdown 块类型
 * @param codeLanguage 代码块语言；非代码块时为空
 * @param blockMetadata 块级附加元数据
 */
public record RagTextChunk(
        int chunkIndex,
        String text,
        String hash,
        int charCount,
        int tokenCount,
        List<String> headingPath,
        String blockType,
        String codeLanguage,
        Map<String, Object> blockMetadata
) {
}
