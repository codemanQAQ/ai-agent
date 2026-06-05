package com.bytedance.ai.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.bytedance.ai.graph.api.AgentStreamEvent;
import com.bytedance.ai.graph.api.GuideGraphIntent;
import com.bytedance.ai.graph.api.GuideNodeExecutionResult;
import com.bytedance.ai.graph.api.GuideNodeResult;
import com.bytedance.ai.graph.api.NodeRunStatus;
import com.bytedance.ai.graph.cartmanage.CartManageWorkflowNode;
import com.bytedance.ai.graph.cartmanage.CartManageWorkflowResult;
import com.bytedance.ai.graph.cartmanage.subgraph.CartManageSubgraphFactory;
import com.bytedance.ai.graph.cartmanage.ProductCandidate;
import com.bytedance.ai.graph.conversation.AgentConversationRepository;
import com.bytedance.ai.graph.conversation.ConversationMessage;
import com.bytedance.ai.graph.intent.MainIntent;
import com.bytedance.ai.graph.intent.MainIntentDecision;
import com.bytedance.ai.graph.intent.MainIntentRouterService;
import com.bytedance.ai.graph.input.MultimodalInputProcessingResult;
import com.bytedance.ai.graph.input.MultimodalInputProcessor;
import com.bytedance.ai.graph.ordermanage.OrderManageSubgraphFactory;
import com.bytedance.ai.graph.ordermanage.OrderManageStateKeys;
import com.bytedance.ai.graph.ordermanage.OrderManageStatus;
import com.bytedance.ai.graph.ordermanage.PendingOrderActionRepository;
import com.bytedance.ai.graph.productrecommend.ProductCandidateExclusion;
import com.bytedance.ai.graph.productrecommend.ProductCandidatePostProcessResult;
import com.bytedance.ai.graph.productrecommend.ProductCandidatePostProcessor;
import com.bytedance.ai.graph.productrecommend.ProductCard;
import com.bytedance.ai.graph.productrecommend.ProductCardMapper;
import com.bytedance.ai.graph.productrecommend.ProductMultiRecallService;
import com.bytedance.ai.graph.productrecommend.ProductRecommendationAnswerGenerator;
import com.bytedance.ai.graph.productrecommend.ProductRecallCandidate;
import com.bytedance.ai.graph.productrecommend.ProductRecallPlan;
import com.bytedance.ai.graph.productrecommend.ProductRecommendSubScene;
import com.bytedance.ai.graph.productrecommend.ProductRecommendStrategyPlanner;
import com.bytedance.ai.graph.productrecommend.SceneBundlePlan;
import com.bytedance.ai.graph.productrecommend.SceneBundlePlanner;
import com.bytedance.ai.graph.productrecommend.SceneBundleRole;
import com.bytedance.ai.graph.session.AgentSessionState;
import com.bytedance.ai.graph.session.AgentSessionStateRepository;
import com.bytedance.ai.graph.session.AgentSessionStateMerger;
import com.bytedance.ai.graph.session.CandidateSnapshot;
import com.bytedance.ai.graph.session.CurrentTurnMultimodalContext;
import com.bytedance.ai.graph.session.CurrentTurnMultimodalUnifier;
import com.bytedance.ai.graph.session.RecommendationState;
import com.bytedance.ai.graph.session.UnifiedQueryContext;
import com.bytedance.ai.graph.session.UnifiedQueryContextBuilder;
import com.bytedance.ai.shared.support.RagLogFields;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

@Component
public class GuideStateGraphFactory {

    private static final Logger log = LoggerFactory.getLogger(GuideStateGraphFactory.class);

    public static final String GRAPH_NAME = "ecommerce-guide-state-graph-v1";
    private static final String MAIN_INTENT_LLM_TIMEOUT = "MAIN_INTENT_LLM_TIMEOUT";
    private static final String MAIN_INTENT_ROUTER_FAILED = "MAIN_INTENT_ROUTER_FAILED";
    private static final String ORDER_WORKFLOW_NOT_DISPATCHED = "ORDER_WORKFLOW_NOT_DISPATCHED";
    private static final String ORDER_WORKFLOW_NOT_DISPATCHED_MESSAGE =
            "下单流程没有被正确执行，请检查主图 workflow 路由。";
    private static final String CLARIFY_MESSAGE =
            "我暂时没能识别你的操作，请换一种说法，例如‘查看购物车’、‘添加商品到购物车’或‘结算购物车’。";
    private static final String FAILURE_MESSAGE =
            "当前请求处理失败，请稍后重试，或换一种说法重新发送。";
    private static final String NO_BUSINESS_RESULT_MESSAGE =
            "当前没有可展示的业务结果，请换一种说法重新发送。";

    private final AgentConversationRepository conversationRepository;
    private final MainIntentRouterService mainIntentRouterService;
    private final CartManageWorkflowNode cartManageWorkflowNode;
    private final CartManageSubgraphFactory cartManageSubgraphFactory;
    private final OrderManageSubgraphFactory orderManageSubgraphFactory;
    private final PendingOrderActionRepository pendingOrderActionRepository;
    private final AgentSessionStateRepository agentSessionStateRepository;
    private final MultimodalInputProcessor multimodalInputProcessor;
    private final ProductMultiRecallService productMultiRecallService;
    private final ProductCandidatePostProcessor productCandidatePostProcessor;
    private final ProductCardMapper productCardMapper;
    private final ProductRecommendationAnswerGenerator productRecommendationAnswerGenerator;
    private final CurrentTurnMultimodalUnifier currentTurnMultimodalUnifier = new CurrentTurnMultimodalUnifier();
    private final AgentSessionStateMerger agentSessionStateMerger = new AgentSessionStateMerger();
    private final UnifiedQueryContextBuilder unifiedQueryContextBuilder = new UnifiedQueryContextBuilder();
    private final ProductRecommendStrategyPlanner productRecommendStrategyPlanner = new ProductRecommendStrategyPlanner();
    private final SceneBundlePlanner sceneBundlePlanner = new SceneBundlePlanner();

    public GuideStateGraphFactory(AgentConversationRepository conversationRepository) {
        this(conversationRepository, (MainIntentRouterService) null, (CartManageWorkflowNode) null, null, null, null,
                null, null, null, null, null, null);
    }

    public GuideStateGraphFactory(
            AgentConversationRepository conversationRepository,
            CartManageWorkflowNode cartManageWorkflowNode
    ) {
        this(conversationRepository, (MainIntentRouterService) null, cartManageWorkflowNode, null, null, null,
                null, null, null, null, null, null);
    }

    @Autowired
    public GuideStateGraphFactory(
            AgentConversationRepository conversationRepository,
            ObjectProvider<MainIntentRouterService> mainIntentRouterServiceProvider,
            ObjectProvider<CartManageWorkflowNode> cartManageWorkflowNodeProvider,
            ObjectProvider<CartManageSubgraphFactory> cartManageSubgraphFactoryProvider,
            OrderManageSubgraphFactory orderManageSubgraphFactory,
            ObjectProvider<PendingOrderActionRepository> pendingOrderActionRepositoryProvider,
            ObjectProvider<AgentSessionStateRepository> agentSessionStateRepositoryProvider,
            ObjectProvider<MultimodalInputProcessor> multimodalInputProcessorProvider,
            ObjectProvider<ProductMultiRecallService> productMultiRecallServiceProvider,
            ObjectProvider<ProductCandidatePostProcessor> productCandidatePostProcessorProvider,
            ObjectProvider<ProductCardMapper> productCardMapperProvider,
            ObjectProvider<ProductRecommendationAnswerGenerator> productRecommendationAnswerGeneratorProvider
    ) {
        this(
                conversationRepository,
                mainIntentRouterServiceProvider.getIfAvailable(),
                cartManageWorkflowNodeProvider.getIfAvailable(),
                cartManageSubgraphFactoryProvider.getIfAvailable(),
                orderManageSubgraphFactory,
                pendingOrderActionRepositoryProvider.getIfAvailable(),
                agentSessionStateRepositoryProvider.getIfAvailable(),
                multimodalInputProcessorProvider.getIfAvailable(),
                productMultiRecallServiceProvider.getIfAvailable(),
                productCandidatePostProcessorProvider.getIfAvailable(),
                productCardMapperProvider.getIfAvailable(),
                productRecommendationAnswerGeneratorProvider.getIfAvailable()
        );
    }

    GuideStateGraphFactory(
            AgentConversationRepository conversationRepository,
            MainIntentRouterService mainIntentRouterService,
            CartManageWorkflowNode cartManageWorkflowNode,
            CartManageSubgraphFactory cartManageSubgraphFactory,
            OrderManageSubgraphFactory orderManageSubgraphFactory,
            PendingOrderActionRepository pendingOrderActionRepository
    ) {
        this(conversationRepository, mainIntentRouterService, cartManageWorkflowNode, cartManageSubgraphFactory,
                orderManageSubgraphFactory, pendingOrderActionRepository, null, null, null, null, null, null);
    }

    GuideStateGraphFactory(
            AgentConversationRepository conversationRepository,
            MainIntentRouterService mainIntentRouterService,
            CartManageWorkflowNode cartManageWorkflowNode,
            CartManageSubgraphFactory cartManageSubgraphFactory,
            OrderManageSubgraphFactory orderManageSubgraphFactory,
            PendingOrderActionRepository pendingOrderActionRepository,
            ProductMultiRecallService productMultiRecallService,
            ProductCandidatePostProcessor productCandidatePostProcessor,
            ProductCardMapper productCardMapper
    ) {
        this(conversationRepository, mainIntentRouterService, cartManageWorkflowNode, cartManageSubgraphFactory,
                orderManageSubgraphFactory, pendingOrderActionRepository, productMultiRecallService,
                productCandidatePostProcessor, productCardMapper, null);
    }

    GuideStateGraphFactory(
            AgentConversationRepository conversationRepository,
            MainIntentRouterService mainIntentRouterService,
            CartManageWorkflowNode cartManageWorkflowNode,
            CartManageSubgraphFactory cartManageSubgraphFactory,
            OrderManageSubgraphFactory orderManageSubgraphFactory,
            PendingOrderActionRepository pendingOrderActionRepository,
            ProductMultiRecallService productMultiRecallService,
            ProductCandidatePostProcessor productCandidatePostProcessor,
            ProductCardMapper productCardMapper,
            ProductRecommendationAnswerGenerator productRecommendationAnswerGenerator
    ) {
        this(conversationRepository, mainIntentRouterService, cartManageWorkflowNode, cartManageSubgraphFactory,
                orderManageSubgraphFactory, pendingOrderActionRepository, null, null, productMultiRecallService,
                productCandidatePostProcessor, productCardMapper, productRecommendationAnswerGenerator);
    }

    GuideStateGraphFactory(
            AgentConversationRepository conversationRepository,
            MainIntentRouterService mainIntentRouterService,
            CartManageWorkflowNode cartManageWorkflowNode,
            CartManageSubgraphFactory cartManageSubgraphFactory,
            OrderManageSubgraphFactory orderManageSubgraphFactory,
            PendingOrderActionRepository pendingOrderActionRepository,
            AgentSessionStateRepository agentSessionStateRepository,
            MultimodalInputProcessor multimodalInputProcessor,
            ProductMultiRecallService productMultiRecallService,
            ProductCandidatePostProcessor productCandidatePostProcessor,
            ProductCardMapper productCardMapper,
            ProductRecommendationAnswerGenerator productRecommendationAnswerGenerator
    ) {
        this.conversationRepository = conversationRepository;
        this.mainIntentRouterService = mainIntentRouterService;
        this.cartManageWorkflowNode = cartManageWorkflowNode;
        this.cartManageSubgraphFactory = cartManageSubgraphFactory;
        this.orderManageSubgraphFactory = orderManageSubgraphFactory;
        this.pendingOrderActionRepository = pendingOrderActionRepository;
        this.agentSessionStateRepository = agentSessionStateRepository;
        this.multimodalInputProcessor = multimodalInputProcessor;
        this.productMultiRecallService = productMultiRecallService;
        this.productCandidatePostProcessor = productCandidatePostProcessor;
        this.productCardMapper = productCardMapper;
        this.productRecommendationAnswerGenerator = productRecommendationAnswerGenerator;
    }

    public CompiledGraph compile(Consumer<AgentStreamEvent> eventSink) {
        return compile(eventSink, Map.of());
    }

    CompiledGraph compile(Consumer<AgentStreamEvent> eventSink, Map<String, GuideGraphNodeAction> actionOverrides) {
        try {
            GuideGraphNodeTemplate nodeTemplate = new GuideGraphNodeTemplate(eventSink);
            StateGraph graph = new StateGraph(GRAPH_NAME, keyStrategyFactory());
            graph.addNode(GuideGraphNodeNames.CHECK_CONVERSATION,
                    AsyncNodeAction.node_async(state -> nodeTemplate.execute(
                            GuideGraphNodeNames.CHECK_CONVERSATION, state,
                            actionOverrides.getOrDefault(
                                    GuideGraphNodeNames.CHECK_CONVERSATION, this::checkConversation))));
            graph.addNode(GuideGraphNodeNames.LOAD_MEMORY,
                    AsyncNodeAction.node_async(state -> nodeTemplate.execute(
                            GuideGraphNodeNames.LOAD_MEMORY, state,
                            actionOverrides.getOrDefault(GuideGraphNodeNames.LOAD_MEMORY, this::loadMemory))));
            graph.addNode(GuideGraphNodeNames.INIT_CONVERSATION,
                    AsyncNodeAction.node_async(state -> nodeTemplate.execute(
                            GuideGraphNodeNames.INIT_CONVERSATION, state,
                            actionOverrides.getOrDefault(
                                    GuideGraphNodeNames.INIT_CONVERSATION, this::initConversation))));
            graph.addNode(GuideGraphNodeNames.LOAD_AGENT_SESSION_STATE,
                    AsyncNodeAction.node_async(state -> nodeTemplate.execute(
                            GuideGraphNodeNames.LOAD_AGENT_SESSION_STATE, state,
                            actionOverrides.getOrDefault(
                                    GuideGraphNodeNames.LOAD_AGENT_SESSION_STATE, this::loadAgentSessionState))));
            graph.addNode(GuideGraphNodeNames.CURRENT_TURN_MULTIMODAL_UNIFIER,
                    AsyncNodeAction.node_async(state -> nodeTemplate.execute(
                            GuideGraphNodeNames.CURRENT_TURN_MULTIMODAL_UNIFIER, state,
                            actionOverrides.getOrDefault(
                                    GuideGraphNodeNames.CURRENT_TURN_MULTIMODAL_UNIFIER,
                                    this::currentTurnMultimodalUnifier))));
            graph.addNode(GuideGraphNodeNames.SAVE_USER_MESSAGE,
                    AsyncNodeAction.node_async(state -> nodeTemplate.execute(
                            GuideGraphNodeNames.SAVE_USER_MESSAGE, state,
                            actionOverrides.getOrDefault(GuideGraphNodeNames.SAVE_USER_MESSAGE, this::saveUserMessage))));
            graph.addNode(GuideGraphNodeNames.MAIN_INTENT_ROUTER,
                    AsyncNodeAction.node_async(state -> nodeTemplate.execute(
                            GuideGraphNodeNames.MAIN_INTENT_ROUTER, state,
                            actionOverrides.getOrDefault(GuideGraphNodeNames.MAIN_INTENT_ROUTER, this::mainIntentRouter))));
            graph.addNode(GuideGraphNodeNames.AGENT_SESSION_STATE_MERGER,
                    AsyncNodeAction.node_async(state -> nodeTemplate.execute(
                            GuideGraphNodeNames.AGENT_SESSION_STATE_MERGER, state,
                            actionOverrides.getOrDefault(
                                    GuideGraphNodeNames.AGENT_SESSION_STATE_MERGER,
                                    this::agentSessionStateMerger))));
            Map<String, GuideGraphNodeAction> defaultWorkflowActions = defaultWorkflowActions();
            boolean cartSubgraphWired = cartManageSubgraphFactory != null
                    && !actionOverrides.containsKey(GuideGraphNodeNames.CART_MANAGE_WORKFLOW);
            boolean orderSubgraphWired = orderManageSubgraphFactory != null
                    && !actionOverrides.containsKey(GuideGraphNodeNames.ORDER_MANAGE_WORKFLOW);
            for (String workflowNode : new LinkedHashSet<>(GuideGraphWorkflows.targets().values())) {
                if (cartSubgraphWired && GuideGraphNodeNames.CART_MANAGE_WORKFLOW.equals(workflowNode)) {
                    // CART_MANAGE is implemented as a sub-state-graph rather than a single node
                    // action; the legacy single-node CartManageWorkflowNode is kept around only
                    // for tests that explicitly override this node via `actionOverrides`.
                    graph.addNode(workflowNode, cartManageSubgraphFactory.build());
                    continue;
                }
                if (orderSubgraphWired && GuideGraphNodeNames.ORDER_MANAGE_WORKFLOW.equals(workflowNode)) {
                    graph.addNode(workflowNode, AsyncNodeAction.node_async(state -> nodeTemplate.execute(
                            workflowNode, state, this::orderManageWorkflow)));
                    continue;
                }
                GuideGraphNodeAction defaultAction = defaultWorkflowActions.getOrDefault(
                        workflowNode, workflowNode(workflowNode));
                graph.addNode(workflowNode, AsyncNodeAction.node_async(state -> nodeTemplate.execute(
                        workflowNode, state,
                        actionOverrides.getOrDefault(workflowNode, defaultAction))));
            }
            graph.addNode(GuideGraphNodeNames.BUILD_ANSWER_CONTEXT,
                    AsyncNodeAction.node_async(state -> nodeTemplate.execute(
                            GuideGraphNodeNames.BUILD_ANSWER_CONTEXT, state,
                            actionOverrides.getOrDefault(GuideGraphNodeNames.BUILD_ANSWER_CONTEXT, this::buildAnswerContext))));
            graph.addNode(GuideGraphNodeNames.TERMINAL_STATE_WRITEBACK,
                    AsyncNodeAction.node_async(state -> nodeTemplate.execute(
                            GuideGraphNodeNames.TERMINAL_STATE_WRITEBACK, state,
                            actionOverrides.getOrDefault(GuideGraphNodeNames.TERMINAL_STATE_WRITEBACK,
                                    this::terminalStateWriteback))));

            graph.addEdge(StateGraph.START, GuideGraphNodeNames.CHECK_CONVERSATION);
            graph.addConditionalEdges(
                    GuideGraphNodeNames.CHECK_CONVERSATION,
                    AsyncEdgeAction.edge_async(this::routeByConversationExists),
                    conversationExistenceMappings()
            );
            graph.addEdge(GuideGraphNodeNames.LOAD_MEMORY, GuideGraphNodeNames.LOAD_AGENT_SESSION_STATE);
            graph.addEdge(GuideGraphNodeNames.INIT_CONVERSATION, GuideGraphNodeNames.LOAD_AGENT_SESSION_STATE);
            // 三条互相独立的链路从 load_state 并行 fan-out，在 merger 汇合：
            //   1) 图像处理链路 current_turn_multimodal_unifier（caption+embedding）
            //   2) 文本意图链路 main_intent_router（只依赖 message + imageRef，不等 caption）
            //   3) 用户消息落库 save_user_message（只依赖 message）
            // 三条等长（各 1 跳到 merger），同一 superstep 完成；merger 需要 multimodal 上下文 + 意图结果，
            // 作为唯一汇合点只执行一次。图像处理(~3.5s)与意图(~4.9s)由此重叠，省去串行等待。
            graph.addEdge(GuideGraphNodeNames.LOAD_AGENT_SESSION_STATE, GuideGraphNodeNames.CURRENT_TURN_MULTIMODAL_UNIFIER);
            graph.addEdge(GuideGraphNodeNames.LOAD_AGENT_SESSION_STATE, GuideGraphNodeNames.MAIN_INTENT_ROUTER);
            graph.addEdge(GuideGraphNodeNames.LOAD_AGENT_SESSION_STATE, GuideGraphNodeNames.SAVE_USER_MESSAGE);
            graph.addEdge(GuideGraphNodeNames.CURRENT_TURN_MULTIMODAL_UNIFIER, GuideGraphNodeNames.AGENT_SESSION_STATE_MERGER);
            graph.addEdge(GuideGraphNodeNames.MAIN_INTENT_ROUTER, GuideGraphNodeNames.AGENT_SESSION_STATE_MERGER);
            graph.addEdge(GuideGraphNodeNames.SAVE_USER_MESSAGE, GuideGraphNodeNames.AGENT_SESSION_STATE_MERGER);
            graph.addConditionalEdges(
                    GuideGraphNodeNames.AGENT_SESSION_STATE_MERGER,
                    AsyncEdgeAction.edge_async(this::routeByMockIntent),
                    workflowMappings()
            );
            for (String workflowNode : new LinkedHashSet<>(GuideGraphWorkflows.targets().values())) {
                graph.addEdge(workflowNode, GuideGraphNodeNames.BUILD_ANSWER_CONTEXT);
            }
            graph.addEdge(GuideGraphNodeNames.BUILD_ANSWER_CONTEXT, GuideGraphNodeNames.TERMINAL_STATE_WRITEBACK);
            graph.addEdge(GuideGraphNodeNames.TERMINAL_STATE_WRITEBACK, StateGraph.END);
            return graph.compile();
        } catch (Exception exception) {
            throw new IllegalStateException("Guide StateGraph compile failed", exception);
        }
    }

    private KeyStrategyFactory keyStrategyFactory() {
        return new KeyStrategyFactoryBuilder()
                .defaultStrategy(new ReplaceStrategy())
                .addStrategy(GuideGraphStateKeys.LAST_NODE_RESULT, new ReplaceStrategy())
                .addStrategy(GuideGraphStateKeys.NODE_RESULTS, new ReplaceStrategy())
                .addStrategy(GuideGraphStateKeys.ANSWER_CONTEXT, new ReplaceStrategy())
                .addStrategy(GuideGraphStateKeys.TERMINAL_STATE_WRITEBACK, new ReplaceStrategy())
                .addStrategy(GuideGraphStateKeys.INTENT_SLOTS, new ReplaceStrategy())
                .addStrategy(GuideGraphStateKeys.MISSING_SLOTS, new ReplaceStrategy())
                .addStrategy(GuideGraphStateKeys.AGENT_SESSION_STATE, new ReplaceStrategy())
                .addStrategy(GuideGraphStateKeys.CURRENT_TURN_MULTIMODAL_CONTEXT, new ReplaceStrategy())
                .addStrategy(GuideGraphStateKeys.IMAGE_PROCESSING_RESULT, new ReplaceStrategy())
                .build();
    }

    private GuideNodeExecutionResult checkConversation(OverAllState state) {
        String userId = requiredString(state, GuideGraphStateKeys.USER_ID);
        String conversationId = requiredString(state, GuideGraphStateKeys.CONVERSATION_ID);
        boolean exists = conversationRepository.existsConversation(userId, conversationId);
        return GuideNodeExecutionResult.withStateUpdates(
                Map.of(GuideGraphStateKeys.CONVERSATION_EXISTS, exists),
                Map.of(GuideGraphStateKeys.CONVERSATION_EXISTS, exists)
        );
    }

    private GuideNodeExecutionResult loadMemory(OverAllState state) {
        String userId = requiredString(state, GuideGraphStateKeys.USER_ID);
        String conversationId = requiredString(state, GuideGraphStateKeys.CONVERSATION_ID);
        java.util.List<ConversationMessage> recentMessages =
                conversationRepository.loadRecentMessages(userId, conversationId, 20);
        return GuideNodeExecutionResult.withStateUpdates(
                Map.of(
                        GuideGraphStateKeys.RECENT_MESSAGES, recentMessages,
                        GuideGraphStateKeys.MESSAGE_COUNT, recentMessages.size()
                ),
                Map.of(GuideGraphStateKeys.MESSAGE_COUNT, recentMessages.size())
        );
    }

    private GuideNodeExecutionResult initConversation(OverAllState state) {
        String userId = requiredString(state, GuideGraphStateKeys.USER_ID);
        String conversationId = requiredString(state, GuideGraphStateKeys.CONVERSATION_ID);
        Long internalConversationId = conversationRepository.initConversation(userId, conversationId);
        return GuideNodeExecutionResult.withStateUpdates(
                Map.of(GuideGraphStateKeys.CONVERSATION_INTERNAL_ID, internalConversationId),
                Map.of(GuideGraphStateKeys.CONVERSATION_INTERNAL_ID, internalConversationId)
        );
    }

    private GuideNodeExecutionResult loadAgentSessionState(OverAllState state) {
        String userId = requiredString(state, GuideGraphStateKeys.USER_ID);
        String conversationId = requiredString(state, GuideGraphStateKeys.CONVERSATION_ID);
        List<ConversationMessage> recentMessages = state.value(
                GuideGraphStateKeys.RECENT_MESSAGES,
                List.<ConversationMessage>of()
        );
        Optional<AgentSessionState> storedSessionState = agentSessionStateRepository == null
                ? Optional.empty()
                : agentSessionStateRepository.find(userId, conversationId);
        AgentSessionState sessionState = storedSessionState
                .map(stored -> stored.withRecentMessages(recentMessages))
                .orElseGet(() -> AgentSessionState.fromRecentMessages(userId, conversationId, recentMessages));
        Map<String, Object> updates = Map.of(GuideGraphStateKeys.AGENT_SESSION_STATE, sessionState);
        return GuideNodeExecutionResult.withStateUpdates(
                updates,
                Map.of(
                        "schemaVersion", sessionState.schemaVersion(),
                        GuideGraphStateKeys.MESSAGE_COUNT, sessionState.recentMessages().size(),
                        "stateStoreHit", storedSessionState.isPresent()
                )
        );
    }

    private GuideNodeExecutionResult currentTurnMultimodalUnifier(OverAllState state) {
        AgentSessionState sessionState = state.value(GuideGraphStateKeys.AGENT_SESSION_STATE, AgentSessionState.class)
                .orElse(null);
        MultimodalInputProcessingResult imageProcessingResult = processImageInputIfNeeded(state);
        CurrentTurnMultimodalContext context = currentTurnMultimodalUnifier.unify(
                sessionState,
                state.value(GuideGraphStateKeys.MESSAGE, ""),
                state.value(GuideGraphStateKeys.ORIGINAL_MESSAGE, state.value(GuideGraphStateKeys.MESSAGE, "")),
                state.value(GuideGraphStateKeys.INPUT_MODALITIES, List.<String>of()),
                state.value(GuideGraphStateKeys.IMAGE_REF).map(Object::toString).orElse(null),
                firstText(state.value(GuideGraphStateKeys.IMAGE_CAPTION).map(Object::toString).orElse(null),
                        imageProcessingResult == null ? null : imageProcessingResult.imageCaption()),
                firstText(state.value(GuideGraphStateKeys.IMAGE_EMBEDDING_REF).map(Object::toString).orElse(null),
                        imageProcessingResult == null ? null : imageProcessingResult.imageEmbeddingRef())
        );
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(GuideGraphStateKeys.CURRENT_TURN_MULTIMODAL_CONTEXT, context);
        if (imageProcessingResult != null) {
            putIfPresent(updates, GuideGraphStateKeys.IMAGE_CAPTION, imageProcessingResult.imageCaption());
            putIfPresent(updates, GuideGraphStateKeys.IMAGE_EMBEDDING_REF, imageProcessingResult.imageEmbeddingRef());
            updates.put(GuideGraphStateKeys.IMAGE_PROCESSING_RESULT, imageProcessingResult.metadata());
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("inputModalities", context.inputModalities());
        metadata.put("hasImage", context.hasImage());
        metadata.put("imageFromHistory", context.imageFromHistory());
        metadata.put("imageProcessedOnline", imageProcessingResult != null);
        return GuideNodeExecutionResult.withStateUpdates(
                updates,
                metadata
        );
    }

    private MultimodalInputProcessingResult processImageInputIfNeeded(OverAllState state) {
        if (multimodalInputProcessor == null) {
            return null;
        }
        String imageRef = state.value(GuideGraphStateKeys.IMAGE_REF).map(Object::toString).orElse(null);
        if (imageRef == null || imageRef.isBlank()) {
            return null;
        }
        boolean generateCaption = state.value(GuideGraphStateKeys.IMAGE_CAPTION)
                .map(Object::toString)
                .map(String::isBlank)
                .orElse(true);
        boolean generateEmbedding = state.value(GuideGraphStateKeys.IMAGE_EMBEDDING_REF)
                .map(Object::toString)
                .map(String::isBlank)
                .orElse(true);
        if (!generateCaption && !generateEmbedding) {
            return null;
        }
        return multimodalInputProcessor.processImage(imageRef, generateCaption, generateEmbedding).orElse(null);
    }

    private String firstText(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback == null || fallback.isBlank() ? null : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private GuideNodeExecutionResult agentSessionStateMerger(OverAllState state) {
        AgentSessionState base = state.value(GuideGraphStateKeys.AGENT_SESSION_STATE, AgentSessionState.class)
                .orElse(null);
        CurrentTurnMultimodalContext multimodalContext = state
                .value(GuideGraphStateKeys.CURRENT_TURN_MULTIMODAL_CONTEXT, CurrentTurnMultimodalContext.class)
                .orElse(null);
        AgentSessionState merged = agentSessionStateMerger.merge(
                base,
                state.value(GuideGraphStateKeys.MAIN_INTENT).map(Object::toString)
                        .orElseGet(() -> state.value(GuideGraphStateKeys.INTENT, "")),
                state.value(GuideGraphStateKeys.INTENT_SLOTS, Map.<String, Object>of()),
                state.value(GuideGraphStateKeys.MISSING_SLOTS, List.<String>of()),
                state.value(GuideGraphStateKeys.CLARIFY_QUESTION).map(Object::toString).orElse(null),
                multimodalContext
        );
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "activeIntent", merged.recommendationState().activeIntent());
        metadata.put("inputModalities", multimodalContext == null ? List.of() : multimodalContext.inputModalities());
        metadata.put("missingSlotCount", merged.recommendationState().missingSlots().size());
        return GuideNodeExecutionResult.withStateUpdates(
                Map.of(GuideGraphStateKeys.AGENT_SESSION_STATE, merged),
                metadata
        );
    }

    private GuideNodeExecutionResult saveUserMessage(OverAllState state) {
        String userId = requiredString(state, GuideGraphStateKeys.USER_ID);
        String conversationId = requiredString(state, GuideGraphStateKeys.CONVERSATION_ID);
        String turnId = requiredString(state, GuideGraphStateKeys.RUN_ID);
        String correlationId = requiredString(state, GuideGraphStateKeys.CORRELATION_ID);
        String content = requiredString(state, GuideGraphStateKeys.MESSAGE);
        ConversationMessage message = conversationRepository.saveUserMessage(
                userId,
                conversationId,
                turnId,
                correlationId,
                content
        );
        conversationRepository.createOrUpdateTurn(
                userId,
                conversationId,
                turnId,
                state.value(GuideGraphStateKeys.REQUEST_ID).map(Object::toString).orElse(null),
                "RUNNING",
                null,
                null
        );
        return GuideNodeExecutionResult.withStateUpdates(
                Map.of("userMessageId", message.messageId()),
                Map.of("userMessageId", message.messageId())
        );
    }

    private GuideNodeExecutionResult mainIntentRouter(OverAllState state) {
        MainIntentRouteResult routeResult = routeMainIntentSafely(state);
        MainIntentDecision decision = routeResult.decision();
        GuideGraphIntent intent = toGuideIntent(decision.intent());
        String targetWorkflow = decision.targetWorkflow();
        conversationRepository.createOrUpdateTurn(
                requiredString(state, GuideGraphStateKeys.USER_ID),
                requiredString(state, GuideGraphStateKeys.CONVERSATION_ID),
                requiredString(state, GuideGraphStateKeys.RUN_ID),
                state.value(GuideGraphStateKeys.REQUEST_ID).map(Object::toString).orElse(null),
                "RUNNING",
                intent.name(),
                targetWorkflow
        );
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(GuideGraphStateKeys.INTENT, intent.name());
        updates.put(GuideGraphStateKeys.MAIN_INTENT, decision.intent().name());
        putIfPresent(updates, GuideGraphStateKeys.SUB_INTENT, decision.subIntent());
        updates.put(GuideGraphStateKeys.INTENT_CONFIDENCE, decision.confidence());
        updates.put(GuideGraphStateKeys.NEED_CLARIFY, decision.needClarify());
        updates.put(GuideGraphStateKeys.WRITE_ACTION, decision.writeAction());
        updates.put(GuideGraphStateKeys.TARGET_WORKFLOW, targetWorkflow);
        updates.put(GuideGraphStateKeys.INTENT_REASON, decision.reason());
        putIfPresent(updates, GuideGraphStateKeys.CLARIFY_QUESTION, decision.clarifyQuestion());
        if (decision.needClarify() && routeResult.errorCode() == null) {
            putIfPresent(updates, GuideGraphStateKeys.NODE_MESSAGE, decision.clarifyQuestion());
        }
        updates.put(GuideGraphStateKeys.INTENT_SLOTS, decision.slots());
        updates.put(GuideGraphStateKeys.MISSING_SLOTS, decision.missingSlots());
        putIfPresent(updates, GuideGraphStateKeys.ERROR_CODE, routeResult.errorCode());
        putIfPresent(updates, GuideGraphStateKeys.ERROR_MESSAGE, routeResult.errorMessage());
        putIfPresent(updates, GuideGraphStateKeys.NODE_MESSAGE, routeResult.nodeMessage());
        putIfPresent(updates, GuideGraphStateKeys.ROUTE_SOURCE, routeResult.routeSource());
        if (routeResult.llmCalled() != null) {
            updates.put(GuideGraphStateKeys.LLM_CALLED, routeResult.llmCalled());
        }
        boolean failed = routeResult.errorCode() != null;
        log.atInfo()
                .addKeyValue(RagLogFields.EVENT_NAME, "main_intent.graph_state_written")
                .addKeyValue(RagLogFields.EVENT_OUTCOME, failed ? RagLogFields.OUTCOME_FAILURE : RagLogFields.OUTCOME_SUCCESS)
                .addKeyValue(RagLogFields.RAG_CORRELATION_ID, state.value(GuideGraphStateKeys.CORRELATION_ID, ""))
                .addKeyValue("agent.turn_id", state.value(GuideGraphStateKeys.RUN_ID, ""))
                .addKeyValue("agent.request_id", state.value(GuideGraphStateKeys.REQUEST_ID, ""))
                .addKeyValue("main_intent.intent", decision.intent().name())
                .addKeyValue("main_intent.sub_intent", decision.subIntent())
                .addKeyValue("main_intent.confidence", decision.confidence())
                .addKeyValue("main_intent.need_clarify", decision.needClarify())
                .addKeyValue("main_intent.write_action", decision.writeAction())
                .addKeyValue("main_intent.target_workflow", targetWorkflow)
                .addKeyValue("main_intent.reason", decision.reason())
                .log("main intent graph state written: turnId={}, requestId={}, intent={}, subIntent={}, confidence={}, needClarify={}, writeAction={}, targetWorkflow={}, reason={}",
                        state.value(GuideGraphStateKeys.RUN_ID, ""),
                        state.value(GuideGraphStateKeys.REQUEST_ID, ""),
                        decision.intent(),
                        decision.subIntent(),
                        decision.confidence(),
                        decision.needClarify(),
                        decision.writeAction(),
                        targetWorkflow,
                        decision.reason());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(GuideGraphStateKeys.MAIN_INTENT, decision.intent().name());
        putIfPresent(metadata, GuideGraphStateKeys.SUB_INTENT, decision.subIntent());
        metadata.put(GuideGraphStateKeys.TARGET_WORKFLOW, targetWorkflow);
        metadata.put(GuideGraphStateKeys.WRITE_ACTION, decision.writeAction());
        metadata.put(GuideGraphStateKeys.ROUTE_SOURCE, routeResult.routeSource());
        putIfPresent(metadata, GuideGraphStateKeys.CLARIFY_QUESTION, decision.clarifyQuestion());
        putIfPresent(metadata, GuideGraphStateKeys.ERROR_CODE, routeResult.errorCode());
        putIfPresent(metadata, GuideGraphStateKeys.ERROR_MESSAGE, routeResult.errorMessage());
        return new GuideNodeExecutionResult(
                failed ? NodeRunStatus.FAILED : null,
                intent,
                null,
                updates,
                metadata
        );
    }

    private MainIntentRouteResult routeMainIntentSafely(OverAllState state) {
        Optional<GuideGraphIntent> initialIntent =
                GuideGraphStateValues.intent(state, GuideGraphStateKeys.INITIAL_INTENT);
        if (initialIntent.isPresent()) {
            return new MainIntentRouteResult(decisionFromInitialIntent(initialIntent.get()), null, null, null, "INITIAL", false);
        }
        try {
            MainIntentDecision decision = routeWithIntentService(state);
            return new MainIntentRouteResult(decision, null, null, null, routeSource(decision), true);
        } catch (RuntimeException exception) {
            String errorCode = mainIntentErrorCode(exception);
            log.atWarn()
                    .addKeyValue(RagLogFields.EVENT_NAME, "main_intent.router_fallback")
                    .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_FAILURE)
                    .addKeyValue(RagLogFields.RAG_CORRELATION_ID, state.value(GuideGraphStateKeys.CORRELATION_ID, ""))
                    .addKeyValue("agent.turn_id", state.value(GuideGraphStateKeys.RUN_ID, ""))
                    .addKeyValue("main_intent.error_code", errorCode)
                    .setCause(exception)
                    .log("main intent router failed; marking turn as failed");
            return new MainIntentRouteResult(
                    MainIntentDecision.clarify("main intent router fallback"),
                    errorCode,
                    safeMessage(exception),
                    publicFailureMessage(errorCode),
                    "FALLBACK",
                    true
            );
        }
    }

    private MainIntentDecision decisionFromInitialIntent(GuideGraphIntent intent) {
        MainIntent mainIntent = toMainIntent(intent);
        return new MainIntentDecision(
                mainIntent,
                mainIntent == MainIntent.CLARIFY || mainIntent == MainIntent.UNKNOWN ? 0.0d : 1.0d,
                mainIntent == MainIntent.CLARIFY || mainIntent == MainIntent.UNKNOWN,
                isWriteAction(mainIntent),
                GuideGraphWorkflows.targetFor(mainIntent),
                mainIntent.name(),
                "initial intent override",
                null,
                Map.of(),
                List.of()
        );
    }

    private String routeSource(MainIntentDecision decision) {
        if (decision == null) {
            return "DEFAULT";
        }
        if ("deterministic order_manage pre-router".equals(decision.reason())) {
            return "RULE";
        }
        if ("intent router service unavailable".equals(decision.reason())) {
            return "DEFAULT";
        }
        return mainIntentRouterService == null ? "DEFAULT" : "LLM";
    }

    private MainIntentDecision routeWithIntentService(OverAllState state) {
        MainIntentDecision deterministicOrderDecision = deterministicOrderRoute(state);
        if (deterministicOrderDecision != null) {
            return deterministicOrderDecision;
        }
        if (mainIntentRouterService == null) {
            log.atWarn()
                    .addKeyValue(RagLogFields.EVENT_NAME, "main_intent.router_service_unavailable")
                    .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_FAILURE)
                    .addKeyValue(RagLogFields.RAG_CORRELATION_ID, state.value(GuideGraphStateKeys.CORRELATION_ID, ""))
                    .addKeyValue("agent.turn_id", state.value(GuideGraphStateKeys.RUN_ID, ""))
                    .log("main intent router service unavailable; falling back to clarify");
            return MainIntentDecision.clarify("intent router service unavailable");
        }
        // 本轮是否带图片：直接看请求里的 imageRef（请求即可得知，不依赖图像处理结果），
        // 这样意图路由可与图像处理链路并行，且能据此路由到 PHOTO_SEARCH（拍照找货）。
        String userMessage = requiredString(state, GuideGraphStateKeys.MESSAGE);
        boolean hasImage = !state.value(GuideGraphStateKeys.IMAGE_REF, "").isBlank();
        String routerMessage = hasImage
                ? "[本轮用户上传了一张商品图片。若用户想找同款/相似/这个商品，应判定为 PHOTO_SEARCH 拍照找货意图。]\n" + userMessage
                : userMessage;
        return mainIntentRouterService.route(routerMessage, conversationMemory(state));
    }

    private MainIntentDecision deterministicOrderRoute(OverAllState state) {
        String message = requiredString(state, GuideGraphStateKeys.MESSAGE);
        String normalized = message.trim().toLowerCase();
        if (normalized.contains("查看") && normalized.contains("购物车")) {
            return null;
        }
        String userId = requiredString(state, GuideGraphStateKeys.USER_ID);
        String conversationId = requiredString(state, GuideGraphStateKeys.CONVERSATION_ID);
        String activePendingStatus = "";
        if (pendingOrderActionRepository != null) {
            activePendingStatus = pendingOrderActionRepository
                    .findActiveByUserIdAndConversationId(userId, conversationId)
                    .map(record -> record.status().name())
                    .orElse("");
        }
        boolean hasActivePending = !activePendingStatus.isBlank();
        boolean checkout = looksLikeOrderCheckout(normalized);
        boolean pendingFollowUp = hasActivePending
                && (looksLikeOrderConfirm(normalized) || looksLikeOrderCancel(normalized) || looksLikeAddress(normalized));
        if (!checkout && !pendingFollowUp) {
            return null;
        }
        // 按"待确认订单状态 + 措辞"区分子意图，否则全判 CREATE_ORDER 会让"确认/补地址"也走结算分支、
        // 重新建空 pending 并反复要地址，订单永远建不出来。
        MainIntent orderIntent;
        if (hasActivePending && looksLikeOrderCancel(normalized)) {
            orderIntent = MainIntent.CANCEL_ORDER;
        } else if (hasActivePending && looksLikeOrderConfirm(normalized)) {
            orderIntent = MainIntent.CONFIRM_ORDER;
        } else if (checkout) {
            orderIntent = MainIntent.CREATE_ORDER;
        } else {
            // 有待确认订单 + 像地址：交给 order 子图按状态判定为补充地址（PROVIDE_ADDRESS）。
            orderIntent = MainIntent.ORDER_MANAGE;
        }
        log.atInfo()
                .addKeyValue("routeSource", "RULE")
                .addKeyValue("selectedWorkflow", GuideGraphNodeNames.ORDER_MANAGE_WORKFLOW)
                .addKeyValue("activePendingOrderStatus", activePendingStatus)
                .addKeyValue("resolvedOrderIntent", orderIntent.name())
                .addKeyValue("messagePreview", message.length() > 40 ? message.substring(0, 40) : message)
                .log("main deterministic pre-router selected order_manage_workflow");
        return new MainIntentDecision(
                orderIntent,
                1.0d,
                false,
                true,
                GuideGraphNodeNames.ORDER_MANAGE_WORKFLOW,
                orderIntent.name(),
                "deterministic order_manage pre-router",
                null,
                Map.of(),
                List.of()
        );
    }

    private boolean looksLikeOrderCheckout(String normalized) {
        return normalized.contains("结算购物车")
                || normalized.contains("我要下单")
                || normalized.contains("帮我下单")
                || normalized.contains("提交订单")
                || normalized.contains("购买购物车")
                || normalized.contains("买这些")
                || normalized.equals("checkout");
    }

    private boolean looksLikeOrderConfirm(String normalized) {
        return normalized.equals("确认")
                || normalized.equals("可以")
                || normalized.equals("没问题")
                || normalized.equals("就这样")
                || normalized.contains("确认下单")
                || normalized.contains("确认订单")
                || normalized.contains("提交订单");
    }

    private boolean looksLikeOrderCancel(String normalized) {
        return normalized.contains("取消") || normalized.contains("先不买") || normalized.contains("不要了");
    }

    private boolean looksLikeAddress(String normalized) {
        return normalized.contains("地址")
                || normalized.contains("寄到")
                || normalized.contains("送到")
                || normalized.contains("收货")
                || normalized.matches(".*\\d{8,}.*")
                || normalized.length() >= 12;
    }

    private String conversationMemory(OverAllState state) {
        List<ConversationMessage> recentMessages = state.value(
                GuideGraphStateKeys.RECENT_MESSAGES,
                List.<ConversationMessage>of()
        );
        if (recentMessages.isEmpty()) {
            return "";
        }
        // 只喂用户历史话术、且仅最近几条：助手的长答复（如三亚组合清单）会强烈锚定模型，
        // 让后续不相关的新请求也被误判为延续上一场景；意图分类只需用户自己的历史措辞。
        StringBuilder builder = new StringBuilder();
        int kept = 0;
        int maxUserTurns = 4;
        List<ConversationMessage> tail = recentMessages.size() > maxUserTurns * 3
                ? recentMessages.subList(recentMessages.size() - maxUserTurns * 3, recentMessages.size())
                : recentMessages;
        for (ConversationMessage message : tail) {
            if (message.role() == null || !"user".equalsIgnoreCase(message.role())) {
                continue;
            }
            String content = message.content() == null ? "" : message.content().strip();
            if (content.isEmpty()) {
                continue;
            }
            builder.append("user: ").append(content).append('\n');
            kept++;
        }
        // 只保留最近 maxUserTurns 条用户消息
        if (kept > maxUserTurns) {
            String[] lines = builder.toString().strip().split("\n");
            StringBuilder trimmed = new StringBuilder();
            for (int i = Math.max(0, lines.length - maxUserTurns); i < lines.length; i++) {
                trimmed.append(lines[i]).append('\n');
            }
            return trimmed.toString().trim();
        }
        return builder.toString().trim();
    }

    private GuideGraphIntent toGuideIntent(MainIntent intent) {
        if (intent == null) {
            return GuideGraphIntent.OTHER;
        }
        try {
            return GuideGraphIntent.valueOf(intent.name());
        } catch (IllegalArgumentException ignored) {
            return GuideGraphIntent.UNKNOWN;
        }
    }

    private MainIntent toMainIntent(GuideGraphIntent intent) {
        if (intent == null) {
            return MainIntent.OTHER;
        }
        try {
            return MainIntent.valueOf(intent.name());
        } catch (IllegalArgumentException ignored) {
            return MainIntent.UNKNOWN;
        }
    }

    private boolean isWriteAction(MainIntent intent) {
        return switch (intent) {
            case ADD_TO_CART, REMOVE_FROM_CART, UPDATE_CART_ITEM, CART_MANAGE,
                 CREATE_ORDER, CONFIRM_ORDER, CANCEL_ORDER -> true;
            default -> false;
        };
    }

    /**
     * Real (non-placeholder) actions for specific workflow nodes. Keys not present in this map
     * fall back to {@link #workflowNode(String)}'s mock implementation.
     */
    private Map<String, GuideGraphNodeAction> defaultWorkflowActions() {
        Map<String, GuideGraphNodeAction> actions = new LinkedHashMap<>();
        if (cartManageWorkflowNode != null) {
            actions.put(GuideGraphNodeNames.CART_MANAGE_WORKFLOW, cartManageWorkflowNode::execute);
        }
        actions.put(GuideGraphNodeNames.PRODUCT_RECOMMEND_WORKFLOW, this::productRecommendWorkflow);
        actions.put(GuideGraphNodeNames.CLARIFY_WORKFLOW, this::clarifyWorkflow);
        return actions;
    }

    private GuideNodeExecutionResult productRecommendWorkflow(OverAllState state) {
        AgentSessionState sessionState = state.value(GuideGraphStateKeys.AGENT_SESSION_STATE, AgentSessionState.class)
                .orElse(null);
        if (sessionState == null) {
            sessionState = new AgentSessionState(
                    "1.0",
                    state.value(GuideGraphStateKeys.USER_ID, ""),
                    state.value(GuideGraphStateKeys.CONVERSATION_ID, ""),
                    null,
                    List.of(),
                    null,
                    null,
                    null,
                    null
            );
        }
        UnifiedQueryContext queryContext = unifiedQueryContextBuilder.build(sessionState);
        GuideGraphIntent intent = GuideGraphIntent.parse(state.value(GuideGraphStateKeys.MAIN_INTENT, ""))
                .orElse(GuideGraphIntent.FUZZY_RECOMMEND);
        GuideGraphIntent subIntent = GuideGraphIntent.parse(state.value(GuideGraphStateKeys.SUB_INTENT, ""))
                .orElse(intent);
        ProductRecommendSubScene subScene = ProductRecommendSubScene.from(subIntent);
        ProductRecallPlan recallPlan = productRecommendStrategyPlanner.plan(subScene);

        Map<String, Object> workflowResult = new LinkedHashMap<>();
        workflowResult.put("intent", intent.name());
        workflowResult.put("subIntent", subIntent.name());
        workflowResult.put("workflow", GuideGraphNodeNames.PRODUCT_RECOMMEND_WORKFLOW);
        workflowResult.put("subScene", subScene.name());
        workflowResult.put("strategy", subScene.strategyName());
        workflowResult.put("recallPlan", recallPlanMap(recallPlan));
        workflowResult.put("executionChecks", recommendationExecutionChecks(subScene, queryContext));
        workflowResult.put("message", "Product recommendation workflow routed to " + subScene.strategyName() + ".");
        workflowResult.put(GuideGraphStateKeys.UNIFIED_QUERY_CONTEXT, queryContext);

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(GuideGraphStateKeys.UNIFIED_QUERY_CONTEXT, queryContext);

        AgentSessionState updatedSessionState = sessionState;
        if (productRecommendationEnabled()) {
            List<ProductRecallCandidate> recalledCandidates;
            ProductCandidatePostProcessResult postProcessResult;
            if (subScene == ProductRecommendSubScene.SCENE_BUNDLE_RECOMMEND) {
                SceneBundlePlan bundlePlan = sceneBundlePlanner.plan(queryContext, bundleRolesFromState(state));
                List<Map<String, Object>> roleResults = new ArrayList<>();
                List<ProductRecallCandidate> allRecalledCandidates = new ArrayList<>();
                List<ProductRecallCandidate> roleFinalCandidates = new ArrayList<>();
                List<ProductCandidateExclusion> roleExclusions = new ArrayList<>();
                for (SceneBundleRole role : bundlePlan.roles()) {
                    UnifiedQueryContext roleContext = withSceneBundleRole(queryContext, bundlePlan, role);
                    List<ProductRecallCandidate> roleRecalled = productMultiRecallService.recall(roleContext, recallPlan);
                    ProductCandidatePostProcessResult roleResult = productCandidatePostProcessor.process(
                            roleRecalled,
                            roleContext,
                            recallPlan,
                            2
                    );
                    List<ProductCard> roleCards = productCardMapper.toCards(roleResult.candidates());
                    roleResults.add(sceneBundleRoleMap(role, roleCards, roleRecalled, roleResult));
                    allRecalledCandidates.addAll(roleRecalled);
                    roleFinalCandidates.addAll(roleResult.candidates());
                    roleExclusions.addAll(roleResult.exclusions());
                }
                recalledCandidates = List.copyOf(allRecalledCandidates);
                postProcessResult = productCandidatePostProcessor.process(
                        roleFinalCandidates,
                        queryContext,
                        recallPlan,
                        recallPlan.outputLimit()
                );
                workflowResult.put("bundle", sceneBundleMap(bundlePlan, roleResults));
                workflowResult.put("roleExclusions", roleExclusions.stream().map(this::exclusionMap).toList());
            } else {
                recalledCandidates = productMultiRecallService.recall(queryContext, recallPlan);
                postProcessResult = productCandidatePostProcessor.process(
                        recalledCandidates,
                        queryContext,
                        recallPlan,
                        recallPlan.outputLimit()
                );
            }
            List<ProductCard> productCards = productCardMapper.toCards(postProcessResult.candidates());
            CandidateSnapshot candidateSnapshot = postProcessResult.candidateSnapshot();

            workflowResult.put("products", productCards.stream().map(this::productCardMap).toList());
            if (subScene == ProductRecommendSubScene.PRODUCT_COMPARE) {
                workflowResult.put("comparison", comparisonMap(productCards, queryContext));
            }
            if (subScene == ProductRecommendSubScene.NEGATIVE_CONSTRAINT) {
                workflowResult.put("negativeSummary", negativeSummary(postProcessResult, queryContext));
            }
            workflowResult.put("candidateSnapshot", candidateSnapshotMap(candidateSnapshot));
            workflowResult.put("exclusions", postProcessResult.exclusions().stream().map(this::exclusionMap).toList());
            workflowResult.put("recallSummary", recallSummary(recalledCandidates, postProcessResult));
            workflowResult.put("message", productCards.isEmpty()
                    ? "暂时没有找到匹配的商品候选。"
                    : subScene == ProductRecommendSubScene.SCENE_BUNDLE_RECOMMEND
                    ? "已为你生成场景组合推荐，共 " + productCards.size() + " 个商品候选。"
                    : "已为你找到 " + productCards.size() + " 个商品候选。");

            updates.put(GuideGraphStateKeys.PRODUCT_CANDIDATES, productCards.stream().map(this::productCardMap).toList());
            updates.put(GuideGraphStateKeys.NODE_MESSAGE, workflowResult.get("message"));
            updatedSessionState = withCandidateSnapshot(sessionState, candidateSnapshot);
        }
        if (updatedSessionState != null) {
            updates.put(GuideGraphStateKeys.AGENT_SESSION_STATE, updatedSessionState);
        }

        updates.put(GuideGraphStateKeys.WORKFLOW_RESULT, workflowResult);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("workflow", GuideGraphNodeNames.PRODUCT_RECOMMEND_WORKFLOW);
        metadata.put("intent", intent.name());
        metadata.put("subIntent", subIntent.name());
        metadata.put("subScene", subScene.name());
        metadata.put("strategy", subScene.strategyName());
        metadata.put("recallPlan.sources", recallPlan.enabledSources().stream().map(Enum::name).toList());
        metadata.put("recallPlan.perSourceLimit", recallPlan.perSourceLimit());
        metadata.put("recallPlan.outputLimit", recallPlan.outputLimit());
        metadata.put("recallPlan.enforcePositiveConstraints", recallPlan.enforcePositiveConstraints());
        metadata.put("sessionStatePresent", sessionState != null);
        metadata.put("queryTextPresent", queryContext.queryText() != null && !queryContext.queryText().isBlank());
        metadata.put("hasImage", queryContext.imageRef() != null || queryContext.imageCaption() != null
                || queryContext.imageEmbeddingRef() != null);
        metadata.put("positiveConstraintCount", queryContext.positiveConstraints().size());
        metadata.put("negativeConstraintCount", queryContext.negativeConstraints().size());
        if (productRecommendationEnabled()) {
            Object recallSummary = workflowResult.get("recallSummary");
            if (recallSummary instanceof Map<?, ?> summary) {
                summary.forEach((key, value) -> metadata.put("recall." + key, value));
            }
        }

        return new GuideNodeExecutionResult(null, null, workflowResult, updates, metadata);
    }

    private boolean productRecommendationEnabled() {
        return productMultiRecallService != null
                && productCandidatePostProcessor != null
                && productCardMapper != null;
    }

    private Map<String, Object> recommendationExecutionChecks(
            ProductRecommendSubScene subScene,
            UnifiedQueryContext queryContext
    ) {
        Map<String, Object> checks = new LinkedHashMap<>();
        Map<String, Object> positiveConstraints = queryContext == null ? Map.of() : queryContext.positiveConstraints();
        Map<String, Object> negativeConstraints = queryContext == null ? Map.of() : queryContext.negativeConstraints();
        boolean hasQueryText = queryContext != null && queryContext.queryText() != null
                && !queryContext.queryText().isBlank();
        boolean hasImage = queryContext != null && (hasText(queryContext.imageRef())
                || hasText(queryContext.imageCaption())
                || hasText(queryContext.imageEmbeddingRef()));
        checks.put("mode", "nonBlocking");
        checks.put("queryTextPresent", hasQueryText);
        checks.put("positiveConstraintCount", positiveConstraints.size());
        checks.put("negativeConstraintCount", negativeConstraints.size());
        checks.put("candidateSnapshotPresent", queryContext != null
                && !queryContext.candidateSnapshot().productIds().isEmpty());
        checks.put("imageInputPresent", hasImage);
        checks.put("action", "continue");

        String checkName = switch (subScene == null ? ProductRecommendSubScene.FUZZY_RECOMMEND : subScene) {
            case FUZZY_RECOMMEND -> "recallConditionCheck";
            case CONDITION_FILTER -> "filterConditionCheck";
            case MULTI_TURN_REFINE -> "refineContextCheck";
            case PRODUCT_COMPARE, DETAIL_FAQ_REVIEW_ANSWER -> "comparisonOrDetailObjectCheck";
            case NEGATIVE_CONSTRAINT -> "negativeConstraintCheck";
            case SCENE_BUNDLE_RECOMMEND -> "bundleGoalCheck";
            case PHOTO_SEARCH -> "imageInputCheck";
        };
        checks.put("checkName", checkName);
        checks.put("strictlySatisfied", switch (subScene == null ? ProductRecommendSubScene.FUZZY_RECOMMEND : subScene) {
            case PHOTO_SEARCH -> hasImage;
            case NEGATIVE_CONSTRAINT -> !negativeConstraints.isEmpty();
            case CONDITION_FILTER -> !positiveConstraints.isEmpty();
            case MULTI_TURN_REFINE, PRODUCT_COMPARE, DETAIL_FAQ_REVIEW_ANSWER ->
                    Boolean.TRUE.equals(checks.get("candidateSnapshotPresent")) || hasQueryText;
            case SCENE_BUNDLE_RECOMMEND -> hasQueryText || positiveConstraints.containsKey("scenario");
            case FUZZY_RECOMMEND -> hasQueryText || !positiveConstraints.isEmpty();
        });
        return Map.copyOf(checks);
    }

    private Map<String, Object> recallPlanMap(ProductRecallPlan recallPlan) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("subScene", recallPlan.subScene().name());
        map.put("enabledSources", recallPlan.enabledSources().stream().map(Enum::name).toList());
        map.put("perSourceLimit", recallPlan.perSourceLimit());
        map.put("outputLimit", recallPlan.outputLimit());
        map.put("enforcePositiveConstraints", recallPlan.enforcePositiveConstraints());
        map.put("description", recallPlan.description());
        return Map.copyOf(map);
    }

    private UnifiedQueryContext withSceneBundleRole(
            UnifiedQueryContext queryContext,
            SceneBundlePlan bundlePlan,
            SceneBundleRole role
    ) {
        Map<String, Object> positiveConstraints = new LinkedHashMap<>(
                queryContext == null ? Map.of() : queryContext.positiveConstraints()
        );
        positiveConstraints.putAll(role.constraints());
        putIfPresent(positiveConstraints, "scenario", bundlePlan.scenario());
        putIfPresent(positiveConstraints, "audience", bundlePlan.audience());
        putIfPresent(positiveConstraints, "usageContext", bundlePlan.usageContext());
        positiveConstraints.put("bundleRole", role.name());
        String baseQuery = queryContext == null ? null : queryContext.queryText();
        String roleQuery = mergeQueryText(baseQuery, role.query());
        return new UnifiedQueryContext(
                queryContext == null ? "1.0" : queryContext.schemaVersion(),
                queryContext == null ? ProductRecommendSubScene.SCENE_BUNDLE_RECOMMEND.name() : queryContext.intent(),
                roleQuery,
                queryContext == null ? List.of() : queryContext.inputModalities(),
                queryContext == null ? null : queryContext.imageRef(),
                queryContext == null ? null : queryContext.imageCaption(),
                queryContext == null ? null : queryContext.imageEmbeddingRef(),
                positiveConstraints,
                queryContext == null ? Map.of() : queryContext.negativeConstraints(),
                queryContext == null ? null : queryContext.scope(),
                queryContext == null ? null : queryContext.candidateSnapshot(),
                queryContext != null && queryContext.needClarify(),
                queryContext == null ? List.of() : queryContext.missingSlots(),
                queryContext == null ? null : queryContext.clarifyQuestion()
        );
    }

    private String mergeQueryText(String baseQuery, String roleQuery) {
        if (baseQuery == null || baseQuery.isBlank()) {
            return roleQuery;
        }
        if (roleQuery == null || roleQuery.isBlank() || baseQuery.contains(roleQuery)) {
            return baseQuery;
        }
        return baseQuery + " " + roleQuery;
    }

    private Map<String, Object> sceneBundleMap(
            SceneBundlePlan bundlePlan,
            List<Map<String, Object>> roleResults
    ) {
        Map<String, Object> map = new LinkedHashMap<>();
        putIfPresent(map, "scenario", bundlePlan.scenario());
        putIfPresent(map, "audience", bundlePlan.audience());
        putIfPresent(map, "usageContext", bundlePlan.usageContext());
        putIfPresent(map, "reason", bundlePlan.reason());
        map.put("roles", roleResults == null ? List.of() : List.copyOf(roleResults));
        map.put("bundleReason", bundleReason(bundlePlan, roleResults));
        return Map.copyOf(map);
    }

    private Map<String, Object> sceneBundleRoleMap(
            SceneBundleRole role,
            List<ProductCard> productCards,
            List<ProductRecallCandidate> recalledCandidates,
            ProductCandidatePostProcessResult postProcessResult
    ) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("roleId", role.roleId());
        map.put("name", role.name());
        map.put("query", role.query());
        map.put("constraints", role.constraints());
        map.put("reason", role.reason());
        map.put("products", productCards.stream().map(this::productCardMap).toList());
        map.put("recallSummary", recallSummary(recalledCandidates, postProcessResult));
        map.put("exclusions", postProcessResult.exclusions().stream().map(this::exclusionMap).toList());
        return Map.copyOf(map);
    }

    private String bundleReason(SceneBundlePlan bundlePlan, List<Map<String, Object>> roleResults) {
        long filledRoles = roleResults == null ? 0 : roleResults.stream()
                .filter(role -> {
                    Object products = role.get("products");
                    return products instanceof List<?> list && !list.isEmpty();
                })
                .count();
        return "围绕“" + bundlePlan.scenario() + "”拆出 "
                + bundlePlan.roles().size()
                + " 个角色，其中 "
                + filledRoles
                + " 个角色已召回到商品候选。";
    }

    private AgentSessionState withCandidateSnapshot(AgentSessionState sessionState, CandidateSnapshot candidateSnapshot) {
        AgentSessionState source = sessionState == null
                ? new AgentSessionState("1.0", null, null, null, List.of(), null, null, null, null)
                : sessionState;
        RecommendationState recommendation = source.recommendationState();
        RecommendationState updatedRecommendation = new RecommendationState(
                recommendation.activeIntent(),
                recommendation.scenario(),
                recommendation.accumulatedConstraints(),
                recommendation.negativeConstraints(),
                recommendation.missingSlots(),
                recommendation.clarifyQuestion(),
                candidateSnapshot,
                recommendation.lastRecommendationResult()
        );
        return new AgentSessionState(
                source.schemaVersion(),
                source.userId(),
                source.conversationId(),
                source.loadedAt(),
                source.recentMessages(),
                updatedRecommendation,
                source.multimodalState(),
                source.cartState(),
                source.orderState()
        );
    }

    private Map<String, Object> recallSummary(
            List<ProductRecallCandidate> recalledCandidates,
            ProductCandidatePostProcessResult postProcessResult
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("recalledCount", recalledCandidates == null ? 0 : recalledCandidates.size());
        summary.put("finalCount", postProcessResult.candidates().size());
        summary.put("excludedCount", postProcessResult.exclusions().size());
        Map<String, Long> sourceCounts = new LinkedHashMap<>();
        if (recalledCandidates != null) {
            recalledCandidates.stream()
                    .filter(candidate -> candidate.source() != null)
                    .forEach(candidate -> sourceCounts.merge(candidate.source().name(), 1L, Long::sum));
        }
        summary.put("sourceCounts", sourceCounts);
        return Map.copyOf(summary);
    }

    private Map<String, Object> productCardMap(ProductCard card) {
        Map<String, Object> map = new LinkedHashMap<>();
        putIfPresent(map, "productId", card.productId());
        putIfPresent(map, "skuId", card.skuId());
        putIfPresent(map, "externalRef", card.externalRef());
        putIfPresent(map, "title", card.title());
        putIfPresent(map, "brand", card.brand());
        putIfPresent(map, "price", card.price());
        putIfPresent(map, "stock", card.stock());
        putIfPresent(map, "imageUrl", card.imageUrl());
        putIfPresent(map, "spec", card.spec());
        putIfPresent(map, "recommendReason", card.recommendReason());
        map.put("evidence", card.evidence().stream().map(this::evidenceMap).toList());
        return Map.copyOf(map);
    }

    private Map<String, Object> comparisonMap(List<ProductCard> productCards, UnifiedQueryContext queryContext) {
        Map<String, Object> map = new LinkedHashMap<>();
        List<Map<String, Object>> items = productCards.stream()
                .map(this::comparisonItemMap)
                .toList();
        map.put("comparisonItems", items);
        map.put("differencePoints", differencePoints(productCards));
        putIfPresent(map, "decisionAdvice", decisionAdvice(productCards));
        putIfPresent(map, "queryText", queryContext == null ? null : queryContext.queryText());
        return Map.copyOf(map);
    }

    private Map<String, Object> negativeSummary(
            ProductCandidatePostProcessResult postProcessResult,
            UnifiedQueryContext queryContext
    ) {
        Map<String, Object> map = new LinkedHashMap<>();
        Map<String, Long> reasonCounts = new LinkedHashMap<>();
        postProcessResult.exclusions().stream()
                .filter(exclusion -> exclusion.reason() != null && !exclusion.reason().isBlank())
                .forEach(exclusion -> reasonCounts.merge(exclusion.reason(), 1L, Long::sum));
        map.put("negativeConstraints", queryContext == null ? Map.of() : queryContext.negativeConstraints());
        map.put("excludedCount", postProcessResult.exclusions().size());
        map.put("keptCount", postProcessResult.candidates().size());
        map.put("reasonCounts", reasonCounts);
        return Map.copyOf(map);
    }

    private Map<String, Object> comparisonItemMap(ProductCard card) {
        Map<String, Object> map = new LinkedHashMap<>();
        putIfPresent(map, "productId", card.productId());
        putIfPresent(map, "skuId", card.skuId());
        putIfPresent(map, "externalRef", card.externalRef());
        putIfPresent(map, "title", card.title());
        putIfPresent(map, "brand", card.brand());
        putIfPresent(map, "price", card.price());
        putIfPresent(map, "stock", card.stock());
        putIfPresent(map, "spec", card.spec());
        putIfPresent(map, "recommendReason", card.recommendReason());
        map.put("evidenceTypes", card.evidence().stream()
                .map(com.bytedance.ai.graph.productrecommend.ProductRecallEvidence::evidenceType)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList());
        return Map.copyOf(map);
    }

    private List<Map<String, Object>> differencePoints(List<ProductCard> productCards) {
        List<Map<String, Object>> points = new java.util.ArrayList<>();
        if (productCards == null || productCards.size() < 2) {
            return List.of();
        }
        if (productCards.stream().map(ProductCard::brand).filter(value -> value != null && !value.isBlank()).distinct().count() > 1) {
            points.add(differencePoint("brand", "品牌不同", valuesByProduct(productCards, ProductCard::brand)));
        }
        if (productCards.stream().map(ProductCard::price).filter(value -> value != null).distinct().count() > 1) {
            points.add(differencePoint("price", "价格不同", valuesByProduct(productCards, ProductCard::price)));
        }
        if (productCards.stream().map(ProductCard::stock).filter(value -> value != null).distinct().count() > 1) {
            points.add(differencePoint("stock", "库存不同", valuesByProduct(productCards, ProductCard::stock)));
        }
        return List.copyOf(points);
    }

    private Map<String, Object> valuesByProduct(
            List<ProductCard> productCards,
            java.util.function.Function<ProductCard, Object> valueProvider
    ) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (ProductCard card : productCards) {
            Object value = valueProvider.apply(card);
            if (value != null) {
                values.put(card.productId() == null ? card.title() : card.productId(), value);
            }
        }
        return Map.copyOf(values);
    }

    private Map<String, Object> differencePoint(String field, String label, Map<String, Object> values) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("field", field);
        point.put("label", label);
        point.put("values", values);
        return Map.copyOf(point);
    }

    private String decisionAdvice(List<ProductCard> productCards) {
        if (productCards == null || productCards.isEmpty()) {
            return null;
        }
        ProductCard cheapest = productCards.stream()
                .filter(card -> card.price() != null)
                .min(java.util.Comparator.comparing(ProductCard::price))
                .orElse(null);
        ProductCard mostStock = productCards.stream()
                .filter(card -> card.stock() != null)
                .max(java.util.Comparator.comparing(ProductCard::stock))
                .orElse(null);
        if (cheapest != null && mostStock != null && cheapest.productId() != null
                && cheapest.productId().equals(mostStock.productId())) {
            return "优先考虑 " + titleOrId(cheapest) + "：价格更低且库存更充足。";
        }
        if (cheapest != null) {
            return "如果更看重价格，优先考虑 " + titleOrId(cheapest) + "。";
        }
        if (mostStock != null) {
            return "如果更看重可买到，优先考虑 " + titleOrId(mostStock) + "。";
        }
        return "建议结合上方差异点和推荐理由选择。";
    }

    private String titleOrId(ProductCard card) {
        if (card.title() != null && !card.title().isBlank()) {
            return card.title();
        }
        return card.productId() == null ? "该商品" : card.productId();
    }

    private Map<String, Object> evidenceMap(com.bytedance.ai.graph.productrecommend.ProductRecallEvidence evidence) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (evidence.source() != null) {
            map.put("source", evidence.source().name());
        }
        putIfPresent(map, "evidenceType", evidence.evidenceType());
        putIfPresent(map, "title", evidence.title());
        putIfPresent(map, "content", evidence.content());
        putIfPresent(map, "chunkId", evidence.chunkId());
        putIfPresent(map, "parentChunkId", evidence.parentChunkId());
        putIfPresent(map, "productId", evidence.productId());
        map.put("metadata", evidence.metadata());
        return Map.copyOf(map);
    }

    private Map<String, Object> candidateSnapshotMap(CandidateSnapshot snapshot) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("productIds", snapshot.productIds());
        putIfPresent(map, "updatedAt", snapshot.updatedAt());
        map.put("items", snapshot.items().stream().map(item -> {
            Map<String, Object> itemMap = new LinkedHashMap<>();
            itemMap.put("rank", item.rank());
            putIfPresent(itemMap, "productId", item.productId());
            putIfPresent(itemMap, "skuId", item.skuId());
            putIfPresent(itemMap, "externalRef", item.externalRef());
            putIfPresent(itemMap, "title", item.title());
            putIfPresent(itemMap, "spec", item.spec());
            putIfPresent(itemMap, "price", item.price());
            putIfPresent(itemMap, "imageUrl", item.imageUrl());
            if (item.source() != null) {
                itemMap.put("source", item.source().name());
            }
            putIfPresent(itemMap, "reason", item.reason());
            return Map.copyOf(itemMap);
        }).toList());
        return Map.copyOf(map);
    }

    private Map<String, Object> exclusionMap(ProductCandidateExclusion exclusion) {
        Map<String, Object> map = new LinkedHashMap<>();
        putIfPresent(map, "productId", exclusion.productId());
        putIfPresent(map, "skuId", exclusion.skuId());
        putIfPresent(map, "externalRef", exclusion.externalRef());
        putIfPresent(map, "reason", exclusion.reason());
        return Map.copyOf(map);
    }

    private GuideNodeExecutionResult clarifyWorkflow(OverAllState state) {
        String message = MAIN_INTENT_LLM_TIMEOUT.equals(state.value(GuideGraphStateKeys.ERROR_CODE, ""))
                || MAIN_INTENT_ROUTER_FAILED.equals(state.value(GuideGraphStateKeys.ERROR_CODE, ""))
                ? "意图识别服务暂时超时，请重新发送一次。"
                : CLARIFY_MESSAGE;
        message = state.value(GuideGraphStateKeys.NODE_MESSAGE)
                .map(Object::toString)
                .filter(value -> !value.isBlank())
                .orElse(message);
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(GuideGraphStateKeys.NODE_MESSAGE, message);
        updates.put(GuideGraphStateKeys.NEED_USER_INPUT, true);
        updates.put(GuideGraphStateKeys.WORKFLOW_STATUS, NodeRunStatus.WAITING_CLARIFICATION.name());
        return new GuideNodeExecutionResult(
                NodeRunStatus.WAITING_CLARIFICATION,
                null,
                NodeRunStatus.WAITING_CLARIFICATION,
                updates,
                Map.of("needUserInput", true)
        );
    }

    private GuideGraphNodeAction workflowNode(String nodeName) {
        return state -> GuideNodeExecutionResult.workflow(
                Map.of(
                        "intent", state.value(GuideGraphStateKeys.MAIN_INTENT, GuideGraphIntent.CLARIFY.name()),
                        "workflow", nodeName,
                        "message", "Workflow is not implemented yet: " + nodeName,
                        "todo", true
                ),
                Map.of("todo", true, "workflow", nodeName)
        );
    }

    private GuideNodeExecutionResult orderManageWorkflow(OverAllState state) {
        if (orderManageSubgraphFactory == null) {
            throw new IllegalStateException("OrderManageSubgraphFactory is not configured");
        }
        OverAllState finalState;
        try {
            finalState = orderManageSubgraphFactory.build()
                    .compile()
                    .invoke(state.data())
                    .orElseThrow(() -> new IllegalStateException("Order manage subgraph completed without final state"));
        } catch (Exception exception) {
            throw new IllegalStateException("order_manage_workflow dispatch failed", exception);
        }
        Map<String, Object> updates = new LinkedHashMap<>(finalState.data());
        updates.remove(GuideGraphStateKeys.LAST_NODE_RESULT);
        updates.remove(GuideGraphStateKeys.NODE_RESULTS);
        updates.remove(GuideGraphStateKeys.GRAPH_STATUS);
        updates.remove(GuideGraphStateKeys.ANSWER_CONTEXT);
        updates.put(GuideGraphStateKeys.ORDER_WORKFLOW_DISPATCHED, true);
        String orderStatus = finalState.value(OrderManageStateKeys.ORDER_STATUS, "");
        String nodeMessage = finalState.value(OrderManageStateKeys.NODE_MESSAGE, "");
        NodeRunStatus statusOverride = orderNodeStatus(orderStatus);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(GuideGraphStateKeys.ORDER_WORKFLOW_DISPATCHED, true);
        putIfPresent(metadata, OrderManageStateKeys.ORDER_STATUS, orderStatus);
        finalState.value(OrderManageStateKeys.ORDER_ACTION)
                .ifPresent(value -> metadata.put(OrderManageStateKeys.ORDER_ACTION, value));
        if (statusOverride == NodeRunStatus.FAILED) {
            updates.put(GuideGraphStateKeys.ERROR_CODE, "ORDER_WORKFLOW_FAILED");
            updates.put(GuideGraphStateKeys.ERROR_MESSAGE, nodeMessage.isBlank()
                    ? "下单流程执行失败，请稍后重试。"
                    : nodeMessage);
            metadata.put(GuideGraphStateKeys.ERROR_CODE, "ORDER_WORKFLOW_FAILED");
            metadata.put(GuideGraphStateKeys.ERROR_MESSAGE, updates.get(GuideGraphStateKeys.ERROR_MESSAGE));
        }
        return new GuideNodeExecutionResult(statusOverride, null, null, updates, metadata);
    }

    private NodeRunStatus orderNodeStatus(String orderStatus) {
        if (OrderManageStatus.FAILED.name().equals(orderStatus)
                || OrderManageStatus.EXPIRED.name().equals(orderStatus)) {
            return NodeRunStatus.FAILED;
        }
        return null;
    }

    private GuideNodeExecutionResult buildAnswerContext(OverAllState state) {
        Map<String, Object> answerContext = new LinkedHashMap<>();
        answerContext.put("intent", GuideGraphStateValues.intent(state, GuideGraphStateKeys.INTENT)
                .orElse(GuideGraphIntent.CLARIFY).name());
        String targetWorkflow = state.value(GuideGraphStateKeys.TARGET_WORKFLOW, GuideGraphNodeNames.CLARIFY_WORKFLOW);
        answerContext.put("targetWorkflow", targetWorkflow);
        state.value(GuideGraphStateKeys.WORKFLOW_RESULT).ifPresent(workflowResult ->
                answerContext.put("workflowResult", workflowResult));
        mergeWorkflowAnswerContext(answerContext, state);

        if (GuideGraphNodeNames.ORDER_MANAGE_WORKFLOW.equals(targetWorkflow) && !orderWorkflowDispatched(state)) {
            answerContext.put("answer", ORDER_WORKFLOW_NOT_DISPATCHED_MESSAGE);
            answerContext.put("todo", false);
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(GuideGraphStateKeys.ANSWER_CONTEXT, Map.copyOf(answerContext));
            updates.put(GuideGraphStateKeys.ERROR_CODE, ORDER_WORKFLOW_NOT_DISPATCHED);
            updates.put(GuideGraphStateKeys.ERROR_MESSAGE, ORDER_WORKFLOW_NOT_DISPATCHED_MESSAGE);
            updates.put(GuideGraphStateKeys.NODE_MESSAGE, ORDER_WORKFLOW_NOT_DISPATCHED_MESSAGE);
            return new GuideNodeExecutionResult(
                    NodeRunStatus.FAILED,
                    null,
                    null,
                    updates,
                    Map.of(
                            "answerReady", true,
                            "todo", false,
                            GuideGraphStateKeys.ERROR_CODE, ORDER_WORKFLOW_NOT_DISPATCHED,
                            GuideGraphStateKeys.ERROR_MESSAGE, ORDER_WORKFLOW_NOT_DISPATCHED_MESSAGE
                    )
            );
        }

        String deterministicAnswer = explicitNodeMessage(state, targetWorkflow)
                .orElseGet(() -> fallbackAnswer(state));
        String answer = productRecommendationAnswer(targetWorkflow, answerContext, deterministicAnswer);

        answerContext.put("answer", answer);
        businessOperationResult(state, targetWorkflow).ifPresent(result -> {
            answerContext.put("operationResult", result);
            Object needUserInput = result.get("needUserInput");
            if (needUserInput != null) {
                answerContext.put("needUserInput", needUserInput);
            }
            if (Boolean.TRUE.equals(needUserInput)) {
                answerContext.put("workflowStatus", NodeRunStatus.WAITING_CLARIFICATION.name());
            } else if (result.get("status") != null) {
                answerContext.put("workflowStatus", result.get("status"));
            }
        });
        mergeStateAnswerContext(answerContext, state);
        answerContext.put("todo", false);
        return GuideNodeExecutionResult.withStateUpdates(
                Map.of(GuideGraphStateKeys.ANSWER_CONTEXT, Map.copyOf(answerContext)),
                Map.of("answerReady", true, "todo", false)
        );
    }

    private String productRecommendationAnswer(
            String targetWorkflow,
            Map<String, Object> answerContext,
            String fallbackAnswer
    ) {
        // 商品推荐文案改为在 SSE 层（GuideGraphStreamService）流式生成，graph 节点不再阻塞调用 LLM，
        // 以把首字延迟从一次性 ~5s 阻塞降到亚秒级增量输出。这里只回填确定性兜底文案，
        // 真正的 LLM 文案由 ProductRecommendationAnswerGenerator.generateStream 在流中产出并落库。
        return fallbackAnswer;
    }

    private GuideNodeExecutionResult terminalStateWriteback(OverAllState state) {
        Map<String, Object> writeback = new LinkedHashMap<>();
        AgentSessionState sessionState = state.value(GuideGraphStateKeys.AGENT_SESSION_STATE, AgentSessionState.class)
                .orElse(null);
        Map<String, Object> answerContext = state.value(GuideGraphStateKeys.ANSWER_CONTEXT, Map.of());
        AgentSessionState updatedSessionState = withTerminalRecommendationResult(sessionState, answerContext);
        updatedSessionState = withTerminalBusinessState(updatedSessionState, state);

        writeback.put("schemaVersion", "1.0");
        writeback.put("userId", state.value(GuideGraphStateKeys.USER_ID, ""));
        writeback.put("conversationId", state.value(GuideGraphStateKeys.CONVERSATION_ID, ""));
        writeback.put("runId", state.value(GuideGraphStateKeys.RUN_ID, ""));
        writeback.put("targetWorkflow", state.value(GuideGraphStateKeys.TARGET_WORKFLOW, ""));
        writeback.put("recommendation", terminalRecommendationMap(updatedSessionState.recommendationState()));
        writeback.put("cart", terminalCartMap(updatedSessionState.cartState()));
        writeback.put("order", terminalOrderMap(updatedSessionState.orderState()));
        writeback.put("persistence", Map.of(
                "recommendationState", "graph_state",
                "shoppingCart", "shopping_cart/cart_item",
                "order", "customer_order/order_item",
                "preference", "user_behavior_feedback"
        ));

        if (agentSessionStateRepository != null) {
            agentSessionStateRepository.save(updatedSessionState);
            writeback.put("stateStoreWrite", "agent_session_state");
        }
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(GuideGraphStateKeys.AGENT_SESSION_STATE, updatedSessionState);
        updates.put(GuideGraphStateKeys.TERMINAL_STATE_WRITEBACK, Map.copyOf(writeback));
        return GuideNodeExecutionResult.withStateUpdates(
                updates,
                Map.of(
                        "recommendationSnapshotSize",
                        updatedSessionState.recommendationState().candidateSnapshot().productIds().size(),
                        "cartAction", nullToEmpty(updatedSessionState.cartState().lastCartAction()),
                        "orderId", nullToEmpty(updatedSessionState.orderState().lastOrderId())
                )
        );
    }

    private AgentSessionState withTerminalRecommendationResult(
            AgentSessionState sessionState,
            Map<String, Object> answerContext
    ) {
        AgentSessionState source = sessionState == null
                ? new AgentSessionState("1.0", null, null, null, List.of(), null, null, null, null)
                : sessionState;
        RecommendationState recommendation = source.recommendationState();
        List<Map<String, Object>> products = typedMapList(answerContext.get("products"));
        RecommendationState updatedRecommendation = new RecommendationState(
                recommendation.activeIntent(),
                recommendation.scenario(),
                recommendation.accumulatedConstraints(),
                recommendation.negativeConstraints(),
                recommendation.missingSlots(),
                recommendation.clarifyQuestion(),
                recommendation.candidateSnapshot(),
                products.isEmpty()
                        ? recommendation.lastRecommendationResult()
                        : new com.bytedance.ai.graph.session.LastRecommendationResult(products)
        );
        return new AgentSessionState(
                source.schemaVersion(),
                source.userId(),
                source.conversationId(),
                source.loadedAt(),
                source.recentMessages(),
                updatedRecommendation,
                source.multimodalState(),
                source.cartState(),
                source.orderState()
        );
    }

    private AgentSessionState withTerminalBusinessState(AgentSessionState sessionState, OverAllState state) {
        AgentSessionState source = sessionState == null
                ? new AgentSessionState("1.0", null, null, null, List.of(), null, null, null, null)
                : sessionState;
        com.bytedance.ai.graph.session.CartState cartState = new com.bytedance.ai.graph.session.CartState(
                firstString(
                        state.value(com.bytedance.ai.graph.cartmanage.subgraph.CartGraphStateKeys.CART_ACTION).orElse(null),
                        source.cartState().lastCartAction()
                ),
                firstString(
                        state.value(com.bytedance.ai.graph.cartmanage.subgraph.CartGraphStateKeys.PENDING_CART_ACTION_ID).orElse(null),
                        source.cartState().pendingActionId()
                )
        );
        com.bytedance.ai.graph.session.OrderState orderState = new com.bytedance.ai.graph.session.OrderState(
                firstString(
                        state.value(OrderManageStateKeys.PENDING_ORDER_ACTION_ID).orElse(null),
                        source.orderState().pendingOrderActionId()
                ),
                firstString(
                        state.value(OrderManageStateKeys.ORDER_NO).orElse(null),
                        source.orderState().lastOrderId()
                )
        );
        return new AgentSessionState(
                source.schemaVersion(),
                source.userId(),
                source.conversationId(),
                source.loadedAt(),
                source.recentMessages(),
                source.recommendationState(),
                source.multimodalState(),
                cartState,
                orderState
        );
    }

    private Map<String, Object> terminalRecommendationMap(RecommendationState recommendation) {
        Map<String, Object> map = new LinkedHashMap<>();
        putIfPresent(map, "activeIntent", recommendation.activeIntent());
        putIfPresent(map, "scenario", recommendation.scenario());
        map.put("positiveConstraints", recommendation.accumulatedConstraints());
        map.put("negativeConstraints", recommendation.negativeConstraints());
        map.put("missingSlots", recommendation.missingSlots());
        map.put("candidateSnapshot", candidateSnapshotMap(recommendation.candidateSnapshot()));
        map.put("lastRecommendationResult", recommendation.lastRecommendationResult().products());
        return Map.copyOf(map);
    }

    private Map<String, Object> terminalCartMap(com.bytedance.ai.graph.session.CartState cartState) {
        Map<String, Object> map = new LinkedHashMap<>();
        putIfPresent(map, "lastCartAction", cartState.lastCartAction());
        putIfPresent(map, "pendingActionId", cartState.pendingActionId());
        map.put("storage", "shopping_cart/cart_item");
        return Map.copyOf(map);
    }

    private Map<String, Object> terminalOrderMap(com.bytedance.ai.graph.session.OrderState orderState) {
        Map<String, Object> map = new LinkedHashMap<>();
        putIfPresent(map, "pendingOrderActionId", orderState.pendingOrderActionId());
        putIfPresent(map, "lastOrderId", orderState.lastOrderId());
        map.put("storage", "customer_order/order_item");
        return Map.copyOf(map);
    }

    private List<Map<String, Object>> typedMapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> typed = new LinkedHashMap<>();
                map.forEach((key, mapValue) -> {
                    if (key != null && mapValue != null) {
                        typed.put(String.valueOf(key), mapValue);
                    }
                });
                result.add(Map.copyOf(typed));
            }
        }
        return List.copyOf(result);
    }

    private String firstString(Object preferred, String fallback) {
        if (preferred == null) {
            return fallback;
        }
        String value = String.valueOf(preferred);
        return value.isBlank() ? fallback : value;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void mergeWorkflowAnswerContext(Map<String, Object> answerContext, OverAllState state) {
        Object workflowResult = state.value(GuideGraphStateKeys.WORKFLOW_RESULT).orElse(null);
        if (!(workflowResult instanceof Map<?, ?> workflowMap)) {
            return;
        }
        copyIfPresent(answerContext, workflowMap, "products");
        copyIfPresent(answerContext, workflowMap, "bundle");
        copyIfPresent(answerContext, workflowMap, "comparison");
        copyIfPresent(answerContext, workflowMap, "negativeSummary");
        copyIfPresent(answerContext, workflowMap, "recallSummary");
        copyIfPresent(answerContext, workflowMap, "candidateSnapshot");
        Object candidateSnapshot = workflowMap.get("candidateSnapshot");
        if (candidateSnapshot instanceof Map<?, ?> snapshotMap) {
            putIfPresent(answerContext, "candidateSnapshotId", candidateSnapshotId(snapshotMap));
        }
        Object products = workflowMap.get("products");
        if (products instanceof List<?> productList) {
            List<Map<String, Object>> reasons = recommendationReasons(productList);
            if (!reasons.isEmpty()) {
                answerContext.put("recommendationReasons", reasons);
            }
            List<Map<String, Object>> evidence = productEvidence(productList);
            if (!evidence.isEmpty()) {
                answerContext.put("evidence", evidence);
            }
        }
        Map<String, Object> matchedSlots = matchedSlots(workflowMap);
        if (!matchedSlots.isEmpty()) {
            answerContext.put("matchedSlots", matchedSlots);
        }
        Object unifiedQueryContext = workflowMap.get(GuideGraphStateKeys.UNIFIED_QUERY_CONTEXT);
        if (unifiedQueryContext instanceof UnifiedQueryContext queryContext && !queryContext.missingSlots().isEmpty()) {
            answerContext.put("missingSlots", queryContext.missingSlots());
        }
    }

    private void mergeStateAnswerContext(Map<String, Object> answerContext, OverAllState state) {
        state.value(GuideGraphStateKeys.MISSING_SLOTS)
                .filter(List.class::isInstance)
                .map(List.class::cast)
                .filter(list -> !list.isEmpty())
                .ifPresent(list -> answerContext.put("missingSlots", list));
        state.value(GuideGraphStateKeys.WORKFLOW_STATUS)
                .ifPresent(value -> answerContext.putIfAbsent("workflowStatus", value));
        state.value(GuideGraphStateKeys.NEED_USER_INPUT)
                .ifPresent(value -> answerContext.putIfAbsent("needUserInput", value));
    }

    private void copyIfPresent(Map<String, Object> answerContext, Map<?, ?> source, String key) {
        Object value = source.get(key);
        if (value != null) {
            answerContext.put(key, value);
        }
    }

    private String candidateSnapshotId(Map<?, ?> candidateSnapshot) {
        Object updatedAt = candidateSnapshot.get("updatedAt");
        Object productIds = candidateSnapshot.get("productIds");
        String seed = String.valueOf(productIds) + ":" + String.valueOf(updatedAt);
        return "candidateSnapshot:" + Integer.toHexString(seed.hashCode());
    }

    private List<Map<String, Object>> recommendationReasons(List<?> products) {
        List<Map<String, Object>> reasons = new ArrayList<>();
        for (Object product : products) {
            if (!(product instanceof Map<?, ?> productMap)) {
                continue;
            }
            Object reason = productMap.get("recommendReason");
            if (reason == null || String.valueOf(reason).isBlank()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            copyIfPresent(item, productMap, "productId");
            copyIfPresent(item, productMap, "skuId");
            copyIfPresent(item, productMap, "externalRef");
            copyIfPresent(item, productMap, "title");
            item.put("reason", reason);
            reasons.add(Map.copyOf(item));
        }
        return List.copyOf(reasons);
    }

    private List<Map<String, Object>> productEvidence(List<?> products) {
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (Object product : products) {
            if (!(product instanceof Map<?, ?> productMap)) {
                continue;
            }
            Object productEvidence = productMap.get("evidence");
            if (!(productEvidence instanceof List<?> evidenceList)) {
                continue;
            }
            for (Object item : evidenceList) {
                if (!(item instanceof Map<?, ?> evidenceMap)) {
                    continue;
                }
                Map<String, Object> enriched = new LinkedHashMap<>();
                copyIfPresent(enriched, productMap, "productId");
                copyIfPresent(enriched, productMap, "title");
                evidenceMap.forEach((key, value) -> {
                    if (key != null && value != null) {
                        enriched.put(String.valueOf(key), value);
                    }
                });
                evidence.add(Map.copyOf(enriched));
            }
        }
        return List.copyOf(evidence);
    }

    private Map<String, Object> matchedSlots(Map<?, ?> workflowMap) {
        Map<String, Object> matchedSlots = new LinkedHashMap<>();
        copyIfPresent(matchedSlots, workflowMap, "subScene");
        Object unifiedQueryContext = workflowMap.get(GuideGraphStateKeys.UNIFIED_QUERY_CONTEXT);
        if (unifiedQueryContext instanceof UnifiedQueryContext queryContext) {
            if (!queryContext.positiveConstraints().isEmpty()) {
                matchedSlots.put("positiveConstraints", queryContext.positiveConstraints());
            }
            if (!queryContext.negativeConstraints().isEmpty()) {
                matchedSlots.put("negativeConstraints", queryContext.negativeConstraints());
            }
            matchedSlots.put("inputModalities", queryContext.inputModalities());
            if (queryContext.scope() != null) {
                matchedSlots.put("scope", queryContext.scope());
            }
        }
        return Map.copyOf(matchedSlots);
    }

    private Optional<Map<String, Object>> businessOperationResult(OverAllState state, String targetWorkflow) {
        if (GuideGraphNodeNames.CART_MANAGE_WORKFLOW.equals(targetWorkflow)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("workflow", targetWorkflow);
            state.value(com.bytedance.ai.graph.cartmanage.subgraph.CartGraphStateKeys.CART_ACTION)
                    .ifPresent(value -> result.put("action", value));
            state.value(com.bytedance.ai.graph.cartmanage.subgraph.CartGraphStateKeys.WORKFLOW_STATUS)
                    .ifPresent(value -> result.put("status", value));
            state.value(com.bytedance.ai.graph.cartmanage.subgraph.CartGraphStateKeys.NEED_USER_INPUT)
                    .ifPresent(value -> result.put("needUserInput", value));
            state.value(com.bytedance.ai.graph.cartmanage.subgraph.CartGraphStateKeys.NODE_MESSAGE)
                    .ifPresent(value -> result.put("message", value));
            state.value(com.bytedance.ai.graph.cartmanage.subgraph.CartGraphStateKeys.CART_RESULT)
                    .ifPresent(value -> result.put("cart", value));
            state.value(GuideGraphStateKeys.ERROR_CODE).ifPresent(value -> result.put("errorCode", value));
            state.value(GuideGraphStateKeys.ERROR_MESSAGE).ifPresent(value -> result.put("errorMessage", value));
            return result.size() > 1 ? Optional.of(Map.copyOf(result)) : Optional.empty();
        }
        if (GuideGraphNodeNames.ORDER_MANAGE_WORKFLOW.equals(targetWorkflow)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("workflow", targetWorkflow);
            state.value(OrderManageStateKeys.ORDER_ACTION).ifPresent(value -> result.put("action", value));
            state.value(OrderManageStateKeys.ORDER_STATUS).ifPresent(value -> result.put("status", value));
            state.value(OrderManageStateKeys.NEED_USER_INPUT).ifPresent(value -> result.put("needUserInput", value));
            state.value(OrderManageStateKeys.NODE_MESSAGE).ifPresent(value -> result.put("message", value));
            state.value(OrderManageStateKeys.ORDER_NO).ifPresent(value -> result.put("orderNo", value));
            state.value(OrderManageStateKeys.ERROR_REASON).ifPresent(value -> result.put("errorReason", value));
            state.value(GuideGraphStateKeys.ERROR_CODE).ifPresent(value -> result.put("errorCode", value));
            state.value(GuideGraphStateKeys.ERROR_MESSAGE).ifPresent(value -> result.put("errorMessage", value));
            return result.size() > 1 ? Optional.of(Map.copyOf(result)) : Optional.empty();
        }
        return Optional.empty();
    }

    private String fallbackAnswer(OverAllState state) {
        if (hasFailedState(state) || hasStateErrorCode(state)) {
            return FAILURE_MESSAGE;
        }
        if (isClarifyState(state)) {
            return CLARIFY_MESSAGE;
        }
        Object workflowResult = state.value(GuideGraphStateKeys.WORKFLOW_RESULT).orElse(null);
        if (workflowResult instanceof CartManageWorkflowResult cartResult) {
            return cartManageAnswer(cartResult, state.value(GuideGraphStateKeys.CLARIFY_REASON, ""));
        }
        Object businessResult = state.value(GuideGraphStateKeys.BUSINESS_RESULT).orElse(null);
        if (businessResult instanceof Map<?, ?> map) {
            Object errorMessage = map.get("errorMessage");
            if (errorMessage != null && !String.valueOf(errorMessage).isBlank()) {
                return String.valueOf(errorMessage);
            }
        }
        if (hasSuccessfulWorkflowStatus(state)) {
            return "处理完成。";
        }
        return NO_BUSINESS_RESULT_MESSAGE;
    }

    private String cartManageAnswer(CartManageWorkflowResult result, String clarifyReason) {
        if (result.clarifyQuestion() != null && !result.clarifyQuestion().isBlank()) {
            return result.clarifyQuestion();
        }
        if (result.errorMessage() != null && !result.errorMessage().isBlank()) {
            return result.errorMessage();
        }
        if (result.action() == null) {
            return NO_BUSINESS_RESULT_MESSAGE;
        }
        return switch (result.action()) {
            case ADD -> addSuccessAnswer(result);
            case REMOVE_ITEM -> "已从购物车删除该商品。";
            case UPDATE_QUANTITY -> "已更新购物车商品数量。";
            case CLEAR_CART -> "你确认要清空整个购物车吗？这个操作不可撤销。";
            case VIEW_CART -> "这是你当前购物车中的商品。";
            case UNKNOWN -> clarifyFallback(clarifyReason);
        };
    }

    private String addSuccessAnswer(CartManageWorkflowResult result) {
        ProductCandidate candidate = result.productCandidates().isEmpty()
                ? null
                : result.productCandidates().getFirst();
        String productName = candidate == null || candidate.productName() == null
                ? "该商品"
                : candidate.productName();
        int quantity = result.slots() == null || result.slots().quantity() == null
                ? 1
                : Math.max(result.slots().quantity(), 1);
        return "已将「" + productName + "」加入购物车，数量 " + quantity + "。";
    }

    private String clarifyFallback(String clarifyReason) {
        return switch (clarifyReason == null ? "" : clarifyReason) {
            case "product_not_found" -> "我没有找到你说的商品，请换个商品名或提供更具体的品牌、型号、尺寸。";
            case "missing_quantity" -> "请告诉我要加入几件。";
            default -> "我需要再确认一下你的操作。";
        };
    }

    /** 从意图槽位里取出 LLM 同一次调用产出的 bundleRoles（场景组合的角色规划）。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> bundleRolesFromState(OverAllState state) {
        Object slotsObj = state.value(GuideGraphStateKeys.INTENT_SLOTS).orElse(null);
        if (!(slotsObj instanceof Map<?, ?> slots)) {
            return List.of();
        }
        Object roles = ((Map<String, Object>) slots).get("bundleRoles");
        if (roles instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    result.add((Map<String, Object>) m);
                }
            }
            return result;
        }
        return List.of();
    }

    private String routeByMockIntent(OverAllState state) {
        String target = state.value(GuideGraphStateKeys.TARGET_WORKFLOW, GuideGraphNodeNames.CLARIFY_WORKFLOW);
        // 模型判定 needClarify=true（条件不足）时，推荐类意图改走澄清工作流——先追问偏好，
        // 而不是凭模糊条件直接猜着推荐。业务类（购物车/下单）有各自子图的澄清机制，这里不拦截。
        boolean needClarify = state.value(GuideGraphStateKeys.NEED_CLARIFY, false);
        if (needClarify && GuideGraphNodeNames.PRODUCT_RECOMMEND_WORKFLOW.equals(target)) {
            return GuideGraphNodeNames.CLARIFY_WORKFLOW;
        }
        return target;
    }

    private String routeByConversationExists(OverAllState state) {
        boolean exists = state.value(GuideGraphStateKeys.CONVERSATION_EXISTS, false);
        return exists ? GuideGraphNodeNames.LOAD_MEMORY : GuideGraphNodeNames.INIT_CONVERSATION;
    }

    private Map<String, String> conversationExistenceMappings() {
        return Map.of(
                GuideGraphNodeNames.LOAD_MEMORY, GuideGraphNodeNames.LOAD_MEMORY,
                GuideGraphNodeNames.INIT_CONVERSATION, GuideGraphNodeNames.INIT_CONVERSATION
        );
    }

    private Map<String, String> workflowMappings() {
        Map<String, String> mappings = new LinkedHashMap<>();
        for (String nodeName : GuideGraphWorkflows.targets().values()) {
            mappings.put(nodeName, nodeName);
        }
        return mappings;
    }

    private String requiredString(OverAllState state, String key) {
        return state.value(key)
                .map(Object::toString)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("Missing graph state: " + key));
    }

    private Optional<String> explicitNodeMessage(OverAllState state, String targetWorkflow) {
        if (GuideGraphNodeNames.ORDER_MANAGE_WORKFLOW.equals(targetWorkflow)
                && state.value(OrderManageStateKeys.NODE_MESSAGE).isPresent()) {
            return nonBlankStateString(state, OrderManageStateKeys.NODE_MESSAGE);
        }
        if (GuideGraphNodeNames.CART_MANAGE_WORKFLOW.equals(targetWorkflow)
                && state.value(com.bytedance.ai.graph.cartmanage.subgraph.CartGraphStateKeys.NODE_MESSAGE).isPresent()) {
            return nonBlankStateString(state, com.bytedance.ai.graph.cartmanage.subgraph.CartGraphStateKeys.NODE_MESSAGE);
        }
        Optional<String> orderMessage = nonBlankStateString(state, OrderManageStateKeys.NODE_MESSAGE);
        if (orderMessage.isPresent()) {
            return orderMessage;
        }
        return nonBlankStateString(state, GuideGraphStateKeys.NODE_MESSAGE);
    }

    private Optional<String> nonBlankStateString(OverAllState state, String key) {
        return state.value(key).map(Object::toString).filter(value -> !value.isBlank());
    }

    private boolean orderWorkflowDispatched(OverAllState state) {
        return state.value(GuideGraphStateKeys.ORDER_WORKFLOW_DISPATCHED, false)
                || state.value(OrderManageStateKeys.ORDER_ACTION).isPresent()
                || state.value(OrderManageStateKeys.ORDER_STATUS).isPresent()
                || state.value(OrderManageStateKeys.NODE_MESSAGE).isPresent();
    }

    private boolean hasFailedState(OverAllState state) {
        if (NodeRunStatus.FAILED.name().equals(state.value(GuideGraphStateKeys.GRAPH_STATUS, ""))) {
            return true;
        }
        if ("FAILED".equals(state.value(GuideGraphStateKeys.WORKFLOW_STATUS, ""))) {
            return true;
        }
        if ("FAILED".equals(state.value(OrderManageStateKeys.ORDER_STATUS, ""))) {
            return true;
        }
        List<GuideNodeResult> nodeResults = state.value(GuideGraphStateKeys.NODE_RESULTS, List.<GuideNodeResult>of());
        return nodeResults.stream()
                .anyMatch(result -> result != null && result.status() == NodeRunStatus.FAILED);
    }

    private boolean hasStateErrorCode(OverAllState state) {
        return state.value(GuideGraphStateKeys.ERROR_CODE)
                .map(Object::toString)
                .filter(value -> !value.isBlank())
                .isPresent();
    }

    private boolean isClarifyState(OverAllState state) {
        String targetWorkflow = state.value(GuideGraphStateKeys.TARGET_WORKFLOW, "");
        if (GuideGraphNodeNames.CLARIFY_WORKFLOW.equals(targetWorkflow)) {
            return true;
        }
        return GuideGraphStateValues.intent(state, GuideGraphStateKeys.INTENT)
                .filter(intent -> intent == GuideGraphIntent.CLARIFY || intent == GuideGraphIntent.UNKNOWN)
                .isPresent();
    }

    private boolean hasSuccessfulWorkflowStatus(OverAllState state) {
        String workflowStatus = state.value(GuideGraphStateKeys.WORKFLOW_STATUS, "");
        return NodeRunStatus.SUCCESS.name().equals(workflowStatus) || workflowStatus.endsWith("_SUCCESS");
    }

    private void putIfPresent(Map<String, Object> updates, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            updates.put(key, value);
        }
    }

    private String mainIntentErrorCode(Throwable exception) {
        return hasCause(exception, SocketTimeoutException.class) || hasCause(exception, RestClientException.class)
                ? MAIN_INTENT_LLM_TIMEOUT
                : MAIN_INTENT_ROUTER_FAILED;
    }

    private String publicFailureMessage(String errorCode) {
        return MAIN_INTENT_LLM_TIMEOUT.equals(errorCode)
                ? "当前请求处理超时，请稍后重试。"
                : "当前请求处理失败，请稍后重试。";
    }

    private boolean hasCause(Throwable exception, Class<? extends Throwable> type) {
        Throwable current = exception;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String safeMessage(Throwable exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }

    private record MainIntentRouteResult(
            MainIntentDecision decision,
            String errorCode,
            String errorMessage,
            String nodeMessage,
            String routeSource,
            Boolean llmCalled
    ) {
    }
}
