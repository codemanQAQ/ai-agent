package com.bytedance.ai.graph.ordermanage;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

public interface MockOrderRepository {

    MockOrderRecord create(
            String orderNo,
            String userId,
            String conversationId,
            Map<String, Object> items,
            Map<String, Object> address,
            BigDecimal totalAmount
    );

    default Optional<MockOrderRecord> findByOrderNo(String userId, String conversationId, String orderNo) {
        return Optional.empty();
    }

    default Optional<MockOrderRecord> findLatest(String userId, String conversationId) {
        return Optional.empty();
    }
}
