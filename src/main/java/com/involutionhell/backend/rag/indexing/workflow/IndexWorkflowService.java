package com.involutionhell.backend.rag.indexing.workflow;

import com.involutionhell.backend.rag.document.spi.DocumentIndexingSpi;
import com.involutionhell.backend.rag.document.spi.DocumentIndexingView;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexJobRecord;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexJobRepository;
import org.springframework.statemachine.StateMachine;
import org.springframework.stereotype.Service;

/**
 * 索引工作流统一入口。
 */
@Service
public class IndexWorkflowService {

    private final DocumentIndexingSpi documentIndexingSpi;
    private final RagIndexJobRepository jobRepository;
    private final IndexWorkflowGuard guard;
    private final IndexWorkflowProjector projector;
    private final IndexWorkflowAuditService auditService;
    private final IndexWorkflowStateMachineFactory stateMachineFactory;

    public IndexWorkflowService(
            DocumentIndexingSpi documentIndexingSpi,
            RagIndexJobRepository jobRepository,
            IndexWorkflowGuard guard,
            IndexWorkflowProjector projector,
            IndexWorkflowAuditService auditService,
            IndexWorkflowStateMachineFactory stateMachineFactory
    ) {
        this.documentIndexingSpi = documentIndexingSpi;
        this.jobRepository = jobRepository;
        this.guard = guard;
        this.projector = projector;
        this.auditService = auditService;
        this.stateMachineFactory = stateMachineFactory;
    }

    public void queue(IndexWorkflowCommand command) {
        transition(IndexWorkflowEvent.QUEUE, command);
    }

    public void dispatch(IndexWorkflowCommand command) {
        transition(IndexWorkflowEvent.DISPATCH, command);
    }

    public void startAttempt(IndexWorkflowCommand command) {
        transition(IndexWorkflowEvent.START_ATTEMPT, command);
    }

    public void enterChunking(IndexWorkflowCommand command) {
        transition(IndexWorkflowEvent.ENTER_CHUNKING, command);
    }

    public void enterSaveChunks(IndexWorkflowCommand command) {
        transition(IndexWorkflowEvent.ENTER_SAVE_CHUNKS, command);
    }

    public void enterVectorIndexing(IndexWorkflowCommand command) {
        transition(IndexWorkflowEvent.ENTER_VECTOR_INDEXING, command);
    }

    public void enterCommitIndex(IndexWorkflowCommand command) {
        transition(IndexWorkflowEvent.ENTER_COMMIT_INDEX, command);
    }

    public void succeed(IndexWorkflowCommand command) {
        transition(IndexWorkflowEvent.SUCCEED, command);
    }

    public void retry(IndexWorkflowCommand command) {
        transition(IndexWorkflowEvent.RETRY, command);
    }

    public void fail(IndexWorkflowCommand command) {
        transition(IndexWorkflowEvent.FAIL, command);
    }

    public void skip(IndexWorkflowCommand command) {
        transition(IndexWorkflowEvent.SKIP, command);
    }

    /**
     * 给当前 job 绑定 MQ messageId，属于关联信息更新，不算状态转移。
     */
    public void attachMessageId(Long documentId, String contentSha256, String messageId) {
        jobRepository.attachMessageId(documentId, contentSha256, messageId);
    }

    private void transition(IndexWorkflowEvent event, IndexWorkflowCommand command) {
        RagIndexJobRecord currentJob = jobRepository.findByDocumentIdAndContentSha256(command.documentId(), command.contentSha256())
                .orElse(null);
        IndexWorkflowState fromState = IndexWorkflowState.from(currentJob);
        DocumentIndexingView document = documentIndexingSpi.findById(command.documentId()).orElse(null);
        guard.validate(event, fromState, document, command);

        StateMachine<IndexWorkflowState, IndexWorkflowEvent> stateMachine = stateMachineFactory.create(fromState);
        try {
            stateMachine.start();
            boolean accepted = stateMachine.sendEvent(event);
            if (!accepted) {
                throw new IndexWorkflowTransitionException(
                        "非法索引状态跃迁: currentState=" + fromState + ", event=" + event
                );
            }
            IndexWorkflowState toState = stateMachine.getState().getId();
            projector.project(fromState, toState, event, command);
            auditService.record(fromState, toState, event, command);
        } catch (IndexWorkflowTransitionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IndexWorkflowTransitionException(
                    "执行索引工作流事件失败: currentState=" + fromState + ", event=" + event,
                    exception
            );
        } finally {
            stateMachine.stop();
        }
    }
}
