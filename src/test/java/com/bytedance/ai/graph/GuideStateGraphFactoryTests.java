package com.bytedance.ai.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.bytedance.ai.graph.cart.api.CartItemView;
import com.bytedance.ai.graph.cart.api.CartState;
import com.bytedance.ai.graph.cart.api.CartView;
import com.bytedance.ai.graph.catalog.api.CatalogInventoryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogSkuView;
import com.bytedance.ai.graph.catalog.api.CatalogSpuView;
import com.bytedance.ai.graph.cartmanage.CartCommandService;
import com.bytedance.ai.graph.cartmanage.CartMutationResult;
import com.bytedance.ai.graph.cartmanage.subgraph.PendingCartActionRepository;
import com.bytedance.ai.graph.api.GuideGraphIntent;
import com.bytedance.ai.graph.api.GuideNodeExecutionResult;
import com.bytedance.ai.graph.api.GuideNodeResult;
import com.bytedance.ai.graph.cartmanage.subgraph.CartGraphStateKeys;
import com.bytedance.ai.graph.cartmanage.subgraph.CartWorkflowStatus;
import com.bytedance.ai.graph.conversation.AgentConversationRepository;
import com.bytedance.ai.graph.conversation.JdbcAgentConversationRepository;
import com.bytedance.ai.graph.input.MultimodalInputProcessingResult;
import com.bytedance.ai.graph.input.MultimodalInputProcessor;
import com.bytedance.ai.graph.ordermanage.AddressSnapshot;
import com.bytedance.ai.graph.ordermanage.MockOrderRecord;
import com.bytedance.ai.graph.ordermanage.MockOrderRepository;
import com.bytedance.ai.graph.ordermanage.OrderCartSnapshotService;
import com.bytedance.ai.graph.ordermanage.OrderCommandService;
import com.bytedance.ai.graph.ordermanage.OrderManageStateKeys;
import com.bytedance.ai.graph.ordermanage.OrderManageStatus;
import com.bytedance.ai.graph.ordermanage.OrderManageSubgraphFactory;
import com.bytedance.ai.graph.ordermanage.PendingOrderActionRecord;
import com.bytedance.ai.graph.ordermanage.PendingOrderActionRepository;
import com.bytedance.ai.graph.productrecommend.CandidateSnapshotMapper;
import com.bytedance.ai.graph.productrecommend.LightweightProductRanker;
import com.bytedance.ai.graph.productrecommend.NegativeConstraintFilter;
import com.bytedance.ai.graph.productrecommend.ProductCandidatePostProcessor;
import com.bytedance.ai.graph.productrecommend.ProductCardMapper;
import com.bytedance.ai.graph.productrecommend.ProductMultiRecallService;
import com.bytedance.ai.graph.productrecommend.ProductRecommendationAnswerGenerator;
import com.bytedance.ai.graph.productrecommend.ProductRecallCandidate;
import com.bytedance.ai.graph.productrecommend.ProductRecallEvidence;
import com.bytedance.ai.graph.productrecommend.ProductRecallRequest;
import com.bytedance.ai.graph.productrecommend.ProductRecallService;
import com.bytedance.ai.graph.productrecommend.ProductRecallSource;
import com.bytedance.ai.graph.productrecommend.RrfFusionService;
import com.bytedance.ai.graph.session.AgentSessionState;
import com.bytedance.ai.graph.session.AgentSessionStateRepository;
import com.bytedance.ai.graph.session.CandidateSnapshot;
import com.bytedance.ai.graph.session.CurrentTurnMultimodalContext;
import com.bytedance.ai.graph.session.RecommendationState;
import com.bytedance.ai.graph.session.UnifiedQueryContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GuideStateGraphFactoryTests {

    private GuideStateGraphFactory factory;
    private AgentConversationRepository conversationRepository;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:guidegraph-" + java.util.UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "sa",
                ""
        ));
        createSchema(jdbc);
        conversationRepository = new JdbcAgentConversationRepository(jdbc);
        factory = new GuideStateGraphFactory(conversationRepository);
    }

    @Test
    void compilesGuideStateGraph() {
        CompiledGraph graph = factory.compile(event -> {
        });

        assertThat(graph).isNotNull();
    }

    @Test
    void defaultsMockRouterToClarifyWorkflow() throws Exception {
        OverAllState state = invoke(Map.of());

        assertThat(GuideGraphStateValues.intent(state, GuideGraphStateKeys.INTENT)).contains(GuideGraphIntent.CLARIFY);
        assertThat(state.value(GuideGraphStateKeys.TARGET_WORKFLOW, ""))
                .isEqualTo(GuideGraphNodeNames.CLARIFY_WORKFLOW);
        assertTraceOrder(state, GuideGraphNodeNames.INIT_CONVERSATION, GuideGraphNodeNames.CLARIFY_WORKFLOW);
    }

    @Test
    void firstRequestInitializesConversationThenSavesUserMessage() throws Exception {
        OverAllState state = invoke(Map.of());

        assertThat(state.value(GuideGraphStateKeys.CONVERSATION_EXISTS, true)).isFalse();
        assertTraceOrder(state, GuideGraphNodeNames.INIT_CONVERSATION, GuideGraphNodeNames.CLARIFY_WORKFLOW);
    }

    @Test
    void secondRequestLoadsMemoryThenSavesUserMessage() throws Exception {
        invoke(Map.of(), "conversation-repeat", "run-first");

        OverAllState state = invoke(Map.of(), "conversation-repeat", "run-second");

        assertThat(state.value(GuideGraphStateKeys.CONVERSATION_EXISTS, false)).isTrue();
        assertThat(state.value(GuideGraphStateKeys.MESSAGE_COUNT, 0)).isGreaterThanOrEqualTo(1);
        Object sessionState = state.value(GuideGraphStateKeys.AGENT_SESSION_STATE).orElseThrow();
        assertThat(sessionStateValue(sessionState, "userId")).isEqualTo("user-1");
        assertThat(sessionStateValue(sessionState, "conversationId")).isEqualTo("conversation-repeat");
        assertThat(recentMessages(sessionState)).isNotEmpty();
        assertThat(accumulatedConstraints(sessionState)).isEmpty();
        assertThat(recommendationActiveIntent(sessionState)).isEqualTo(GuideGraphIntent.CLARIFY.name());
        assertTraceOrder(state, GuideGraphNodeNames.LOAD_MEMORY, GuideGraphNodeNames.CLARIFY_WORKFLOW);
    }

    @Test
    void unifiesCurrentTurnMultimodalContextBeforeIntentAndMergesSessionStateAfterIntent() throws Exception {
        OverAllState state = invokeWithOverrides(
                Map.of(
                        GuideGraphStateKeys.IMAGE_REF, "images/p_001.jpg",
                        GuideGraphStateKeys.IMAGE_CAPTION, "黑色通勤双肩包",
                        GuideGraphStateKeys.INPUT_MODALITIES, List.of("text", "image"),
                        GuideGraphStateKeys.INITIAL_INTENT, GuideGraphIntent.PHOTO_SEARCH
                ),
                "conversation-image",
                "run-image",
                "找类似图片里的包",
                Map.of()
        );

        CurrentTurnMultimodalContext context = state
                .value(GuideGraphStateKeys.CURRENT_TURN_MULTIMODAL_CONTEXT, CurrentTurnMultimodalContext.class)
                .orElseThrow();
        assertThat(context.hasImage()).isTrue();
        assertThat(context.inputModalities()).containsExactly("text", "image");
        assertThat(context.queryTextForRecall()).contains("找类似图片里的包", "黑色通勤双肩包");

        Object sessionState = state.value(GuideGraphStateKeys.AGENT_SESSION_STATE).orElseThrow();
        assertThat(recommendationActiveIntent(sessionState)).isEqualTo(GuideGraphIntent.PHOTO_SEARCH.name());
        Map<String, Object> currentMultimodalState = currentMultimodalState(sessionState);
        assertThat(currentMultimodalState.get("imageRef")).isEqualTo("images/p_001.jpg");
        assertThat(currentMultimodalState.get("imageCaption")).isEqualTo("黑色通勤双肩包");
        assertThat(currentMultimodalState.get("hasImage")).isEqualTo(true);
        Object queryContext = state.value(GuideGraphStateKeys.UNIFIED_QUERY_CONTEXT).orElseThrow();
        assertThat(queryContextValue(queryContext, "intent")).isEqualTo(GuideGraphIntent.PHOTO_SEARCH.name());
        assertThat(String.valueOf(queryContextValue(queryContext, "queryText"))).contains("找类似图片里的包", "黑色通勤双肩包");
        assertThat(queryContextValue(queryContext, "imageRef")).isEqualTo("images/p_001.jpg");
        assertThat(stringList(queryContextValue(queryContext, "inputModalities"))).containsExactly("text", "image");
        assertTraceOrder(state, GuideGraphNodeNames.INIT_CONVERSATION, GuideGraphWorkflows.targetFor(GuideGraphIntent.PHOTO_SEARCH));
    }

    @Test
    void currentTurnMultimodalUnifierCompletesMissingImageCaptionAndEmbeddingRef() throws Exception {
        MultimodalInputProcessor inputProcessor = (imageRef, generateCaption, generateEmbedding) -> Optional.of(
                new MultimodalInputProcessingResult(
                        generateCaption ? "黑色通勤双肩包" : null,
                        generateEmbedding ? "target/ecommerce-recall/image-input-embeddings/test.json" : null,
                        Map.of("imageRef", imageRef, "imageEmbeddingDimension", 2048)
                )
        );
        factory = new GuideStateGraphFactory(
                conversationRepository,
                null,
                null,
                null,
                null,
                null,
                null,
                inputProcessor,
                null,
                null,
                null,
                null
        );

        OverAllState state = invokeWithOverrides(
                Map.of(
                        GuideGraphStateKeys.IMAGE_REF, "images/p_001.jpg",
                        GuideGraphStateKeys.INPUT_MODALITIES, List.of("image"),
                        GuideGraphStateKeys.INITIAL_INTENT, GuideGraphIntent.PHOTO_SEARCH
                ),
                "conversation-image-processing",
                "run-image-processing",
                "Find similar products from the image",
                Map.of()
        );

        CurrentTurnMultimodalContext context = state
                .value(GuideGraphStateKeys.CURRENT_TURN_MULTIMODAL_CONTEXT, CurrentTurnMultimodalContext.class)
                .orElseThrow();
        assertThat(context.imageCaption()).isEqualTo("黑色通勤双肩包");
        assertThat(context.imageEmbeddingRef()).isEqualTo("target/ecommerce-recall/image-input-embeddings/test.json");
        assertThat(state.value(GuideGraphStateKeys.IMAGE_PROCESSING_RESULT, Map.of()))
                .containsEntry("imageEmbeddingDimension", 2048);
    }

    @Test
    void terminalStateWritebackPersistsAgentSessionStateWhenRepositoryExists() throws Exception {
        InMemoryAgentSessionStateRepository stateRepository = new InMemoryAgentSessionStateRepository();
        factory = new GuideStateGraphFactory(
                conversationRepository,
                null,
                null,
                null,
                null,
                null,
                stateRepository,
                null,
                null,
                null,
                null,
                null
        );

        invoke(Map.of(GuideGraphStateKeys.INITIAL_INTENT, GuideGraphIntent.FUZZY_RECOMMEND),
                "conversation-state-store",
                "run-state-store");

        AgentSessionState saved = stateRepository.saved;
        assertThat(saved).isNotNull();
        assertThat(saved.userId()).isEqualTo("user-1");
        assertThat(saved.conversationId()).isEqualTo("conversation-state-store");
        assertThat(saved.recommendationState().activeIntent()).isEqualTo(GuideGraphIntent.FUZZY_RECOMMEND.name());
    }

    @ParameterizedTest
    @EnumSource(GuideGraphIntent.class)
    void routesEveryIntentToExpectedWorkflow(GuideGraphIntent intent) throws Exception {
        OverAllState state = invoke(Map.of(GuideGraphStateKeys.INITIAL_INTENT, intent));

        String expectedWorkflow = GuideGraphWorkflows.targetFor(intent);
        assertThat(expectedWorkflow).isIn(
                GuideGraphNodeNames.PRODUCT_RECOMMEND_WORKFLOW,
                GuideGraphNodeNames.CART_MANAGE_WORKFLOW,
                GuideGraphNodeNames.ORDER_MANAGE_WORKFLOW,
                GuideGraphNodeNames.CLARIFY_WORKFLOW
        );
        assertThat(GuideGraphStateValues.intent(state, GuideGraphStateKeys.INTENT)).contains(intent);
        assertThat(state.value(GuideGraphStateKeys.SUB_INTENT, ""))
                .isEqualTo(intent.name());
        assertThat(state.value(GuideGraphStateKeys.TARGET_WORKFLOW, ""))
                .isEqualTo(expectedWorkflow);
        assertTraceOrder(state, GuideGraphNodeNames.INIT_CONVERSATION, expectedWorkflow);
        assertThat(state.<GuideNodeResult>value(GuideGraphStateKeys.LAST_NODE_RESULT))
                .map(GuideNodeResult::nodeName)
                .contains(GuideGraphNodeNames.TERMINAL_STATE_WRITEBACK);
    }

    @Test
    void legacyProductSearchRoutesToProductRecommendWorkflow() throws Exception {
        OverAllState state = invoke(Map.of(GuideGraphStateKeys.INITIAL_INTENT, GuideGraphIntent.PRODUCT_SEARCH));

        assertThat(GuideGraphStateValues.intent(state, GuideGraphStateKeys.INTENT))
                .contains(GuideGraphIntent.PRODUCT_SEARCH);
        assertThat(state.value(GuideGraphStateKeys.TARGET_WORKFLOW, ""))
                .isEqualTo(GuideGraphNodeNames.PRODUCT_RECOMMEND_WORKFLOW);
        assertTraceOrder(state, GuideGraphNodeNames.INIT_CONVERSATION, GuideGraphNodeNames.PRODUCT_RECOMMEND_WORKFLOW);
    }

    @Test
    void productRecommendWorkflowDispatchesRecommendationSubSceneStrategy() throws Exception {
        OverAllState state = invoke(Map.of(GuideGraphStateKeys.INITIAL_INTENT, GuideGraphIntent.PRODUCT_COMPARE));

        Map<String, Object> workflowResult = state.value(GuideGraphStateKeys.WORKFLOW_RESULT, Map.of());
        assertThat(workflowResult)
                .containsEntry("workflow", GuideGraphNodeNames.PRODUCT_RECOMMEND_WORKFLOW)
                .containsEntry("subIntent", "PRODUCT_COMPARE")
                .containsEntry("subScene", "PRODUCT_COMPARE")
                .containsEntry("strategy", "productCompareStrategy");
    }

    @Test
    void productRelatedQuestionDispatchesDetailFaqReviewAnswerStrategy() throws Exception {
        OverAllState state = invoke(Map.of(GuideGraphStateKeys.INITIAL_INTENT, GuideGraphIntent.PRICE_QUERY));

        Map<String, Object> workflowResult = state.value(GuideGraphStateKeys.WORKFLOW_RESULT, Map.of());
        assertThat(workflowResult)
                .containsEntry("workflow", GuideGraphNodeNames.PRODUCT_RECOMMEND_WORKFLOW)
                .containsEntry("subScene", "DETAIL_FAQ_REVIEW_ANSWER")
                .containsEntry("strategy", "detailFaqReviewAnswerStrategy");
    }

    @Test
    void conditionFilterWorkflowUsesStructuredFilterPlan() throws Exception {
        OverAllState state = invoke(Map.of(GuideGraphStateKeys.INITIAL_INTENT, GuideGraphIntent.CONDITION_FILTER));

        Map<String, Object> workflowResult = state.value(GuideGraphStateKeys.WORKFLOW_RESULT, Map.of());
        Map<?, ?> recallPlan = (Map<?, ?>) workflowResult.get("recallPlan");
        assertThat(workflowResult)
                .containsEntry("subScene", "CONDITION_FILTER")
                .containsEntry("strategy", "conditionFilterStrategy");
        assertThat(recallPlan.get("enabledSources"))
                .isEqualTo(List.of("CATALOG_FILTER", "RAG_CHUNK", "CATALOG_KEYWORD", "HISTORY_SNAPSHOT"));
        assertThat(recallPlan.get("enforcePositiveConstraints")).isEqualTo(true);
    }

    @Test
    void productRecommendWorkflowRunsRecallAndWritesProductCardsAndSnapshot() throws Exception {
        factory = new GuideStateGraphFactory(
                conversationRepository,
                null,
                null,
                null,
                null,
                null,
                new ProductMultiRecallService(List.of(fixedRecallService())),
                new ProductCandidatePostProcessor(
                        new RrfFusionService(),
                        new NegativeConstraintFilter(),
                        new LightweightProductRanker(),
                        new CandidateSnapshotMapper()
                ),
                new ProductCardMapper()
        );

        OverAllState state = invoke(Map.of(GuideGraphStateKeys.INITIAL_INTENT, GuideGraphIntent.FUZZY_RECOMMEND));

        Map<String, Object> workflowResult = state.value(GuideGraphStateKeys.WORKFLOW_RESULT, Map.of());
        assertThat(workflowResult).containsEntry("workflow", GuideGraphNodeNames.PRODUCT_RECOMMEND_WORKFLOW);
        Map<?, ?> recallPlan = (Map<?, ?>) workflowResult.get("recallPlan");
        assertThat(recallPlan.get("subScene")).isEqualTo("FUZZY_RECOMMEND");
        assertThat(recallPlan.get("enabledSources"))
                .isEqualTo(List.of("CATALOG_KEYWORD", "RAG_CHUNK", "HISTORY_SNAPSHOT", "PREFERENCE"));
        assertThat((List<?>) workflowResult.get("products")).hasSize(1);
        Map<?, ?> firstProduct = (Map<?, ?>) ((List<?>) workflowResult.get("products")).getFirst();
        assertThat(firstProduct.get("productId")).isEqualTo("p_beauty_001");
        assertThat(firstProduct.get("title")).isEqualTo("清透氨基酸洗面奶");
        Map<?, ?> recallSummary = (Map<?, ?>) workflowResult.get("recallSummary");
        assertThat(recallSummary.get("recalledCount")).isEqualTo(1);
        assertThat(recallSummary.get("finalCount")).isEqualTo(1);

        Object sessionState = state.value(GuideGraphStateKeys.AGENT_SESSION_STATE).orElseThrow();
        assertThat(candidateSnapshotProductIds(sessionState))
                .containsExactly("p_beauty_001");

        Map<String, Object> answerContext = state.value(GuideGraphStateKeys.ANSWER_CONTEXT, Map.of());
        assertThat(((Map<?, ?>) answerContext.get("workflowResult")).get("products")).isNotNull();
        assertThat((List<?>) answerContext.get("products")).hasSize(1);
        assertThat((List<?>) answerContext.get("recommendationReasons")).hasSize(1);
        assertThat((List<?>) answerContext.get("evidence")).hasSize(1);
        assertThat(answerContext.get("candidateSnapshotId")).asString().startsWith("candidateSnapshot:");
        assertThat(((Map<?, ?>) answerContext.get("matchedSlots")).get("subScene")).isEqualTo("FUZZY_RECOMMEND");

        Map<String, Object> terminalState = state.value(GuideGraphStateKeys.TERMINAL_STATE_WRITEBACK, Map.of());
        Map<?, ?> terminalRecommendation = (Map<?, ?>) terminalState.get("recommendation");
        assertThat((List<?>) terminalRecommendation.get("lastRecommendationResult")).hasSize(1);
        assertThat((Map<?, ?>) terminalRecommendation.get("candidateSnapshot")).isNotEmpty();
    }

    @Test
    void productRecommendWorkflowFeedsFinalCandidatesToAnswerGenerator() throws Exception {
        Map<String, Object>[] capturedContext = new Map[1];
        ProductRecommendationAnswerGenerator answerGenerator = (answerContext, fallbackAnswer) -> {
            capturedContext[0] = Map.copyOf(answerContext);
            return Optional.of("DeepSeek 生成的推荐回答");
        };
        factory = new GuideStateGraphFactory(
                conversationRepository,
                null,
                null,
                null,
                null,
                null,
                new ProductMultiRecallService(List.of(fixedRecallService())),
                new ProductCandidatePostProcessor(
                        new RrfFusionService(),
                        new NegativeConstraintFilter(),
                        new LightweightProductRanker(),
                        new CandidateSnapshotMapper()
                ),
                new ProductCardMapper(),
                answerGenerator
        );

        OverAllState state = invoke(Map.of(GuideGraphStateKeys.INITIAL_INTENT, GuideGraphIntent.FUZZY_RECOMMEND));

        Map<String, Object> answerContext = state.value(GuideGraphStateKeys.ANSWER_CONTEXT, Map.of());
        // 文案生成已移至 SSE 流式层（GuideGraphStreamService），build_answer_context 不再阻塞调用 LLM。
        assertThat(capturedContext[0]).isNull();
        // 但最终候选仍被正确组装进 answerContext，供流式层 generateStream 消费。
        assertThat((List<?>) answerContext.get("products")).hasSize(1);
        assertThat((List<?>) answerContext.get("recommendationReasons")).hasSize(1);
        assertThat(answerContext).containsEntry("targetWorkflow", GuideGraphNodeNames.PRODUCT_RECOMMEND_WORKFLOW);
        assertThat(answerContext.get("answer")).asString().isNotBlank();
    }

    @Test
    void productCompareWorkflowOutputsComparisonPayloadAndSnapshot() throws Exception {
        factory = new GuideStateGraphFactory(
                conversationRepository,
                null,
                null,
                null,
                null,
                null,
                new ProductMultiRecallService(List.of(compareRecallService())),
                new ProductCandidatePostProcessor(
                        new RrfFusionService(),
                        new NegativeConstraintFilter(),
                        new LightweightProductRanker(),
                        new CandidateSnapshotMapper()
                ),
                new ProductCardMapper()
        );

        OverAllState state = invoke(Map.of(GuideGraphStateKeys.INITIAL_INTENT, GuideGraphIntent.PRODUCT_COMPARE));

        Map<String, Object> workflowResult = state.value(GuideGraphStateKeys.WORKFLOW_RESULT, Map.of());
        assertThat(workflowResult).containsEntry("subScene", "PRODUCT_COMPARE");
        Map<?, ?> comparison = (Map<?, ?>) workflowResult.get("comparison");
        assertThat((List<?>) comparison.get("comparisonItems")).hasSize(2);
        assertThat((List<?>) comparison.get("differencePoints")).isNotEmpty();
        assertThat(comparison.get("decisionAdvice")).asString().contains("优先考虑");
        assertThat(candidateSnapshotProductIds(state.value(GuideGraphStateKeys.AGENT_SESSION_STATE).orElseThrow()))
                .containsExactly("p_compare_002", "p_compare_001");
    }

    @Test
    void negativeConstraintWorkflowOutputsNegativeSummary() throws Exception {
        factory = new GuideStateGraphFactory(
                conversationRepository,
                null,
                null,
                null,
                null,
                null,
                new ProductMultiRecallService(List.of(compareRecallService())),
                new ProductCandidatePostProcessor(
                        new RrfFusionService(),
                        new NegativeConstraintFilter(),
                        new LightweightProductRanker(),
                        new CandidateSnapshotMapper()
                ),
                new ProductCardMapper()
        );

        OverAllState state = invokeWithOverrides(
                Map.of(GuideGraphStateKeys.INITIAL_INTENT, GuideGraphIntent.NEGATIVE_CONSTRAINT),
                Map.of(GuideGraphNodeNames.AGENT_SESSION_STATE_MERGER,
                        currentState -> GuideNodeExecutionResult.withStateUpdates(
                                Map.of(GuideGraphStateKeys.AGENT_SESSION_STATE, new AgentSessionState(
                                        "1.0",
                                        "user-1",
                                        "conversation-1",
                                        null,
                                        List.of(),
                                        new RecommendationState(
                                                "NEGATIVE_CONSTRAINT",
                                                null,
                                                Map.of("category", "洁面"),
                                                Map.of("brands", List.of("品牌A")),
                                                List.of(),
                                                null,
                                                CandidateSnapshot.empty(),
                                                com.bytedance.ai.graph.session.LastRecommendationResult.empty()
                                        ),
                                        com.bytedance.ai.graph.session.MultimodalState.empty(),
                                        com.bytedance.ai.graph.session.CartState.empty(),
                                        com.bytedance.ai.graph.session.OrderState.empty()
                                )),
                                Map.of("override", true)
                        ))
        );

        Map<String, Object> workflowResult = state.value(GuideGraphStateKeys.WORKFLOW_RESULT, Map.of());
        assertThat(workflowResult).containsEntry("subScene", "NEGATIVE_CONSTRAINT");
        Map<?, ?> negativeSummary = (Map<?, ?>) workflowResult.get("negativeSummary");
        assertThat(negativeSummary.get("excludedCount")).isEqualTo(1);
        assertThat(((Map<?, ?>) negativeSummary.get("reasonCounts")).get("命中排除品牌")).isEqualTo(1L);
    }

    @Test
    void sceneBundleWorkflowOutputsRolesProductsAndSnapshot() throws Exception {
        factory = new GuideStateGraphFactory(
                conversationRepository,
                null,
                null,
                null,
                null,
                null,
                new ProductMultiRecallService(List.of(sceneBundleRecallService())),
                new ProductCandidatePostProcessor(
                        new RrfFusionService(),
                        new NegativeConstraintFilter(),
                        new LightweightProductRanker(),
                        new CandidateSnapshotMapper()
                ),
                new ProductCardMapper()
        );

        OverAllState state = invokeWithOverrides(
                Map.of(GuideGraphStateKeys.INITIAL_INTENT, GuideGraphIntent.SCENE_BUNDLE_RECOMMEND),
                Map.of(GuideGraphNodeNames.AGENT_SESSION_STATE_MERGER,
                        currentState -> GuideNodeExecutionResult.withStateUpdates(
                                Map.of(GuideGraphStateKeys.AGENT_SESSION_STATE, new AgentSessionState(
                                        "1.0",
                                        "user-1",
                                        "conversation-1",
                                        null,
                                        List.of(),
                                        new RecommendationState(
                                                "SCENE_BUNDLE_RECOMMEND",
                                                "露营",
                                                Map.of("scenario", "露营"),
                                                Map.of(),
                                                List.of(),
                                                null,
                                                CandidateSnapshot.empty(),
                                                com.bytedance.ai.graph.session.LastRecommendationResult.empty()
                                        ),
                                        new com.bytedance.ai.graph.session.MultimodalState(
                                                Map.of("message", "给我一套露营用的组合"),
                                                List.of()
                                        ),
                                        com.bytedance.ai.graph.session.CartState.empty(),
                                        com.bytedance.ai.graph.session.OrderState.empty()
                                )),
                                Map.of("override", true)
                        ))
        );

        Map<String, Object> workflowResult = state.value(GuideGraphStateKeys.WORKFLOW_RESULT, Map.of());
        assertThat(workflowResult).containsEntry("subScene", "SCENE_BUNDLE_RECOMMEND");
        Map<?, ?> bundle = (Map<?, ?>) workflowResult.get("bundle");
        assertThat(bundle.get("scenario")).isEqualTo("露营/户外");
        List<?> roles = (List<?>) bundle.get("roles");
        assertThat(roles).hasSize(4);
        assertThat((List<?>) ((Map<?, ?>) roles.getFirst()).get("products")).isNotEmpty();
        assertThat((List<?>) workflowResult.get("products")).isNotEmpty();
        assertThat(candidateSnapshotProductIds(state.value(GuideGraphStateKeys.AGENT_SESSION_STATE).orElseThrow()))
                .contains("p_bundle_light", "p_bundle_sunscreen");

        Map<String, Object> answerContext = state.value(GuideGraphStateKeys.ANSWER_CONTEXT, Map.of());
        assertThat(answerContext.get("bundle")).isNotNull();
        assertThat((List<?>) answerContext.get("products")).isNotEmpty();
        assertThat(answerContext.get("candidateSnapshotId")).asString().startsWith("candidateSnapshot:");
    }

    @Test
    void answerContextIsWrittenOnlyAsSeparateStateAndDoesNotContainTrace() throws Exception {
        OverAllState state = invoke(Map.of(GuideGraphStateKeys.INITIAL_INTENT, GuideGraphIntent.PRODUCT_SEARCH));

        Map<String, Object> answerContext = state.value(GuideGraphStateKeys.ANSWER_CONTEXT, Map.of());
        assertThat(answerContext).containsEntry("targetWorkflow", GuideGraphNodeNames.PRODUCT_RECOMMEND_WORKFLOW);
        assertThat(answerContext)
                .doesNotContainKeys(GuideGraphStateKeys.NODE_RESULTS, GuideGraphStateKeys.LAST_NODE_RESULT);
    }

    @Test
    void buildAnswerContextUsesFailureCopyWhenErrorCodeExistsWithoutNodeMessage() throws Exception {
        OverAllState state = invokeWithOverrides(
                Map.of(GuideGraphStateKeys.INITIAL_INTENT, GuideGraphIntent.PRODUCT_SEARCH),
                Map.of(GuideGraphNodeNames.PRODUCT_RECOMMEND_WORKFLOW,
                        _ -> GuideNodeExecutionResult.withStateUpdates(
                                Map.of(GuideGraphStateKeys.ERROR_CODE, "UPSTREAM_FAILED"),
                                Map.of("forcedError", true)
                        ))
        );

        Map<String, Object> answerContext = state.value(GuideGraphStateKeys.ANSWER_CONTEXT, Map.of());
        assertThat(answerContext.get("answer"))
                .isEqualTo("当前请求处理失败，请稍后重试，或换一种说法重新发送。");
        assertThat(String.valueOf(answerContext.get("answer"))).doesNotContain("我已经处理完成");
    }

    @Test
    void clarifyWorkflowWritesUserVisibleMessageAndNeedUserInput() throws Exception {
        OverAllState state = invoke(Map.of());

        assertThat(state.value(GuideGraphStateKeys.NODE_MESSAGE, ""))
                .contains("我暂时没能识别你的操作");
        assertThat(state.value(GuideGraphStateKeys.NEED_USER_INPUT, false)).isTrue();
        assertThat(state.value(GuideGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo("WAITING_CLARIFICATION");
    }

    @Test
    void cartViewSuccessStillReturnsCartListMessage() throws Exception {
        String cartList = "你的购物车中有：\n1. 轻量通勤双肩包 x 1";
        OverAllState state = invokeWithOverrides(
                Map.of(GuideGraphStateKeys.INITIAL_INTENT, GuideGraphIntent.CART_MANAGE),
                Map.of(GuideGraphNodeNames.CART_MANAGE_WORKFLOW,
                        _ -> GuideNodeExecutionResult.withStateUpdates(
                                Map.of(
                                        CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.VIEW_SUCCESS.name(),
                                        CartGraphStateKeys.NODE_MESSAGE, cartList
                                ),
                                Map.of("cartView", true)
                        ))
        );

        Map<String, Object> answerContext = state.value(GuideGraphStateKeys.ANSWER_CONTEXT, Map.of());
        assertThat(answerContext.get("answer")).isEqualTo(cartList);
    }

    @Test
    void cartAddSuccessStillReturnsAddSuccessMessage() throws Exception {
        String addSuccess = "已将「轻量通勤双肩包」加入购物车，数量 1。";
        OverAllState state = invokeWithOverrides(
                Map.of(GuideGraphStateKeys.INITIAL_INTENT, GuideGraphIntent.ADD_TO_CART),
                Map.of(GuideGraphNodeNames.CART_MANAGE_WORKFLOW,
                        _ -> GuideNodeExecutionResult.withStateUpdates(
                                Map.of(
                                        CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.ADD_SUCCESS.name(),
                                        CartGraphStateKeys.NODE_MESSAGE, addSuccess
                                ),
                                Map.of("cartAdd", true)
                        ))
        );

        Map<String, Object> answerContext = state.value(GuideGraphStateKeys.ANSWER_CONTEXT, Map.of());
        assertThat(answerContext.get("answer")).isEqualTo(addSuccess);
    }

    @Test
    void createOrderUsesOrderManageSubgraphWhenFactoryProvided() throws Exception {
        OrderGraphRig rig = new OrderGraphRig(cart(item(1L, 101L, "苹果", 2)));
        GuideStateGraphFactory orderFactory = new GuideStateGraphFactory(
                conversationRepository,
                null,
                null,
                null,
                rig.factory,
                rig.pending
        );
        CompiledGraph graph = orderFactory.compile(event -> {
        });
        Map<String, Object> initialState = new java.util.LinkedHashMap<>();
        initialState.put(GuideGraphStateKeys.USER_ID, "user-1");
        initialState.put(GuideGraphStateKeys.CONVERSATION_ID, "conversation-order");
        initialState.put(GuideGraphStateKeys.MESSAGE, "结算购物车");
        initialState.put(GuideGraphStateKeys.RUN_ID, "run-order");
        initialState.put(GuideGraphStateKeys.CORRELATION_ID, "corr-order");

        OverAllState state = graph.invoke(initialState).orElseThrow();

        assertThat(state.value(GuideGraphStateKeys.MAIN_INTENT, ""))
                .isEqualTo("CREATE_ORDER");
        assertThat(state.value(GuideGraphStateKeys.SUB_INTENT, ""))
                .isEqualTo("CREATE_ORDER");
        assertThat(state.value(GuideGraphStateKeys.TARGET_WORKFLOW, ""))
                .isEqualTo(GuideGraphNodeNames.ORDER_MANAGE_WORKFLOW);
        assertThat(state.value(GuideGraphStateKeys.WRITE_ACTION, false)).isTrue();
        assertThat(state.value(GuideGraphStateKeys.ROUTE_SOURCE, ""))
                .isEqualTo("RULE");
        assertThat(state.value(GuideGraphStateKeys.ORDER_WORKFLOW_DISPATCHED, false)).isTrue();
        List<GuideNodeResult> nodeResults = state.value(GuideGraphStateKeys.NODE_RESULTS, List.<GuideNodeResult>of());
        assertThat(nodeResults).extracting(GuideNodeResult::nodeName)
                .containsSubsequence(
                        GuideGraphNodeNames.MAIN_INTENT_ROUTER,
                        GuideGraphNodeNames.ORDER_MANAGE_WORKFLOW,
                        GuideGraphNodeNames.BUILD_ANSWER_CONTEXT
                );
        GuideNodeResult routerResult = nodeResults.stream()
                .filter(result -> GuideGraphNodeNames.MAIN_INTENT_ROUTER.equals(result.nodeName()))
                .findFirst()
                .orElseThrow();
        assertThat(routerResult.metadata())
                .containsEntry(GuideGraphStateKeys.ROUTE_SOURCE, "RULE");
        assertThat(rig.pending.active).isPresent();
        assertThat(rig.pending.active.get().status()).isEqualTo(OrderManageStatus.WAITING_ADDRESS);
        assertThat(state.value(OrderManageStateKeys.NODE_MESSAGE, ""))
                .contains("请补充")
                .doesNotContain("Workflow is not implemented");
        Map<String, Object> answerContext = state.value(GuideGraphStateKeys.ANSWER_CONTEXT, Map.of());
        assertThat(answerContext.get("answer"))
                .asString()
                .contains("请补充")
                .doesNotContain("Workflow is not implemented");
        assertThat(answerContext).containsEntry("needUserInput", true);
        assertThat(answerContext).containsEntry("workflowStatus", "WAITING_CLARIFICATION");
        Map<?, ?> operationResult = (Map<?, ?>) answerContext.get("operationResult");
        assertThat(operationResult.get("workflow")).isEqualTo(GuideGraphNodeNames.ORDER_MANAGE_WORKFLOW);
        assertThat(operationResult.get("action")).isEqualTo("CHECKOUT_REQUEST");
        assertThat(operationResult.get("status")).isEqualTo(OrderManageStatus.WAITING_ADDRESS.name());
        assertThat(operationResult.get("needUserInput")).isEqualTo(true);
    }

    @Test
    void createOrderFailsWhenOrderWorkflowIsNotDispatched() throws Exception {
        OverAllState state = invokeWithMessage(Map.of(), "结算购物车");

        assertThat(state.value(GuideGraphStateKeys.TARGET_WORKFLOW, ""))
                .isEqualTo(GuideGraphNodeNames.ORDER_MANAGE_WORKFLOW);
        assertThat(state.value(GuideGraphStateKeys.ROUTE_SOURCE, ""))
                .isEqualTo("RULE");
        assertThat(state.value(GuideGraphStateKeys.ERROR_CODE, ""))
                .isEqualTo("ORDER_WORKFLOW_NOT_DISPATCHED");
        assertThat(state.value(GuideGraphStateKeys.ERROR_MESSAGE, ""))
                .isEqualTo("下单流程没有被正确执行，请检查主图 workflow 路由。");
        Map<String, Object> answerContext = state.value(GuideGraphStateKeys.ANSWER_CONTEXT, Map.of());
        assertThat(answerContext.get("answer"))
                .isEqualTo("下单流程没有被正确执行，请检查主图 workflow 路由。");
        List<GuideNodeResult> nodeResults = state.value(GuideGraphStateKeys.NODE_RESULTS, List.<GuideNodeResult>of());
        assertThat(nodeResults)
                .anySatisfy(result -> {
                    assertThat(result.nodeName()).isEqualTo(GuideGraphNodeNames.BUILD_ANSWER_CONTEXT);
                    assertThat(result.status()).isEqualTo(com.bytedance.ai.graph.api.NodeRunStatus.FAILED);
                });
    }

    private OverAllState invoke(Map<String, Object> extraState) throws Exception {
        return invoke(extraState, "conversation-1", "run-1");
    }

    private ProductRecallService fixedRecallService() {
        return new ProductRecallService() {
            @Override
            public ProductRecallSource source() {
                return ProductRecallSource.CATALOG_KEYWORD;
            }

            @Override
            public List<ProductRecallCandidate> recall(ProductRecallRequest request) {
                return List.of(new ProductRecallCandidate(
                        "p_beauty_001",
                        "1",
                        "sku_beauty_001",
                        "p_beauty_001",
                        "清透氨基酸洗面奶",
                        "清透",
                        List.of("美妆护肤", "洁面"),
                        new BigDecimal("89.00"),
                        12,
                        "cleanser.jpg",
                        ProductRecallSource.CATALOG_KEYWORD,
                        0.9d,
                        0.9d,
                        Map.of("keyword", request.queryContext().queryText()),
                        List.of(new ProductRecallEvidence(
                                ProductRecallSource.CATALOG_KEYWORD,
                                "catalog_keyword",
                                "清透氨基酸洗面奶",
                                "适合油皮日常清洁。",
                                null,
                                null,
                                "p_beauty_001",
                                Map.of()
                        ))
                ));
            }
        };
    }

    private ProductRecallService compareRecallService() {
        return new ProductRecallService() {
            @Override
            public ProductRecallSource source() {
                return ProductRecallSource.HISTORY_SNAPSHOT;
            }

            @Override
            public List<ProductRecallCandidate> recall(ProductRecallRequest request) {
                return List.of(
                        compareCandidate("p_compare_001", "洁面A", "品牌A", new BigDecimal("129.00"), 8, 0.8d),
                        compareCandidate("p_compare_002", "洁面B", "品牌B", new BigDecimal("89.00"), 20, 0.9d)
                );
            }
        };
    }

    private ProductRecallService sceneBundleRecallService() {
        return new ProductRecallService() {
            @Override
            public ProductRecallSource source() {
                return ProductRecallSource.CATALOG_KEYWORD;
            }

            @Override
            public List<ProductRecallCandidate> recall(ProductRecallRequest request) {
                String query = Optional.ofNullable(request.queryContext().queryText()).orElse("");
                if (query.contains("照明")) {
                    return List.of(bundleCandidate("p_bundle_light", "户外露营灯", "山野", "数码电子", "照明", 0.95d));
                }
                if (query.contains("防晒")) {
                    return List.of(bundleCandidate("p_bundle_sunscreen", "清爽防晒乳", "清透", "美妆护肤", "防晒", 0.93d));
                }
                if (query.contains("补水")) {
                    return List.of(bundleCandidate("p_bundle_hydration", "舒缓补水喷雾", "清透", "美妆护肤", "补水", 0.9d));
                }
                if (query.contains("收纳")) {
                    return List.of(bundleCandidate("p_bundle_storage", "便携收纳袋", "山野", "食品饮料", "收纳", 0.88d));
                }
                return List.of(bundleCandidate("p_bundle_generic", "场景实用品", "通用", "食品饮料", "通用", 0.7d));
            }
        };
    }

    private ProductRecallCandidate bundleCandidate(
            String productId,
            String title,
            String brand,
            String category,
            String role,
            double score
    ) {
        return new ProductRecallCandidate(
                productId,
                productId.replace("p_bundle_", ""),
                "sku-" + productId,
                productId,
                title,
                brand,
                List.of(category, role),
                new BigDecimal("99.00"),
                10,
                productId + ".jpg",
                ProductRecallSource.CATALOG_KEYWORD,
                score,
                score,
                Map.of("bundleRole", role),
                List.of(new ProductRecallEvidence(
                        ProductRecallSource.CATALOG_KEYWORD,
                        "scene_bundle_role",
                        title,
                        "适合作为" + role + "角色商品。",
                        null,
                        null,
                        productId,
                        Map.of("bundleRole", role)
                ))
        );
    }

    private ProductRecallCandidate compareCandidate(
            String productId,
            String title,
            String brand,
            BigDecimal price,
            int stock,
            double score
    ) {
        return new ProductRecallCandidate(
                productId,
                productId.replace("p_compare_", ""),
                "sku-" + productId,
                productId,
                title,
                brand,
                List.of("美妆护肤", "洁面"),
                price,
                stock,
                productId + ".jpg",
                ProductRecallSource.HISTORY_SNAPSHOT,
                score,
                score,
                Map.of("snapshotRank", productId.endsWith("001") ? 1 : 2),
                List.of(new ProductRecallEvidence(
                        ProductRecallSource.HISTORY_SNAPSHOT,
                        "candidate_snapshot",
                        title,
                        "上一轮候选：" + title,
                        null,
                        null,
                        productId,
                        Map.of()
                ))
        );
    }

    private String recommendationActiveIntent(Object sessionState) {
        if (sessionState instanceof AgentSessionState state) {
            return state.recommendationState().activeIntent();
        }
        Map<?, ?> recommendationState = nestedMap(sessionState, "recommendationState");
        Object activeIntent = recommendationState.get("activeIntent");
        return activeIntent == null ? null : String.valueOf(activeIntent);
    }

    private Object sessionStateValue(Object sessionState, String key) {
        if (sessionState instanceof AgentSessionState state) {
            return switch (key) {
                case "userId" -> state.userId();
                case "conversationId" -> state.conversationId();
                default -> null;
            };
        }
        if (sessionState instanceof Map<?, ?> map) {
            return map.get(key);
        }
        return null;
    }

    private List<?> recentMessages(Object sessionState) {
        if (sessionState instanceof AgentSessionState state) {
            return state.recentMessages();
        }
        if (sessionState instanceof Map<?, ?> map && map.get("recentMessages") instanceof List<?> list) {
            return list;
        }
        return List.of();
    }

    private Map<?, ?> accumulatedConstraints(Object sessionState) {
        if (sessionState instanceof AgentSessionState state) {
            return state.recommendationState().accumulatedConstraints();
        }
        return nestedMap(nestedMap(sessionState, "recommendationState"), "accumulatedConstraints");
    }

    private Map<String, Object> currentMultimodalState(Object sessionState) {
        if (sessionState instanceof AgentSessionState state) {
            return state.multimodalState().current();
        }
        return stringObjectMap(nestedMap(nestedMap(sessionState, "multimodalState"), "current"));
    }

    private List<String> candidateSnapshotProductIds(Object sessionState) {
        if (sessionState instanceof AgentSessionState state) {
            return state.recommendationState().candidateSnapshot().productIds();
        }
        Map<?, ?> candidateSnapshot = nestedMap(nestedMap(sessionState, "recommendationState"), "candidateSnapshot");
        Object productIds = candidateSnapshot.get("productIds");
        return stringList(productIds);
    }

    private Object queryContextValue(Object queryContext, String key) {
        if (queryContext instanceof UnifiedQueryContext context) {
            return switch (key) {
                case "intent" -> context.intent();
                case "queryText" -> context.queryText();
                case "imageRef" -> context.imageRef();
                case "inputModalities" -> context.inputModalities();
                default -> null;
            };
        }
        if (queryContext instanceof Map<?, ?> map) {
            return map.get(key);
        }
        return null;
    }

    private Map<?, ?> nestedMap(Object value, String key) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Object nested = map.get(key);
        return nested instanceof Map<?, ?> nestedMap ? nestedMap : Map.of();
    }

    private Map<String, Object> stringObjectMap(Map<?, ?> raw) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key != null) {
                result.put(String.valueOf(key), value);
            }
        });
        return result;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private static final class InMemoryAgentSessionStateRepository implements AgentSessionStateRepository {

        private AgentSessionState saved;

        @Override
        public Optional<AgentSessionState> find(String userId, String conversationId) {
            if (saved == null || !saved.userId().equals(userId) || !saved.conversationId().equals(conversationId)) {
                return Optional.empty();
            }
            return Optional.of(saved);
        }

        @Override
        public void save(AgentSessionState state) {
            this.saved = state;
        }
    }

    private OverAllState invoke(Map<String, Object> extraState, String conversationId, String runId) throws Exception {
        return invokeWithOverrides(extraState, conversationId, runId, Map.of());
    }

    private OverAllState invokeWithOverrides(
            Map<String, Object> extraState,
            Map<String, GuideGraphNodeAction> actionOverrides
    ) throws Exception {
        return invokeWithOverrides(extraState, "conversation-1", "run-1", actionOverrides);
    }

    private OverAllState invokeWithMessage(Map<String, Object> extraState, String message) throws Exception {
        return invokeWithOverrides(extraState, "conversation-1", "run-1", message, Map.of());
    }

    private OverAllState invokeWithOverrides(
            Map<String, Object> extraState,
            String conversationId,
            String runId,
            Map<String, GuideGraphNodeAction> actionOverrides
    ) throws Exception {
        return invokeWithOverrides(extraState, conversationId, runId, "推荐 300 元以下的双肩包", actionOverrides);
    }

    private OverAllState invokeWithOverrides(
            Map<String, Object> extraState,
            String conversationId,
            String runId,
            String message,
            Map<String, GuideGraphNodeAction> actionOverrides
    ) throws Exception {
        CompiledGraph graph = factory.compile(event -> {
        }, actionOverrides);
        Map<String, Object> initialState = new java.util.LinkedHashMap<>();
        initialState.put(GuideGraphStateKeys.USER_ID, "user-1");
        initialState.put(GuideGraphStateKeys.CONVERSATION_ID, conversationId);
        initialState.put(GuideGraphStateKeys.MESSAGE, message);
        initialState.put(GuideGraphStateKeys.RUN_ID, runId);
        initialState.put(GuideGraphStateKeys.CORRELATION_ID, "corr-1");
        extraState.forEach((key, value) -> {
            if (value instanceof GuideGraphIntent intent) {
                initialState.put(key, intent.name());
            } else {
                initialState.put(key, value);
            }
        });
        return graph.invoke(initialState).orElseThrow();
    }

    private void assertTraceOrder(OverAllState state, String conversationBranchNode, String workflowNode) {
        List<GuideNodeResult> nodeResults = state.value(GuideGraphStateKeys.NODE_RESULTS, List.<GuideNodeResult>of());
        assertThat(nodeResults).extracting(GuideNodeResult::nodeName)
                .containsExactly(
                        GuideGraphNodeNames.CHECK_CONVERSATION,
                        conversationBranchNode,
                        GuideGraphNodeNames.LOAD_AGENT_SESSION_STATE,
                        GuideGraphNodeNames.CURRENT_TURN_MULTIMODAL_UNIFIER,
                        GuideGraphNodeNames.SAVE_USER_MESSAGE,
                        GuideGraphNodeNames.MAIN_INTENT_ROUTER,
                        GuideGraphNodeNames.AGENT_SESSION_STATE_MERGER,
                        workflowNode,
                        GuideGraphNodeNames.BUILD_ANSWER_CONTEXT,
                        GuideGraphNodeNames.TERMINAL_STATE_WRITEBACK
                );
        assertThat(nodeResults).extracting(GuideNodeResult::nodeName)
                .doesNotHaveDuplicates();
    }

    private void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE agent_conversations (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    conversation_id VARCHAR(128) NOT NULL UNIQUE,
                    user_id VARCHAR(128) NOT NULL,
                    title VARCHAR(200),
                    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                    message_count INTEGER NOT NULL DEFAULT 0,
                    metadata CLOB NOT NULL DEFAULT '{}',
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    last_message_at TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE agent_conversation_messages (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    message_id VARCHAR(128) NOT NULL UNIQUE,
                    conversation_id BIGINT NOT NULL,
                    role VARCHAR(16) NOT NULL,
                    content CLOB NOT NULL,
                    status VARCHAR(16) NOT NULL DEFAULT 'SUCCEEDED',
                    token_count INTEGER,
                    correlation_id VARCHAR(128),
                    sequence_no INTEGER NOT NULL,
                    metadata CLOB NOT NULL DEFAULT '{}',
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE (conversation_id, sequence_no)
                )
                """);
        jdbc.execute("""
                CREATE TABLE agent_turn (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    turn_id VARCHAR(64) NOT NULL UNIQUE,
                    request_id VARCHAR(64),
                    conversation_id VARCHAR(64) NOT NULL,
                    user_id VARCHAR(64) NOT NULL,
                    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                    intent VARCHAR(64),
                    target_workflow VARCHAR(64),
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    completed_at TIMESTAMP
                )
                """);
    }

    private static CartItemView item(Long itemId, Long spuId, String title, int quantity) {
        BigDecimal unitPrice = new BigDecimal("9.90");
        return new CartItemView(itemId, spuId, "SPU-" + spuId, title, "brand", null, quantity,
                unitPrice, unitPrice.multiply(BigDecimal.valueOf(quantity)), 10);
    }

    private static CartView cart(CartItemView... items) {
        List<CartItemView> itemList = List.of(items);
        BigDecimal amount = itemList.stream()
                .map(CartItemView::lineAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int itemCount = itemList.stream().mapToInt(item -> item.quantity() == null ? 0 : item.quantity()).sum();
        return new CartView("cart-1", "user-1", "conversation-order", CartState.IN_CART, "CNY",
                amount, itemCount, Map.of(), itemList);
    }

    private static <T> ObjectProvider<T> provider(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }

            @Override
            public T getObject() {
                return instance;
            }
        };
    }

    private static final class OrderGraphRig {
        CartView cart;
        final StubPendingOrderActionRepository pending = new StubPendingOrderActionRepository();
        final StubCatalog catalog = new StubCatalog();
        final StubInventory inventory = new StubInventory();
        final StubCartCommand cartCommand = new StubCartCommand();
        final StubMockOrders mockOrders = new StubMockOrders();
        final OrderCartSnapshotService snapshots = new OrderCartSnapshotService();
        final OrderManageSubgraphFactory factory;

        OrderGraphRig(CartView cart) {
            this.cart = cart;
            OrderCommandService commandService = new OrderCommandService(
                    pending,
                    (userId, conversationId) -> this.cart,
                    catalog,
                    inventory,
                    cartCommand,
                    mockOrders,
                    snapshots
            );
            this.factory = new OrderManageSubgraphFactory(
                    (userId, conversationId) -> this.cart,
                    catalog,
                    pending,
                    new com.bytedance.ai.graph.ordermanage.OrderAddressResolver(),
                    snapshots,
                    commandService,
                    provider((PendingCartActionRepository) null)
            );
        }
    }

    private static final class StubPendingOrderActionRepository implements PendingOrderActionRepository {
        Optional<PendingOrderActionRecord> active = Optional.empty();
        long nextId = 1L;

        @Override
        public Optional<PendingOrderActionRecord> findActiveByUserIdAndConversationId(String userId, String conversationId) {
            return active.filter(record -> record.status() == OrderManageStatus.WAITING_ADDRESS
                    || record.status() == OrderManageStatus.WAITING_CONFIRMATION
                    || record.status() == OrderManageStatus.CREATING);
        }

        @Override
        public Optional<PendingOrderActionRecord> findById(Long id) {
            return active.filter(record -> record.id().equals(id));
        }

        @Override
        public PendingOrderActionRecord save(PendingOrderActionRecord record) {
            PendingOrderActionRecord saved = copy(record, nextId++, record.status(), record.addressSnapshot(),
                    record.cartSnapshot(), record.cartSnapshotHash(), record.amountSnapshot(), null);
            active = Optional.of(saved);
            return saved;
        }

        @Override
        public void updateAddress(Long id, Map<String, Object> addressSnapshot) {
            active = active.map(record -> copy(record, record.id(), record.status(), addressSnapshot,
                    record.cartSnapshot(), record.cartSnapshotHash(), record.amountSnapshot(), null));
        }

        @Override
        public void markWaitingAddress(Long id) {
            active = active.map(record -> copy(record, record.id(), OrderManageStatus.WAITING_ADDRESS,
                    record.addressSnapshot(), record.cartSnapshot(), record.cartSnapshotHash(), record.amountSnapshot(), null));
        }

        @Override
        public void markWaitingConfirmation(Long id, Map<String, Object> cartSnapshot, String cartSnapshotHash,
                                            Map<String, Object> addressSnapshot, BigDecimal amount) {
            active = active.map(record -> copy(record, record.id(), OrderManageStatus.WAITING_CONFIRMATION,
                    addressSnapshot, cartSnapshot, cartSnapshotHash, amount, null));
        }

        @Override
        public boolean markCreatingIfWaitingConfirmation(Long id) {
            if (active.isEmpty() || active.get().status() != OrderManageStatus.WAITING_CONFIRMATION) {
                return false;
            }
            active = active.map(record -> copy(record, record.id(), OrderManageStatus.CREATING,
                    record.addressSnapshot(), record.cartSnapshot(), record.cartSnapshotHash(), record.amountSnapshot(), null));
            return true;
        }

        @Override
        public void markCreated(Long id, String orderNo) {
            active = Optional.empty();
        }

        @Override
        public void markCancelled(Long id) {
            active = Optional.empty();
        }

        @Override
        public void markFailed(Long id, String reason) {
            active = Optional.empty();
        }

        @Override
        public void markExpired(Long id) {
            active = Optional.empty();
        }

        private PendingOrderActionRecord copy(PendingOrderActionRecord record, Long id, OrderManageStatus status,
                                              Map<String, Object> address, Map<String, Object> cartSnapshot,
                                              String cartSnapshotHash, BigDecimal amount, String orderNo) {
            return new PendingOrderActionRecord(id, record.userId(), record.conversationId(), cartSnapshot,
                    cartSnapshotHash, address, amount, status, record.failReason(), orderNo,
                    record.createdAt(), LocalDateTime.now(), record.expireAt());
        }
    }

    private static final class StubCatalog implements CatalogQueryFacade {
        @Override
        public CatalogSpuView getSpu(Long spuId) {
            return new CatalogSpuView(spuId, "SPU-" + spuId, "苹果", "brand", "fruit",
                    new BigDecimal("9.90"), new BigDecimal("9.90"), 10, "", List.of(), null,
                    Map.of(), "DONE", "ACTIVE", null, List.of(), OffsetDateTime.now(), OffsetDateTime.now());
        }

        @Override
        public Optional<CatalogSpuView> findSpuByExternalRef(String externalRef) {
            return Optional.empty();
        }

        @Override
        public List<CatalogSkuView> listSkus(Long spuId) {
            return List.of();
        }
    }

    private static final class StubInventory implements CatalogInventoryFacade {
        @Override
        public void decreaseStock(Long spuId, int quantity) {
        }
    }

    private static final class StubCartCommand implements CartCommandService {
        @Override
        public CartMutationResult addItem(String userId, String conversationId, String productId, String skuId,
                                          int quantity, BigDecimal expectedUnitPrice) {
            return CartMutationResult.ok(null);
        }

        @Override
        public CartMutationResult removeItem(String userId, String conversationId, String cartItemId) {
            return CartMutationResult.ok(null);
        }

        @Override
        public CartMutationResult updateQuantity(String userId, String conversationId, String cartItemId, int quantity) {
            return CartMutationResult.ok(null);
        }

        @Override
        public CartMutationResult clearCart(String userId, String conversationId) {
            return CartMutationResult.ok(null);
        }
    }

    private static final class StubMockOrders implements MockOrderRepository {
        @Override
        public MockOrderRecord create(String orderNo, String userId, String conversationId, Map<String, Object> items,
                                      Map<String, Object> address, BigDecimal totalAmount) {
            return new MockOrderRecord(1L, orderNo, userId, conversationId, items, address, totalAmount,
                    "CREATED", LocalDateTime.now());
        }
    }
}
