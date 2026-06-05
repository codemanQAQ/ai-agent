package com.bytedance.ai.graph.ordermanage;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public record PendingOrderActionRecord(
        Long id,
        String userId,
        String conversationId,
        Map<String, Object> cartSnapshot,
        String cartSnapshotHash,
        Map<String, Object> addressSnapshot,
        BigDecimal amountSnapshot,
        OrderManageStatus status,
        String failReason,
        String orderNo,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime expireAt
) {
}
