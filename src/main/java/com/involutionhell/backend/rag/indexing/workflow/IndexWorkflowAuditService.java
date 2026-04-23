package com.involutionhell.backend.rag.indexing.workflow;

import com.involutionhell.backend.rag.indexing.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

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
            RagIndexOutboxRepository outboxRepository
    ) {
        this.transitionRepository = transitionRepository;
        this.jobRepository = jobRepository;
        this.outboxRepository = outboxRepository;
    }

    /**
     * 记录一次工作流跃迁。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(
            IndexWorkflowState fromState,
            IndexWorkflowState toState,
            IndexWorkflowEvent event,
            IndexWorkflowCommand command
    ) {
        RagIndexJobRecord job = jobRepository.findByDocumentIdAndContentSha256(command.documentId(), command.contentSha256())
                .orElse(null);

        RagIndexOutboxRecord outbox = null;
        if (toState == IndexWorkflowState.DISPATCHING) {
            outbox = outboxRepository.findByDocumentIdAndContentSha256(command.documentId(), command.contentSha256())
                    .orElse(null);
        }

        transitionRepository.save(
                command.documentId(),
                job == null ? null : job.id(),
                outbox == null ? null : outbox.id(),
                command.contentSha256(),
                fromState == null ? "NEW" : fromState.name(), // 明确 NEW 状态
                toState.name(),
                event.name(),
                command.triggerType().name(),
                command.triggeredBy(),
                // 成功标志：非失败且非重试
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
