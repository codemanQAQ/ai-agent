package com.bytedance.ai.graph.intent;

import java.util.List;
import java.util.Map;

public record MainIntentDecision(
        MainIntent intent,
        double confidence,
        boolean needClarify,
        boolean writeAction,
        String targetWorkflow,
        String subIntent,
        String reason,
        String clarifyQuestion,
        Map<String, Object> slots,
        List<String> missingSlots
) {
    public MainIntentDecision(
            MainIntent intent,
            double confidence,
            boolean needClarify,
            boolean writeAction,
            String targetWorkflow,
            String reason,
            Map<String, Object> slots,
            List<String> missingSlots
    ) {
        this(intent, confidence, needClarify, writeAction, targetWorkflow, null, reason, null, slots, missingSlots);
    }

    public MainIntentDecision(
            MainIntent intent,
            double confidence,
            boolean needClarify,
            boolean writeAction,
            String targetWorkflow,
            String reason,
            String clarifyQuestion,
            Map<String, Object> slots,
            List<String> missingSlots
    ) {
        this(intent, confidence, needClarify, writeAction, targetWorkflow, null, reason, clarifyQuestion, slots, missingSlots);
    }

    public static MainIntentDecision clarify(String reason) {
        return new MainIntentDecision(
                MainIntent.CLARIFY,
                0.0d,
                true,
                false,
                "clarify_workflow",
                "CLARIFY",
                reason,
                null,
                Map.of(),
                List.of()
        );
    }
}
