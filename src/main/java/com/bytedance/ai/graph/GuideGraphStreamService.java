package com.bytedance.ai.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.bytedance.ai.graph.api.AgentStreamEvent;
import com.bytedance.ai.graph.api.*;
import com.bytedance.ai.graph.cartmanage.subgraph.CartGraphStateKeys;
import com.bytedance.ai.graph.conversation.AgentConversationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GuideGraphStreamService implements GuideGraphStreamFacade {

    private static final Logger log = LoggerFactory.getLogger(GuideGraphStreamService.class);
    private static final String GRAPH_FAILURE_FALLBACK_ANSWER = "购物车操作暂时失败了，请稍后再试。";

    private final GuideStateGraphFactory graphFactory;
    private final AgentConversationRepository conversationRepository;
    private final com.bytedance.ai.graph.productrecommend.ProductRecommendationAnswerGenerator answerGenerator;

    public GuideGraphStreamService(
            GuideStateGraphFactory graphFactory,
            AgentConversationRepository conversationRepository
    ) {
        this.graphFactory = graphFactory;
        this.conversationRepository = conversationRepository;
        this.answerGenerator = null;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GuideGraphStreamService(
            GuideStateGraphFactory graphFactory,
            AgentConversationRepository conversationRepository,
            org.springframework.beans.factory.ObjectProvider<
                    com.bytedance.ai.graph.productrecommend.ProductRecommendationAnswerGenerator> answerGeneratorProvider
    ) {
        this.graphFactory = graphFactory;
        this.conversationRepository = conversationRepository;
        this.answerGenerator = answerGeneratorProvider.getIfAvailable();
    }

    @Override
    public Flux<AgentStreamEvent> turnStream(GuideGraphRequest request) {
        return Flux.defer(() -> {
            Sinks.Many<AgentStreamEvent> eventSink =
                    Sinks.many().multicast().onBackpressureBuffer();

            emitNext(eventSink, GuideGraphStreamEvents.turnStarted(request));

            Mono.fromRunnable(() -> {
                        try {
                            // TODO: skeleton only. Per-request compile is acceptable for now.
                            // Production should use singleton/cached compiled graph.
                            CompiledGraph graph = graphFactory.compile(event -> emitNext(eventSink, event));

                            OverAllState finalState = graph.invoke(
                                            initialState(request),
                                            RunnableConfig.builder().threadId(request.runId()).build()
                                    )
                                    .orElseThrow(() -> new IllegalStateException("Guide graph completed without final state"));

                            GuideGraphFinalSummary summary = finalSummary(request, finalState);
                            conversationRepository.createOrUpdateTurn(
                                    request.userId(),
                                    request.conversationId(),
                                    request.runId(),
                                    request.requestId(),
                                    summary.status().name(),
                                    summary.intent().name(),
                                    summary.targetWorkflow()
                            );

                            if (summary.status() == NodeRunStatus.FAILED) {
                                emitNext(eventSink, GuideGraphStreamEvents.turnError(
                                        request.correlationId(),
                                        safeErrorCode(summary.errorCode()),
                                        publicErrorMessage(summary.errorCode(), summary.errorMessage()),
                                        recoverableError(summary.errorCode())
                                ));
                            } else {
                                productCards(finalState).ifPresent(products -> emitNext(eventSink, GuideGraphStreamEvents.productCards(
                                        request.correlationId(),
                                        products
                                )));
                                emitAnswer(eventSink, request, summary, finalState);
                            }

                            emitNext(eventSink, GuideGraphStreamEvents.turnCompleted(
                                    request.correlationId(),
                                    summary
                            ));

                            eventSink.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST);
                        } catch (Exception exception) {
                            log.error("Guide graph stream failed: runId={}, requestId={}, userId={}, conversationId={}",
                                    request.runId(), request.requestId(), request.userId(), request.conversationId(), exception);
                            markTurnFailed(request);
                            String errorCode = "GUIDE_GRAPH_FAILED";
                            String errorMessage = publicErrorMessage(errorCode, safeMessage(exception));
                            saveAssistantMessage(request, errorMessage, "FAILED");
                            emitNext(eventSink, GuideGraphStreamEvents.turnError(
                                    request.correlationId(),
                                    errorCode,
                                    errorMessage,
                                    true
                            ));
                            emitNext(eventSink, GuideGraphStreamEvents.turnCompleted(
                                    request.correlationId(),
                                    GuideGraphFinalSummary.failed(
                                            request,
                                            request.initialIntent() == null ? GuideGraphIntent.CLARIFY : request.initialIntent(),
                                            null,
                                            null,
                                            errorCode,
                                            safeMessage(exception)
                                    )
                            ));
                            eventSink.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST);
                        }
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe();

            return eventSink.asFlux();
        });
    }

    private void emitNext(Sinks.Many<AgentStreamEvent> sink, AgentStreamEvent event) {
        sink.emitNext(event, Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(100)));
    }

    /**
     * 发射最终答案：商品推荐且有候选商品时，逐 token 流式发 answer.delta（首字亚秒级）；
     * 其它情况（或流式不可用/失败）回退为原来的单次 delta。
     */
    private void emitAnswer(
            Sinks.Many<AgentStreamEvent> sink,
            GuideGraphRequest request,
            GuideGraphFinalSummary summary,
            OverAllState finalState
    ) {
        Map<String, Object> answerContext = answerContextMap(finalState);
        String targetWorkflow = finalState.value(GuideGraphStateKeys.TARGET_WORKFLOW, "");
        boolean streamable = answerGenerator != null
                && GuideGraphNodeNames.PRODUCT_RECOMMEND_WORKFLOW.equals(targetWorkflow)
                && summary.status() != NodeRunStatus.FAILED
                && hasProducts(answerContext);

        if (streamable) {
            String fallback = answerText(finalState).orElse("");
            StringBuilder full = new StringBuilder();
            try {
                for (String chunk : answerGenerator.generateStream(answerContext, fallback).toIterable()) {
                    if (chunk != null && !chunk.isEmpty()) {
                        full.append(chunk);
                        emitNext(sink, GuideGraphStreamEvents.answerDelta(request.correlationId(), chunk));
                    }
                }
            } catch (Exception exception) {
                log.warn("streaming recommendation answer failed, fallback used: {}", exception.getMessage());
            }
            String finalAnswer = full.length() > 0 ? full.toString().trim() : fallback;
            if (full.length() == 0 && org.springframework.util.StringUtils.hasText(finalAnswer)) {
                // 流式没产出（失败/空）→ 把确定性兜底文案作为单次 delta 补发
                emitNext(sink, GuideGraphStreamEvents.answerDelta(request.correlationId(), finalAnswer));
            }
            if (org.springframework.util.StringUtils.hasText(finalAnswer)) {
                String messageId = saveAssistantMessage(request, finalAnswer, "SUCCEEDED");
                if (messageId != null) {
                    emitNext(sink, GuideGraphStreamEvents.answerCompleted(request.correlationId(), messageId));
                }
            }
            return;
        }

        // 非商品推荐 / 无候选：保持原单次 delta 行为
        answerText(finalState).ifPresent(answer -> {
            String streamAnswer = statusConsistentAnswer(summary, answer);
            String messageId = saveAssistantMessage(request, streamAnswer, "SUCCEEDED");
            emitNext(sink, GuideGraphStreamEvents.answerDelta(request.correlationId(), streamAnswer));
            if (messageId != null) {
                emitNext(sink, GuideGraphStreamEvents.answerCompleted(request.correlationId(), messageId));
            }
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> answerContextMap(OverAllState state) {
        Object answerContext = state.value(GuideGraphStateKeys.ANSWER_CONTEXT).orElse(null);
        if (answerContext instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private boolean hasProducts(Map<String, Object> answerContext) {
        Object products = answerContext.get("products");
        return products instanceof List<?> list && !list.isEmpty();
    }

    private Map<String, Object> initialState(GuideGraphRequest request) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put(GuideGraphStateKeys.USER_ID, request.userId());
        state.put(GuideGraphStateKeys.CONVERSATION_ID, request.conversationId());
        state.put(GuideGraphStateKeys.MESSAGE, request.message());
        state.put(GuideGraphStateKeys.RUN_ID, request.runId());
        state.put(GuideGraphStateKeys.REQUEST_ID, request.requestId());
        state.put(GuideGraphStateKeys.IMAGE_REF, request.imageRef());
        state.put(GuideGraphStateKeys.ORIGINAL_MESSAGE, request.originalMessage());
        state.put(GuideGraphStateKeys.INPUT_MODALITIES, request.inputModalities());
        state.put(GuideGraphStateKeys.IMAGE_CAPTION, request.imageCaption());
        state.put(GuideGraphStateKeys.IMAGE_EMBEDDING_REF, request.imageEmbeddingRef());
        state.put(GuideGraphStateKeys.CORRELATION_ID, request.correlationId());
        if (request.initialIntent() != null) {
            state.put(GuideGraphStateKeys.INITIAL_INTENT, request.initialIntent().name());
        }
        state.values().removeIf(value -> value == null);
        return state;
    }

    @SuppressWarnings("unchecked")
    private java.util.Optional<String> answerText(OverAllState state) {
        Object answerContext = state.value(GuideGraphStateKeys.ANSWER_CONTEXT).orElse(null);
        if (answerContext instanceof Map<?, ?> map) {
            Object answer = map.get("answer");
            if (answer != null && !String.valueOf(answer).isBlank()) {
                return java.util.Optional.of(String.valueOf(answer));
            }
        }
        String targetWorkflow = state.value(GuideGraphStateKeys.TARGET_WORKFLOW, "");
        if (GuideGraphNodeNames.CART_MANAGE_WORKFLOW.equals(targetWorkflow)) {
            return state.value(CartGraphStateKeys.NODE_MESSAGE)
                    .map(Object::toString)
                    .filter(value -> !value.isBlank());
        }
        return java.util.Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private java.util.Optional<Object> productCards(OverAllState state) {
        Object answerContext = state.value(GuideGraphStateKeys.ANSWER_CONTEXT).orElse(null);
        if (answerContext instanceof Map<?, ?> map) {
            Object products = map.get("products");
            if (products instanceof List<?> list && !list.isEmpty()) {
                return java.util.Optional.of(products);
            }
        }
        return java.util.Optional.empty();
    }

    private String statusConsistentAnswer(GuideGraphFinalSummary summary, String answer) {
        if (summary.status() == NodeRunStatus.FAILED && looksLikeSuccessFallback(answer)) {
            return GRAPH_FAILURE_FALLBACK_ANSWER;
        }
        if ((summary.status() == NodeRunStatus.WAITING_CLARIFICATION
                || summary.intent() == GuideGraphIntent.CLARIFY)
                && looksLikeSuccessFallback(answer)) {
            return "我暂时没能识别你的操作，请换一种说法，例如‘查看购物车’、‘添加商品到购物车’或‘结算购物车’。";
        }
        return answer;
    }

    private boolean looksLikeSuccessFallback(String answer) {
        return answer != null && (answer.contains("我已经处理完成") || answer.equals("处理完成。"));
    }

    private String saveAssistantMessage(GuideGraphRequest request, String answer, String status) {
        if (answer == null || answer.isBlank()) {
            return null;
        }
        try {
            return conversationRepository.saveAssistantMessage(
                    request.userId(),
                    request.conversationId(),
                    request.runId(),
                    request.correlationId(),
                    answer,
                    status
            ).messageId();
        } catch (Exception exception) {
            log.warn("Failed to save assistant message: runId={}, requestId={}, status={}",
                    request.runId(), request.requestId(), status, exception);
            return null;
        }
    }

    private void markTurnFailed(GuideGraphRequest request) {
        try {
            conversationRepository.createOrUpdateTurn(
                    request.userId(),
                    request.conversationId(),
                    request.runId(),
                    request.requestId(),
                    NodeRunStatus.FAILED.name(),
                    request.initialIntent() == null ? null : request.initialIntent().name(),
                    null
            );
        } catch (Exception exception) {
            log.warn("Failed to mark guide graph turn failed: runId={}, requestId={}",
                    request.runId(), request.requestId(), exception);
        }
    }

    private GuideGraphFinalSummary finalSummary(GuideGraphRequest request, OverAllState state) {
        GuideGraphIntent intent = GuideGraphStateValues.intent(state, GuideGraphStateKeys.INTENT)
                .orElse(GuideGraphIntent.CLARIFY);
        String targetWorkflow = state.value(GuideGraphStateKeys.TARGET_WORKFLOW, GuideGraphWorkflows.targetFor(intent));
        List<GuideNodeResult> nodeResults = state.value(GuideGraphStateKeys.NODE_RESULTS, List.of());
        NodeRunStatus status = GuideGraphFinalSummaryFactory.calculateFinalStatus(
                nodeResults,
                state.value(GuideGraphStateKeys.WORKFLOW_RESULT).orElse(null)
        );

        if (status == NodeRunStatus.FAILED) {
            GuideNodeResult failedNode = nodeResults.stream()
                    .filter(result -> result != null && result.status() == NodeRunStatus.FAILED)
                    .findFirst()
                    .orElse(null);
            return GuideGraphFinalSummary.failed(
                    request,
                    intent,
                    targetWorkflow,
                    failedNode == null ? null : failedNode.nodeName(),
                    failedNode == null ? "GUIDE_GRAPH_UNKNOWN_FAILURE" : failedNode.errorCode(),
                    failedNode == null ? "Guide graph reported FAILED status but no failed node was recorded"
                            : failedNode.errorMessage()
            );
        }

        GuideNodeResult finalNode = state.<GuideNodeResult>value(GuideGraphStateKeys.LAST_NODE_RESULT).orElse(null);
        return GuideGraphFinalSummary.success(
                request,
                intent,
                targetWorkflow,
                status,
                finalNode == null ? null : finalNode.nodeName()
        );
    }

    private String safeMessage(Throwable exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? "Guide graph failed"
                : exception.getMessage();
    }

    private String safeErrorCode(String errorCode) {
        return errorCode == null || errorCode.isBlank() ? "GUIDE_GRAPH_FAILED" : errorCode;
    }

    private String publicErrorMessage(String errorCode, String fallbackMessage) {
        return switch (safeErrorCode(errorCode)) {
            case "MAIN_INTENT_LLM_TIMEOUT" -> "当前请求处理超时，请稍后重试。";
            case "ORDER_WORKFLOW_NOT_DISPATCHED" -> "下单流程没有被正确执行，请检查主图 workflow 路由。";
            case "MAIN_INTENT_ROUTER_FAILED", "GUIDE_GRAPH_NODE_FAILED", "GUIDE_GRAPH_FAILED" ->
                    "当前请求处理失败，请稍后重试。";
            default -> fallbackMessage == null || fallbackMessage.isBlank()
                    ? GRAPH_FAILURE_FALLBACK_ANSWER
                    : fallbackMessage;
        };
    }

    private boolean recoverableError(String errorCode) {
        return !"ORDER_WORKFLOW_NOT_DISPATCHED".equals(safeErrorCode(errorCode));
    }

}
