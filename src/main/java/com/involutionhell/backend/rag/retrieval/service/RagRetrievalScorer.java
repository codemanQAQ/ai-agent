package com.involutionhell.backend.rag.retrieval.service;

import com.involutionhell.backend.rag.shared.properties.RagProperties;
import com.involutionhell.backend.rag.indexing.api.RagChunkSearchView;
import com.involutionhell.backend.rag.shared.metadata.RagChunkMetadataView;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 统一管理 RAG 检索阶段的词法打分和 headingPath 加权规则。
 */
@Component
public class RagRetrievalScorer {

    private final RagProperties ragProperties;

    public RagRetrievalScorer(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    /**
     * 为关键词回退检索计算总分，标题路径命中会显著加权。
     */
    public double keywordScore(RagChunkSearchView row, RagChunkMetadataView metadataView, Set<String> tokens) {
        String headingText = normalize(joinHeadingPath(metadataView));
        String titleText = normalize(row.title());
        String contentText = normalize(row.chunkText());
        return weightedOccurrences(headingText, tokens, ragProperties.retrieval().keywordHeadingWeight())
                + weightedOccurrences(titleText, tokens, ragProperties.retrieval().keywordTitleWeight())
                + weightedOccurrences(contentText, tokens, ragProperties.retrieval().keywordContentWeight());
    }

    /**
     * 对向量检索结果做轻量重排，优先提升标题路径命中的 chunk。
     */
    public double semanticRerankScore(RagChunkMetadataView metadataView, Set<String> tokens, double semanticScore) {
        String headingText = normalize(joinHeadingPath(metadataView));
        return semanticScore + weightedOccurrences(headingText, tokens, ragProperties.retrieval().semanticHeadingWeight());
    }

    /**
     * 统一提取检索 token，兼容中英文问题。
     */
    public Set<String> extractTokens(String question) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : normalize(question).split("[^\\p{IsAlphabetic}\\p{IsDigit}]+")) {
            if (!token.isBlank() && token.length() >= 2) {
                tokens.add(token);
            }
        }
        if (!tokens.isEmpty()) {
            return tokens;
        }
        return Set.of(normalize(question));
    }

    private double weightedOccurrences(String text, Set<String> tokens, double weight) {
        if (text.isBlank()) {
            return 0;
        }
        return tokens.stream()
                .mapToDouble(token -> countOccurrences(text, token) * weight)
                .sum();
    }

    private int countOccurrences(String text, String token) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }

    private String joinHeadingPath(RagChunkMetadataView metadataView) {
        if (metadataView == null || metadataView.headingPath() == null || metadataView.headingPath().isEmpty()) {
            return "";
        }
        return metadataView.headingPath().stream()
                .filter(value -> !value.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
