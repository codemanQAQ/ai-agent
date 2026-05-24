package com.bytedance.ai.agent.slot;

import com.bytedance.ai.agent.api.Slot;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class NegationSlotExtractorTests {

    private final RagJsonCodec jsonCodec = new RagJsonCodec(JsonMapper.builder().build());
    private final NegationSlotExtractor extractor = new NegationSlotExtractor(noChatModel(), jsonCodec);

    @Test
    void fallbackExtractsIngredientsWhenChatModelMissing() {
        Slot.MustNot mn = extractor.extract("帮我找防晒霜，不含酒精和香精");
        assertThat(mn.ingredients()).containsExactlyInAnyOrder("酒精", "香精");
        assertThat(mn.brands()).isEmpty();
        assertThat(mn.tags()).isEmpty();
    }

    @Test
    void fallbackExtractsBrandViaWhitelist() {
        Slot.MustNot mn = extractor.extract("推荐蓝牙耳机，非苹果品牌");
        assertThat(mn.brands()).containsExactly("苹果");
        assertThat(mn.ingredients()).isEmpty();
    }

    @Test
    void fallbackUnknownTermFallsIntoTags() {
        Slot.MustNot mn = extractor.extract("双肩包不要黑色");
        assertThat(mn.tags()).containsExactly("黑色");
    }

    @Test
    void noNegationReturnsEmpty() {
        assertThat(extractor.extract("推荐通勤双肩包").isEmpty()).isTrue();
        assertThat(extractor.extract("").isEmpty()).isTrue();
        assertThat(extractor.extract(null).isEmpty()).isTrue();
    }

    @Test
    void parseAcceptsJsonObject() {
        Slot.MustNot mn = extractor.parse("""
                {"tags":["黑色"],"brands":["Apple"],"ingredients":["酒精"]}
                """);
        assertThat(mn.tags()).containsExactly("黑色");
        assertThat(mn.brands()).containsExactly("Apple");
        assertThat(mn.ingredients()).containsExactly("酒精");
    }

    private static ObjectProvider<ChatModel> noChatModel() {
        return new ObjectProvider<>() {
            @Override public ChatModel getObject(Object... args) { return null; }
            @Override public ChatModel getIfAvailable() { return null; }
            @Override public ChatModel getIfUnique() { return null; }
            @Override public ChatModel getObject() { return null; }
        };
    }
}
