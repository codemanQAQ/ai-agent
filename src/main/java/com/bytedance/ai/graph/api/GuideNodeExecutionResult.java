package com.bytedance.ai.graph.api;

import java.util.Map;

public record GuideNodeExecutionResult(
        NodeRunStatus statusOverride,
        GuideGraphIntent routeIntent,
        Object workflowResult,
        Map<String, Object> stateUpdates,
        Map<String, Object> metadata
) {
    public GuideNodeExecutionResult {
        stateUpdates = stateUpdates == null ? Map.of() : Map.copyOf(stateUpdates);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static GuideNodeExecutionResult success(Map<String, Object> metadata) {
        return new GuideNodeExecutionResult(null, null, null, Map.of(), metadata);
    }

    public static GuideNodeExecutionResult route(GuideGraphIntent intent, String targetWorkflow) {
        return new GuideNodeExecutionResult(
                null,
                intent,
                null,
                Map.of("targetWorkflow", targetWorkflow),
                Map.of("mockRouter", true)
        );
    }

    public static GuideNodeExecutionResult workflow(Object workflowResult, Map<String, Object> metadata) {
        return new GuideNodeExecutionResult(null, null, workflowResult, Map.of("workflowResult", workflowResult), metadata);
    }

    public static GuideNodeExecutionResult withStateUpdates(Map<String, Object> stateUpdates, Map<String, Object> metadata) {
        return new GuideNodeExecutionResult(null, null, null, stateUpdates, metadata);
    }

    public static GuideNodeExecutionResult waitingClarification(
            Object workflowResult,
            Map<String, Object> metadata
    ) {
        return new GuideNodeExecutionResult(
                NodeRunStatus.WAITING_CLARIFICATION,
                null,
                workflowResult,
                Map.of("workflowResult", workflowResult),
                metadata
        );
    }

    public static GuideNodeExecutionResult waitingConfirmation(
            Object workflowResult,
            Map<String, Object> metadata
    ) {
        return new GuideNodeExecutionResult(
                NodeRunStatus.WAITING_CONFIRMATION,
                null,
                workflowResult,
                Map.of("workflowResult", workflowResult),
                metadata
        );
    }
}
