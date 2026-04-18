package com.involutionhell.backend.rag.indexing.persistence;

import com.involutionhell.backend.rag.indexing.persistence.RagIndexJobRecord;
import com.involutionhell.backend.rag.indexing.model.RagIndexStage;
import java.util.Optional;

/**
 * 离线索引作业仓储。
 */
public interface RagIndexJobRepository {

    /**
     * 创建或重置一条待分发的作业记录。
     */
    void queue(Long documentId, String contentSha256);

    /**
     * 绑定 MQ messageId，便于把消息投递和作业状态串起来追踪。
     */
    void attachMessageId(Long documentId, String contentSha256, String messageId);

    /**
     * 记录最近一次工作流事件并递增状态版本。
     */
    void annotateEvent(Long documentId, String contentSha256, String event);

    /**
     * 开启一次新的索引尝试，并记录目标 generation。
     */
    void startAttempt(Long documentId, String contentSha256, Long targetGeneration);

    /**
     * 推进作业当前阶段，不改变最终状态。
     */
    void updateStage(Long documentId, String contentSha256, RagIndexStage stage);

    /**
     * 记录一次可重试失败，让作业重新回到待处理态。
     */
    void recordRetry(Long documentId, String contentSha256, RagIndexStage stage, String errorMessage);

    /**
     * 标记作业成功完成。
     */
    void markSucceeded(Long documentId, String contentSha256, Long targetGeneration);

    /**
     * 标记作业不可恢复地失败。
     */
    void markFailed(Long documentId, String contentSha256, RagIndexStage stage, String errorMessage);

    /**
     * 标记本次消息被跳过，例如文档版本已过时或主记录不存在。
     */
    void markSkipped(Long documentId, String contentSha256, String reason);

    /**
     * 查询文档某个内容版本对应的作业状态。
     */
    Optional<RagIndexJobRecord> findByDocumentIdAndContentSha256(Long documentId, String contentSha256);

    /**
     * 删除指定文档的全部作业记录。
     */
    void deleteByDocumentId(Long documentId);
}
