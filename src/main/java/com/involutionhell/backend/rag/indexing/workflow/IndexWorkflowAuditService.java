package com.involutionhell.backend.rag.indexing.workflow;

import com.involutionhell.backend.rag.indexing.persistence.RagIndexJobRepository;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexJobTransitionRepository;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexOutboxRepository;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexJobRecord;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexOutboxRecord;
import com.involutionhell.backend.rag.shared.support.RagJsonCodec;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 写入索引工作流转移审计。
 */
@Service
public class IndexWorkflowAuditService {

    private final RagIndexJobTransitionRepository transitionRepository;
    private final RagIndexJobRepository jobRepository;
    private final RagIndexOutboxRepository outboxRepository;

    public IndexWorkflowAuditService(
            RagIndexJobTransitionRepository transitionRepository,
            RagIndexJobRepository jobRepository,
            RagIndexOutboxRepository outboxRepository,
            RagJsonCodec jsonCodec
    ) {
        this.transitionRepository = transitionRepository;
        this.jobRepository = jobRepository;
        this.outboxRepository = outboxRepository;
    }

    /**
     * 记录一次工作流跃迁。
     */
    public void record(
            IndexWorkflowState fromState,
            IndexWorkflowState toState,
            IndexWorkflowEvent event,
            IndexWorkflowCommand command
    ) {
        RagIndexJobRecord job = jobRepository.findByDocumentIdAndContentSha256(command.documentId(), command.contentSha256())
                .orElse(null);
        RagIndexOutboxRecord outbox = outboxRepository.findByDocumentIdAndContentSha256(command.documentId(), command.contentSha256())
                .orElse(null);
        transitionRepository.save(
                command.documentId(),
                job == null ? null : job.id(),
                outbox == null ? null : outbox.id(),
                command.contentSha256(),
                fromState == null ? null : fromState.name(),
                toState.name(),
                event.name(),
                command.triggerType().name(),
                command.triggeredBy(),
                event != IndexWorkflowEvent.FAIL && event != IndexWorkflowEvent.RETRY,
                command.failureReason(),
                command.errorMessage(),
                command.messageId(),
                toMetadata(command, job, outbox)
        );
    }

    private Map<String, Object> toMetadata(
            IndexWorkflowCommand command,
            RagIndexJobRecord job,
            RagIndexOutboxRecord outbox
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>(command.metadata());
        metadata.put("targetGeneration", command.targetGeneration());
        metadata.put("chunkCount", command.chunkCount());
        metadata.put("note", command.note());
        if (job != null) {
            metadata.put("jobStatus", job.status());
            metadata.put("jobStage", job.stage());
            metadata.put("jobVersion", job.version());
            metadata.put("jobLastEvent", job.lastEvent());
        }
        if (outbox != null) {
            metadata.put("outboxStatus", outbox.status());
            metadata.put("outboxEventType", outbox.eventType());
            metadata.put("outboxAttemptCount", outbox.attemptCount());
        }
        return metadata;
    }
}
