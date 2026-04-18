package com.involutionhell.backend.rag.retrieval.service;

import com.involutionhell.backend.rag.shared.metadata.RagSearchFilter;
import com.involutionhell.backend.rag.shared.support.RagJsonCodec;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 将可下推的检索过滤条件编译为 Milvus 原生过滤表达式。
 */
@Component
public class RagMilvusNativeExpressionBuilder {

    private final RagJsonCodec jsonCodec;

    public RagMilvusNativeExpressionBuilder(RagJsonCodec jsonCodec) {
        this.jsonCodec = jsonCodec;
    }

    public String build(RagSearchFilter filter) {
        if (filter == null || filter.isEmpty()) {
            return null;
        }

        java.util.ArrayList<String> expressions = new java.util.ArrayList<>();
        if (StringUtils.hasText(filter.sourceUriPrefix())) {
            expressions.add(MilvusVectorStore.METADATA_FIELD_NAME
                    + "[\"sourceUri\"] LIKE \""
                    + escapeLikeLiteral(filter.sourceUriPrefix())
                    + "%\"");
        }
        if (filter.tags() != null && !filter.tags().isEmpty()) {
            expressions.add("json_contains_all("
                    + MilvusVectorStore.METADATA_FIELD_NAME
                    + "[\"documentTags\"], "
                    + toJsonArray(filter.tags())
                    + ")");
        }
        if (StringUtils.hasText(filter.headingPathContains())) {
            expressions.add(MilvusVectorStore.METADATA_FIELD_NAME
                    + "[\"headingPathText\"] LIKE \"%"
                    + escapeLikeLiteral(normalizeLowerCase(filter.headingPathContains()))
                    + "%\"");
        }

        if (!StringUtils.hasText(expressions.isEmpty() ? null : expressions.getFirst())) {
            return null;
        }
        return String.join(" AND ", expressions);
    }

    private String escapeLikeLiteral(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("%", "\\\\%")
                .replace("_", "\\\\_");
    }

    private String normalizeLowerCase(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String toJsonArray(List<String> values) {
        try {
            return jsonCodec.write(values);
        } catch (Exception exception) {
            throw new IllegalStateException("构建 Milvus tags 过滤表达式失败", exception);
        }
    }
}
