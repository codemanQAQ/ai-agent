package com.involutionhell.backend.rag.shared.support;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 统一承接 RAG 模块内的 JSON 编解码，避免业务层直接散落 ObjectMapper 调用。
 */
@Component
public class RagJsonCodec {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public RagJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("JSON 序列化失败", exception);
        }
    }

    public <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception exception) {
            throw new IllegalStateException("JSON 反序列化失败", exception);
        }
    }

    public <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception exception) {
            throw new IllegalStateException("JSON 反序列化失败", exception);
        }
    }

    public Map<String, Object> readMap(String json) {
        Map<String, Object> map = read(json, MAP_TYPE);
        return map == null ? Map.of() : new LinkedHashMap<>(map);
    }

    public Map<String, Object> convertToMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?,?> rawMap) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (entry.getKey() != null) {
                    normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return normalized;
        }
        return readMap(write(value));
    }
}
