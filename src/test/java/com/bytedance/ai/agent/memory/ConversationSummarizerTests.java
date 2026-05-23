package com.bytedance.ai.agent.memory;

import com.bytedance.ai.retrieval.spi.AgentTurnConversationState.ConversationTurn;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationSummarizerTests {

    @Test
    void skipsWhenHistoryDoesNotExceedRecentWindow() {
        ConversationSummarizer summarizer = new ConversationSummarizer(noChatModel());

        ConversationSummary summary = summarizer.summarize(history(6), Optional.of("旧摘要"), 4);

        assertThat(summary.summary()).contains("旧摘要");
        assertThat(summary.messageCount()).isEqualTo(4);
    }

    @Test
    void keepsPreviousSummaryWhenChatModelUnavailable() {
        ConversationSummarizer summarizer = new ConversationSummarizer(noChatModel());

        ConversationSummary summary = summarizer.summarize(history(8), Optional.of("旧摘要"), 1);

        assertThat(summary.summary()).contains("旧摘要");
        assertThat(summary.messageCount()).isEqualTo(1);
        assertThat(summary.model()).isEqualTo(ConversationSummarizer.MODEL_NAME);
    }

    @Test
    void generatesSummaryWithChatModel() {
        ConversationSummarizer summarizer = new ConversationSummarizer(chatModel("用户偏好拍照优先，预算 3000 内。"));

        ConversationSummary summary = summarizer.summarize(history(8), Optional.of("旧摘要"), 1);

        assertThat(summary.summary()).hasValueSatisfying(value -> assertThat(value).contains("用户偏好拍照优先"));
        assertThat(summary.messageCount()).isEqualTo(2);
        assertThat(summary.model()).isEqualTo(ConversationSummarizer.MODEL_NAME);
    }

    @Test
    void keepsPreviousSummaryWhenChatModelFails() {
        ConversationSummarizer summarizer = new ConversationSummarizer(chatModelThatFails());

        ConversationSummary summary = summarizer.summarize(history(8), Optional.of("旧摘要"), 1);

        assertThat(summary.summary()).contains("旧摘要");
        assertThat(summary.messageCount()).isEqualTo(1);
    }

    private static List<ConversationTurn> history(int size) {
        List<ConversationTurn> turns = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            turns.add(new ConversationTurn(i % 2 == 0 ? "user" : "assistant", "m" + i));
        }
        return turns;
    }

    private static ObjectProvider<ChatModel> chatModel(String response) {
        return provider(new FixedChatModel(response, null));
    }

    private static ObjectProvider<ChatModel> chatModelThatFails() {
        return provider(new FixedChatModel(null, new IllegalStateException("boom")));
    }

    private static ObjectProvider<ChatModel> noChatModel() {
        return provider(null);
    }

    private static ObjectProvider<ChatModel> provider(ChatModel chatModel) {
        return new ObjectProvider<>() {
            @Override
            public ChatModel getObject(Object... args) throws BeansException {
                return chatModel;
            }

            @Override
            public ChatModel getIfAvailable() throws BeansException {
                return chatModel;
            }

            @Override
            public ChatModel getIfUnique() throws BeansException {
                return chatModel;
            }

            @Override
            public ChatModel getObject() throws BeansException {
                return chatModel;
            }
        };
    }

    private record FixedChatModel(String response, RuntimeException failure) implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            if (failure != null) {
                throw failure;
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
        }
    }
}
