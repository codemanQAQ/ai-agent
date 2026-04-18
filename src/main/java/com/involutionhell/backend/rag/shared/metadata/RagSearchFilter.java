package com.involutionhell.backend.rag.shared.metadata;

import java.util.List;
import org.springframework.util.StringUtils;

/**
 * 检索过滤条件。
 *
 * @param sourceUriPrefix 文档来源 URI 的前缀匹配条件
 * @param tags 文档标签过滤条件
 * @param headingPathContains 标题路径的包含匹配条件
 */
public record RagSearchFilter(
        String sourceUriPrefix,
        List<String> tags,
        String headingPathContains
) {

    public static RagSearchFilter of(String sourceUriPrefix, List<String> tags, String headingPathContains) {
        return new RagSearchFilter(
                trimToNull(sourceUriPrefix),
                tags == null ? List.of() : tags.stream()
                        .filter(StringUtils::hasText)
                        .map(String::trim)
                        .toList(),
                trimToNull(headingPathContains)
        );
    }

    public boolean isEmpty() {
        return !StringUtils.hasText(sourceUriPrefix)
                && (tags == null || tags.isEmpty())
                && !StringUtils.hasText(headingPathContains);
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
