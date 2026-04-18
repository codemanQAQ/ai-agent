package com.involutionhell.backend.rag.indexing.persistence;

import com.involutionhell.backend.rag.indexing.persistence.RagIndexOutboxRecord;
import com.involutionhell.backend.rag.indexing.model.RagIndexOutboxEventType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 离线索引任务 Outbox 仓储。
 */
public interface RagIndexOutboxRepository {

    /**
     * 写入或重置一条待投递的 Outbox 事件。
     */
    void enqueue(Long documentId, String contentSha256, RagIndexOutboxEventType eventType);

    /**
     * 查询当前应该被 dispatcher 投递的事件。
     */
    List<RagIndexOutboxRecord> findDispatchable(OffsetDateTime now, int limit);

    /**
     * 把事件占用为发送中，避免多个调度线程重复分发。
     */
    boolean markSending(Long id);

    /**
     * 标记事件已成功发往 MQ。
     */
    void markSent(Long id, String messageId);

    /**
     * 记录消费端已经对指定消息完成终态处理，供 outbox 补偿做强确认。
     */
    boolean confirmConsumed(Long documentId, String contentSha256, String messageId);

    /**
     * 在无法解析业务负载时，仅根据 producer messageId 回写消费确认。
     */
    boolean confirmConsumedByMessageId(String messageId);

    /**
     * 标记事件投递失败，并设置下一次重试时间。
     */
    void markFailed(Long id, String errorMessage, OffsetDateTime nextAttemptAt);

    /**
     * 查询长时间卡在发送中的事件，供补偿任务接管。
     */
    List<RagIndexOutboxRecord> findStuckSendingBefore(OffsetDateTime cutoff, int limit);

    /**
     * 把卡住的发送中事件重置回失败待重试状态。
     */
    void resetForRetry(Long id, String errorMessage, OffsetDateTime nextAttemptAt);

    /**
     * 查询文档当前版本最近的一条 Outbox 记录。
     */
    Optional<RagIndexOutboxRecord> findByDocumentIdAndContentSha256(Long documentId, String contentSha256);

    /**
     * 删除某篇文档全部尚存的 Outbox 事件，供两阶段删除时阻断后续投递。
     */
    void deleteByDocumentId(Long documentId);
}
