package com.involutionhell.backend.rag.indexing.workflow;

import java.util.EnumSet;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineBuilder;
import org.springframework.stereotype.Component;

/**
 * 为索引链路按需构建状态机。
 */
@Component
public class IndexWorkflowStateMachineFactory {

    /**
     * 基于当前状态创建一个新的状态机实例。
     */
    public StateMachine<IndexWorkflowState, IndexWorkflowEvent> create(IndexWorkflowState initialState) {
        try {
            StateMachineBuilder.Builder<IndexWorkflowState, IndexWorkflowEvent> builder = StateMachineBuilder.builder();
            builder.configureConfiguration()
                    .withConfiguration()
                    .autoStartup(false);
            builder.configureStates()
                    .withStates()
                    .initial(initialState)
                    .states(EnumSet.allOf(IndexWorkflowState.class));
            builder.configureTransitions()
                    .withExternal().source(IndexWorkflowState.NEW).target(IndexWorkflowState.QUEUED).event(IndexWorkflowEvent.QUEUE)
                    .and()
                    .withExternal().source(IndexWorkflowState.QUEUED).target(IndexWorkflowState.QUEUED).event(IndexWorkflowEvent.QUEUE)
                    .and()
                    .withExternal().source(IndexWorkflowState.FAILED).target(IndexWorkflowState.QUEUED).event(IndexWorkflowEvent.QUEUE)
                    .and()
                    .withExternal().source(IndexWorkflowState.SKIPPED).target(IndexWorkflowState.QUEUED).event(IndexWorkflowEvent.QUEUE)
                    .and()
                    .withExternal().source(IndexWorkflowState.COMPLETED).target(IndexWorkflowState.QUEUED).event(IndexWorkflowEvent.QUEUE)
                    .and()
                    .withExternal().source(IndexWorkflowState.QUEUED).target(IndexWorkflowState.DISPATCHING).event(IndexWorkflowEvent.DISPATCH)
                    .and()
                    .withExternal().source(IndexWorkflowState.QUEUED).target(IndexWorkflowState.PREPARING).event(IndexWorkflowEvent.START_ATTEMPT)
                    .and()
                    .withExternal().source(IndexWorkflowState.DISPATCHING).target(IndexWorkflowState.PREPARING).event(IndexWorkflowEvent.START_ATTEMPT)
                    .and()
                    .withExternal().source(IndexWorkflowState.PREPARING).target(IndexWorkflowState.CHUNKING).event(IndexWorkflowEvent.ENTER_CHUNKING)
                    .and()
                    .withExternal().source(IndexWorkflowState.CHUNKING).target(IndexWorkflowState.SAVE_CHUNKS).event(IndexWorkflowEvent.ENTER_SAVE_CHUNKS)
                    .and()
                    .withExternal().source(IndexWorkflowState.SAVE_CHUNKS).target(IndexWorkflowState.VECTOR_INDEXING).event(IndexWorkflowEvent.ENTER_VECTOR_INDEXING)
                    .and()
                    .withExternal().source(IndexWorkflowState.SAVE_CHUNKS).target(IndexWorkflowState.COMMIT_INDEX).event(IndexWorkflowEvent.ENTER_COMMIT_INDEX)
                    .and()
                    .withExternal().source(IndexWorkflowState.VECTOR_INDEXING).target(IndexWorkflowState.COMMIT_INDEX).event(IndexWorkflowEvent.ENTER_COMMIT_INDEX)
                    .and()
                    .withExternal().source(IndexWorkflowState.COMMIT_INDEX).target(IndexWorkflowState.COMPLETED).event(IndexWorkflowEvent.SUCCEED)
                    .and()
                    .withExternal().source(IndexWorkflowState.DISPATCHING).target(IndexWorkflowState.QUEUED).event(IndexWorkflowEvent.RETRY)
                    .and()
                    .withExternal().source(IndexWorkflowState.PREPARING).target(IndexWorkflowState.QUEUED).event(IndexWorkflowEvent.RETRY)
                    .and()
                    .withExternal().source(IndexWorkflowState.CHUNKING).target(IndexWorkflowState.QUEUED).event(IndexWorkflowEvent.RETRY)
                    .and()
                    .withExternal().source(IndexWorkflowState.SAVE_CHUNKS).target(IndexWorkflowState.QUEUED).event(IndexWorkflowEvent.RETRY)
                    .and()
                    .withExternal().source(IndexWorkflowState.VECTOR_INDEXING).target(IndexWorkflowState.QUEUED).event(IndexWorkflowEvent.RETRY)
                    .and()
                    .withExternal().source(IndexWorkflowState.COMMIT_INDEX).target(IndexWorkflowState.QUEUED).event(IndexWorkflowEvent.RETRY)
                    .and()
                    .withExternal().source(IndexWorkflowState.QUEUED).target(IndexWorkflowState.FAILED).event(IndexWorkflowEvent.FAIL)
                    .and()
                    .withExternal().source(IndexWorkflowState.DISPATCHING).target(IndexWorkflowState.FAILED).event(IndexWorkflowEvent.FAIL)
                    .and()
                    .withExternal().source(IndexWorkflowState.PREPARING).target(IndexWorkflowState.FAILED).event(IndexWorkflowEvent.FAIL)
                    .and()
                    .withExternal().source(IndexWorkflowState.CHUNKING).target(IndexWorkflowState.FAILED).event(IndexWorkflowEvent.FAIL)
                    .and()
                    .withExternal().source(IndexWorkflowState.SAVE_CHUNKS).target(IndexWorkflowState.FAILED).event(IndexWorkflowEvent.FAIL)
                    .and()
                    .withExternal().source(IndexWorkflowState.VECTOR_INDEXING).target(IndexWorkflowState.FAILED).event(IndexWorkflowEvent.FAIL)
                    .and()
                    .withExternal().source(IndexWorkflowState.COMMIT_INDEX).target(IndexWorkflowState.FAILED).event(IndexWorkflowEvent.FAIL)
                    .and()
                    .withExternal().source(IndexWorkflowState.NEW).target(IndexWorkflowState.SKIPPED).event(IndexWorkflowEvent.SKIP)
                    .and()
                    .withExternal().source(IndexWorkflowState.QUEUED).target(IndexWorkflowState.SKIPPED).event(IndexWorkflowEvent.SKIP)
                    .and()
                    .withExternal().source(IndexWorkflowState.DISPATCHING).target(IndexWorkflowState.SKIPPED).event(IndexWorkflowEvent.SKIP)
                    .and()
                    .withExternal().source(IndexWorkflowState.PREPARING).target(IndexWorkflowState.SKIPPED).event(IndexWorkflowEvent.SKIP)
                    .and()
                    .withExternal().source(IndexWorkflowState.CHUNKING).target(IndexWorkflowState.SKIPPED).event(IndexWorkflowEvent.SKIP)
                    .and()
                    .withExternal().source(IndexWorkflowState.SAVE_CHUNKS).target(IndexWorkflowState.SKIPPED).event(IndexWorkflowEvent.SKIP)
                    .and()
                    .withExternal().source(IndexWorkflowState.VECTOR_INDEXING).target(IndexWorkflowState.SKIPPED).event(IndexWorkflowEvent.SKIP)
                    .and()
                    .withExternal().source(IndexWorkflowState.COMMIT_INDEX).target(IndexWorkflowState.SKIPPED).event(IndexWorkflowEvent.SKIP);
            return builder.build();
        } catch (Exception exception) {
            throw new IndexWorkflowTransitionException("创建索引状态机失败", exception);
        }
    }
}
