package com.bytedance.ai.graph.session;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class UserPreferenceMemoryService {

    private final Map<String, Map<String, Object>> memoryByUser = new ConcurrentHashMap<>();

    public UserBehaviorFeedbackResult accept(UserBehaviorFeedbackRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("feedback request is required");
        }
        String userId = requireText(request.userId(), "userId");
        String productId = firstText(request.productId(), request.externalRef());
        if (productId == null) {
            throw new IllegalArgumentException("productId or externalRef is required");
        }
        UserBehaviorType type = UserBehaviorType.parse(request.behaviorType());
        Map<String, Object> preferenceMemory = memoryByUser.compute(userId, (key, current) ->
                updateMemory(current, request, type, productId));
        return new UserBehaviorFeedbackResult(
                UUID.randomUUID().toString(),
                type,
                userId,
                request.productId(),
                request.skuId(),
                request.externalRef(),
                preferenceMemory,
                Instant.now()
        );
    }

    public Map<String, Object> memory(String userId) {
        if (userId == null || userId.isBlank()) {
            return Map.of();
        }
        return Map.copyOf(memoryByUser.getOrDefault(userId, Map.of()));
    }

    private Map<String, Object> updateMemory(
            Map<String, Object> current,
            UserBehaviorFeedbackRequest request,
            UserBehaviorType type,
            String productId
    ) {
        Map<String, Object> updated = new LinkedHashMap<>(current == null ? Map.of() : current);
        increment(updated, "feedbackCount");
        increment(updated, type.name());
        if (type == UserBehaviorType.CLICK_PRODUCT || type == UserBehaviorType.ADD_TO_CART) {
            appendLatest(updated, "positiveProductIds", productId);
        }
        if (type == UserBehaviorType.SKIP_PRODUCT || type == UserBehaviorType.NOT_INTERESTED) {
            appendLatest(updated, "negativeProductIds", productId);
        }
        if (request.context() != null && !request.context().isEmpty()) {
            updated.put("lastContext", request.context());
        }
        updated.put("updatedAt", Instant.now().toString());
        return Map.copyOf(updated);
    }

    private void increment(Map<String, Object> map, String key) {
        Object current = map.get(key);
        int value = current instanceof Number number ? number.intValue() : 0;
        map.put(key, value + 1);
    }

    private void appendLatest(Map<String, Object> map, String key, String productId) {
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        Object current = map.get(key);
        if (current instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    values.add(String.valueOf(item));
                }
            }
        }
        values.add(productId);
        map.put(key, java.util.List.copyOf(values));
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private String firstText(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }
}
