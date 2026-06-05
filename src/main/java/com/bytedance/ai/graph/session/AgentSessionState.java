package com.bytedance.ai.graph.session;

import com.bytedance.ai.graph.conversation.ConversationMessage;

import java.time.Instant;
import java.util.List;

public record AgentSessionState(
        String schemaVersion,
        String userId,
        String conversationId,
        Instant loadedAt,
        List<AgentSessionMessage> recentMessages,
        RecommendationState recommendationState,
        MultimodalState multimodalState,
        CartState cartState,
        OrderState orderState
) {

    public AgentSessionState {
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
        recommendationState = recommendationState == null ? RecommendationState.empty() : recommendationState;
        multimodalState = multimodalState == null ? MultimodalState.empty() : multimodalState;
        cartState = cartState == null ? CartState.empty() : cartState;
        orderState = orderState == null ? OrderState.empty() : orderState;
    }

    public static AgentSessionState fromRecentMessages(
            String userId,
            String conversationId,
            List<ConversationMessage> recentMessages
    ) {
        List<AgentSessionMessage> messages = recentMessages == null
                ? List.of()
                : recentMessages.stream().map(AgentSessionMessage::from).toList();
        return new AgentSessionState(
                "1.0",
                userId,
                conversationId,
                Instant.now(),
                messages,
                RecommendationState.empty(),
                MultimodalState.empty(),
                CartState.empty(),
                OrderState.empty()
        );
    }

    public AgentSessionState withRecentMessages(List<ConversationMessage> recentMessages) {
        List<AgentSessionMessage> messages = recentMessages == null
                ? List.of()
                : recentMessages.stream().map(AgentSessionMessage::from).toList();
        return new AgentSessionState(
                schemaVersion,
                userId,
                conversationId,
                Instant.now(),
                messages,
                recommendationState,
                multimodalState,
                cartState,
                orderState
        );
    }
}
