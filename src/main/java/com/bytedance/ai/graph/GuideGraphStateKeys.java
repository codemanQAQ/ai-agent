package com.bytedance.ai.graph;

public final class GuideGraphStateKeys {

    public static final String USER_ID = "userId";
    public static final String CONVERSATION_ID = "conversationId";
    public static final String CONVERSATION_EXISTS = "conversationExists";
    public static final String CONVERSATION_INTERNAL_ID = "conversationInternalId";
    public static final String MESSAGE = "message";
    public static final String RUN_ID = "runId";
    public static final String REQUEST_ID = "requestId";
    public static final String IMAGE_REF = "imageRef";
    public static final String ORIGINAL_MESSAGE = "originalMessage";
    public static final String INPUT_MODALITIES = "inputModalities";
    public static final String IMAGE_CAPTION = "imageCaption";
    public static final String IMAGE_EMBEDDING_REF = "imageEmbeddingRef";
    public static final String IMAGE_PROCESSING_RESULT = "imageProcessingResult";
    public static final String CORRELATION_ID = "correlationId";
    public static final String INITIAL_INTENT = "initialIntent";
    public static final String INTENT = "intent";
    public static final String MAIN_INTENT = "mainIntent";
    public static final String SUB_INTENT = "subIntent";
    public static final String INTENT_CONFIDENCE = "intentConfidence";
    public static final String NEED_CLARIFY = "needClarify";
    public static final String WRITE_ACTION = "writeAction";
    public static final String TARGET_WORKFLOW = "targetWorkflow";
    public static final String INTENT_REASON = "intentReason";
    public static final String CLARIFY_QUESTION = "clarifyQuestion";
    public static final String INTENT_SLOTS = "intentSlots";
    public static final String MISSING_SLOTS = "missingSlots";
    public static final String ERROR_CODE = "errorCode";
    public static final String ERROR_MESSAGE = "errorMessage";
    public static final String ROUTE_SOURCE = "routeSource";
    public static final String LLM_CALLED = "llmCalled";
    public static final String GRAPH_STATUS = "graphStatus";
    public static final String LAST_NODE_RESULT = "lastNodeResult";
    public static final String NODE_RESULTS = "nodeResults";
    public static final String WORKFLOW_RESULT = "workflowResult";
    public static final String ORDER_WORKFLOW_DISPATCHED = "orderWorkflowDispatched";
    public static final String EVIDENCE = "evidence";
    public static final String BUSINESS_RESULT = "businessResult";
    public static final String ANSWER_CONTEXT = "answerContext";
    public static final String TERMINAL_STATE_WRITEBACK = "terminalStateWriteback";
    public static final String WORKFLOW_STATUS = "workflow_status";
    public static final String CLARIFY_REASON = "clarify_reason";
    public static final String CART_ACTION = "cart_action";
    public static final String PRODUCT_NAME = "product_name";
    public static final String PRODUCT_CANDIDATES = "product_candidates";
    public static final String NEED_USER_INPUT = "need_user_input";
    public static final String NODE_MESSAGE = "node_message";
    public static final String RECENT_MESSAGES = "recentMessages";
    public static final String MESSAGE_COUNT = "messageCount";
    public static final String AGENT_SESSION_STATE = "agentSessionState";
    public static final String CURRENT_TURN_MULTIMODAL_CONTEXT = "currentTurnMultimodalContext";
    public static final String UNIFIED_QUERY_CONTEXT = "unifiedQueryContext";

    private GuideGraphStateKeys() {
    }
}
