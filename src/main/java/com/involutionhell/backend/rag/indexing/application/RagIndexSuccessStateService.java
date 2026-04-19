package com.involutionhell.backend.rag.indexing.application;

import com.involutionhell.backend.rag.indexing.persistence.RagIndexOutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 成功消费后的严格 outbox 确认。
 *
 * <p>注意：这里不包裹 indexDocument() 本身，
 * 只负责在“索引已经成功完成”后做严格确认。
 * 若确认失败，抛异常给监听器，由 MQ 重试。
 */
@Service
public class RagIndexSuccessStateService {

    private final RagIndexOutboxRepository outboxRepository;

    public RagIndexSuccessStateService(RagIndexOutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @Transactional(rollbackFor = Exception.class)
    public void confirmConsumedOrThrow(Long documentId, String contentSha256, String messageId) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalStateException(
                    "RAG outbox consumption confirmation failed because messageId is blank: documentId="
                            + documentId
            );
        }

        boolean confirmed = outboxRepository.confirmConsumed(documentId, contentSha256, messageId);
        if (!confirmed) {
            throw new IllegalStateException(
                    "RAG outbox consumption confirmation failed because no matching event was found: "
                            + "messageId=" + messageId
                            + ", documentId=" + documentId
            );
        }
    }
}