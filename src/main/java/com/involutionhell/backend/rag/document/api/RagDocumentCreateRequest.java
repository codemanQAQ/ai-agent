package com.involutionhell.backend.rag.document.api;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * 文档导入请求。
 *
 * @param sourceType 文档来源类型
 * @param sourceUri 文档来源 URI
 * @param externalRef 外部引用标识
 * @param title 文档标题
 * @param content 文档正文
 * @param metadata 文档附加元数据
 */
public record RagDocumentCreateRequest(
        @NotBlank(message = "来源类型不能为空")
        String sourceType,
        @NotBlank(message = "来源链接不能为空")
        String sourceUri,
        String externalRef,
        @NotBlank(message = "标题不能为空")
        String title,
        @NotBlank(message = "文档内容不能为空")
        String content,
        @NotBlank(message = "元数据信息不能为空")
        Map<String, Object> metadata
) {
}
