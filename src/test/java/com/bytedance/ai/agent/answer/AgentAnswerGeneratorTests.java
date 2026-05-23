package com.bytedance.ai.agent.answer;

import com.bytedance.ai.agent.api.CompareMatrixView;
import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.agent.memory.ConversationMemory;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAnswerGeneratorTests {

    @Test
    void fallbackCompareAnswerUsesMarkdownTableAndCitation() {
        AgentAnswerGenerator generator = new AgentAnswerGenerator(noChatModel(), new ClassPathResource("prompts/agent-answer-v1.txt"));
        AtomicBoolean generated = new AtomicBoolean(true);

        String answer = generator.generateStream(
                "A vs B 哪个保湿",
                cards(),
                matrix(),
                ConversationMemory.empty(),
                generated::set
        ).collectList().block().getFirst();

        assertThat(generated).isFalse();
        assertThat(answer).contains("| 属性 | [#1] A 面霜 | [#2] B 面霜 |");
        assertThat(answer).contains("推荐：[#1]");
    }

    @Test
    void promptIncludesCompareMatrixWhenChatModelExists() {
        CapturingChatModel chatModel = new CapturingChatModel();
        AgentAnswerGenerator generator = new AgentAnswerGenerator(provider(chatModel), new ClassPathResource("prompts/agent-answer-v1.txt"));

        String answer = String.join("", generator.generateStream(
                "A vs B 哪个保湿",
                cards(),
                matrix(),
                ConversationMemory.empty(),
                ignored -> {}
        ).collectList().block());

        assertThat(answer).contains("[#1]");
        assertThat(chatModel.lastPrompt.getContents()).contains("【对比矩阵】", "| 属性 | [#1] A 面霜 | [#2] B 面霜 |");
    }

    private List<SpuCardView> cards() {
        return List.of(
                new SpuCardView(1L, "SPU-A", "A 面霜", "Alpha", null,
                        new BigDecimal("299"), new BigDecimal("299"), 8, 0.9d, List.of(), List.of("保湿强"), "#1"),
                new SpuCardView(2L, "SPU-B", "B 面霜", "Beta", null,
                        new BigDecimal("199"), new BigDecimal("199"), 20, 0.8d, List.of(), List.of("清爽"), "#2")
        );
    }

    private CompareMatrixView matrix() {
        return new CompareMatrixView(
                List.of(
                        new CompareMatrixView.ProductColumn("#1", 1L, "SPU-A", "A 面霜"),
                        new CompareMatrixView.ProductColumn("#2", 2L, "SPU-B", "B 面霜")
                ),
                List.of(
                        new CompareMatrixView.AttributeRow("品牌", List.of("Alpha", "Beta")),
                        new CompareMatrixView.AttributeRow("保湿", List.of("强", "中"))
                ),
                "#1",
                "#1 更贴合关注点：保湿。"
        );
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

    private static class CapturingChatModel implements ChatModel {
        private Prompt lastPrompt;

        @Override
        public ChatResponse call(Prompt prompt) {
            lastPrompt = prompt;
            return new ChatResponse(List.of(new Generation(new AssistantMessage("推荐 [#1]。"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }
}
