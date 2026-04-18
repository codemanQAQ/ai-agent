package com.involutionhell.backend.rag.indexing.messaging;

/**
 * 索引任务投递抽象。
 */
public interface RagIndexEventPublisher {

    /**
     * 发布索引任务，并在可用时返回底层消息系统分配的 messageId。
     */
    String publish(Long documentId, String contentSha256);
}
