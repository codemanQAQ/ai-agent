package com.involutionhell.backend.rag.indexing.application;

import com.involutionhell.backend.rag.document.spi.DocumentIndexingSpi;
import com.involutionhell.backend.rag.document.spi.DocumentIndexingView;
import com.involutionhell.backend.rag.indexing.api.RagIndexJobView;
import com.involutionhell.backend.rag.indexing.api.RagIndexOutboxView;
import com.involutionhell.backend.rag.indexing.api.RagIndexTimelineDocumentView;
import com.involutionhell.backend.rag.indexing.api.RagIndexTimelineView;
import com.involutionhell.backend.rag.indexing.api.RagIndexTransitionView;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexJobRepository;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexJobTransitionRepository;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexOutboxRepository;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexJobRecord;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexJobTransitionRecord;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexOutboxRecord;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 聚合文档、job、outbox 与状态转移审计，构建索引时间线视图。
 */
@Service
public class RagIndexTimelineService {

    private final DocumentIndexingSpi documentIndexingSpi;
    private final RagIndexJobRepository jobRepository;
    private final RagIndexOutboxRepository outboxRepository;
    private final RagIndexJobTransitionRepository transitionRepository;

    public RagIndexTimelineService(
            DocumentIndexingSpi documentIndexingSpi,
            RagIndexJobRepository jobRepository,
            RagIndexOutboxRepository outboxRepository,
            RagIndexJobTransitionRepository transitionRepository
    ) {
        this.documentIndexingSpi = documentIndexingSpi;
        this.jobRepository = jobRepository;
        this.outboxRepository = outboxRepository;
        this.transitionRepository = transitionRepository;
    }

    /**
     * 查询当前文档版本的完整索引时间线。
     */
    public RagIndexTimelineView getTimeline(Long documentId) {
        DocumentIndexingView document = documentIndexingSpi.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("RAG 文档不存在: " + documentId));
        RagIndexJobRecord job = jobRepository.findByDocumentIdAndContentSha256(documentId, document.contentSha256())
                .orElse(null);
        RagIndexOutboxRecord outbox = outboxRepository.findByDocumentIdAndContentSha256(documentId, document.contentSha256())
                .orElse(null);
        List<RagIndexTransitionView> transitions = transitionRepository.findByDocumentIdAndContentSha256(
                        documentId,
                        document.contentSha256()
                ).stream()
                .map(this::toTransitionView)
                .toList();
        return new RagIndexTimelineView(
                toDocumentView(document),
                toJobView(job),
                toOutboxView(outbox),
                transitions
        );
    }

    private RagIndexTimelineDocumentView toDocumentView(DocumentIndexingView record) {
        return new RagIndexTimelineDocumentView(
                record.id(),
                record.sourceType(),
                record.sourceUri(),
                record.externalRef(),
                record.title(),
                record.status(),
                record.chunkCount(),
                record.attemptCount(),
                record.lastError(),
                record.lastAttemptedAt(),
                record.indexedAt(),
                record.createdAt(),
                record.updatedAt()
        );
    }

    private RagIndexJobView toJobView(RagIndexJobRecord record) {
        if (record == null) {
            return null;
        }
        return new RagIndexJobView(
                record.id(),
                record.documentId(),
                record.contentSha256(),
                record.status(),
                record.stage(),
                record.version(),
                record.lastEvent(),
                record.attemptCount(),
                record.targetGeneration(),
                record.messageId(),
                record.lastError(),
                record.startedAt(),
                record.finishedAt(),
                record.createdAt(),
                record.updatedAt()
        );
    }

    private RagIndexOutboxView toOutboxView(RagIndexOutboxRecord record) {
        if (record == null) {
            return null;
        }
        return new RagIndexOutboxView(
                record.id(),
                record.documentId(),
                record.contentSha256(),
                record.eventType(),
                record.status(),
                record.attemptCount(),
                record.messageId(),
                record.lastError(),
                record.nextAttemptAt(),
                record.dispatchedAt(),
                record.consumedAt(),
                record.createdAt(),
                record.updatedAt()
        );
    }

    private RagIndexTransitionView toTransitionView(RagIndexJobTransitionRecord record) {
        return new RagIndexTransitionView(
                record.id(),
                record.fromState(),
                record.toState(),
                record.event(),
                record.triggerType(),
                record.triggeredBy(),
                record.success(),
                record.failureReason(),
                record.errorMessage(),
                record.messageId(),
                record.metadata(),
                record.createdAt()
        );
    }
}
