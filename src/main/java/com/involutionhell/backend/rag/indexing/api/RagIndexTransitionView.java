package com.involutionhell.backend.rag.indexing.api;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 索引状态转移审计视图。
 *
 * @param id 审计主键
 * @param fromState 跃迁前状态
 * @param toState 跃迁后状态
 * @param event 触发事件
 * @param triggerType 触发类型
 * @param triggeredBy 触发者
 * @param success 跃迁是否成功
 * @param failureReason 失败原因分类
 * @param errorMessage 错误详情
 * @param messageId 关联 MQ messageId
 * @param metadata 附加元数据
 * @param createdAt 创建时间
 */
public record RagIndexTransitionView(
        Long id,
        String fromState,
        String toState,
        String event,
        String triggerType,
        String triggeredBy,
        boolean success,
        String failureReason,
        String errorMessage,
        String messageId,
        Map<String, Object> metadata,
        OffsetDateTime createdAt
) {
}
