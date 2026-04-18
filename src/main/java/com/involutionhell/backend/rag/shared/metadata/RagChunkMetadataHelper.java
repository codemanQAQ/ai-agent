package com.involutionhell.backend.rag.shared.metadata;

import com.involutionhell.backend.rag.shared.support.RagJsonCodec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 负责解析 chunk metadata，并提供统一的过滤判断。
 */
@Component
public class RagChunkMetadataHelper {

    private static final Logger log = LoggerFactory.getLogger(RagChunkMetadataHelper.class);

    private final RagJsonCodec jsonCodec;

    public RagChunkMetadataHelper(RagJsonCodec jsonCodec) {
        this.jsonCodec = jsonCodec;
    }

    public RagChunkMetadataView parse(String metadataJson) {
        Map<String, Object> raw = parseRaw(metadataJson);
        return new RagChunkMetadataView(
                asText(raw.get("blockType")),
                asText(raw.get("codeLanguage")),
                toStringList(raw.get("headingPath")),
                toStringList(raw.get("documentTags")),
                raw
        );
    }

    public boolean matches(String sourceUri, RagChunkMetadataView metadataView, RagSearchFilter filter) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }

        if (StringUtils.hasText(filter.sourceUriPrefix())
                && !startsWithIgnoreCase(sourceUri, filter.sourceUriPrefix())) {
            return false;
        }

        if (filter.tags() != null && !filter.tags().isEmpty()) {
            List<String> lowerCaseTags = metadataView.documentTags().stream()
                    .map(this::normalizeLowerCase)
                    .toList();
            boolean allMatch = filter.tags().stream()
                    .map(this::normalizeLowerCase)
                    .allMatch(lowerCaseTags::contains);
            if (!allMatch) {
                return false;
            }
        }

        if (StringUtils.hasText(filter.headingPathContains())) {
            String keyword = normalizeLowerCase(filter.headingPathContains());
            boolean matched = metadataView.headingPath().stream()
                    .map(this::normalizeLowerCase)
                    .anyMatch(segment -> segment.contains(keyword));
            if (!matched) {
                return false;
            }
        }

        return true;
    }

    public List<String> toStringList(Object value) {
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }
        return rawList.stream()
                .filter(item -> item != null && StringUtils.hasText(String.valueOf(item)))
                .map(String::valueOf)
                .toList();
    }

    public Map<String, Object> parseRaw(String metadataJson) {
        if (!StringUtils.hasText(metadataJson)) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = jsonCodec.readMap(metadataJson);
            if (raw == null || raw.isEmpty()) {
                return Map.of();
            }
            return new LinkedHashMap<>(raw);
        } catch (Exception exception) {
            log.warn(
                    "RAG chunk metadata 解析失败，将按空 metadata 处理。error={}, payload={}",
                    exception.getMessage(),
                    abbreviate(metadataJson)
            );
            return Map.of();
        }
    }

    private boolean startsWithIgnoreCase(String value, String prefix) {
        if (!StringUtils.hasText(value) || !StringUtils.hasText(prefix)) {
            return false;
        }
        return normalizeLowerCase(value).startsWith(normalizeLowerCase(prefix));
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String normalizeLowerCase(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.length() <= 200) {
            return value;
        }
        return value.substring(0, 200) + "...";
    }
}
