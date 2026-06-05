package com.bytedance.ai.graph.intent;

import com.bytedance.ai.graph.intent.config.IntentLlmProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MainIntentPromptFactoryTest {

    @Test
    void promptContainsLightweightRoutingRulesAndJsonSchema() {
        MainIntentPromptFactory factory = new MainIntentPromptFactory(new IntentLlmProperties());
        factory.init();
        String prompt = factory.build("帮我推荐一款适合办公使用的商品", "");

        assertThat(prompt).contains("understand the user's current turn in one pass");
        assertThat(prompt).contains("Do not output CLARIFY as intent");
        assertThat(prompt).contains("If the user writes Chinese, use Chinese values");
        assertThat(prompt).contains("same natural language as the user's message");
        assertThat(prompt).contains("positiveConstraints");
        assertThat(prompt).contains("negativeConstraints");
        assertThat(prompt).contains("clarifyQuestion");
        assertThat(prompt).contains("generate clarifyQuestion in the same language as the user");
        assertThat(prompt).contains("missingSlots");
        assertThat(prompt).contains("FUZZY_RECOMMEND");
        assertThat(prompt).contains("suitability, audience, or scenario");
        assertThat(prompt).contains("hard structured filters");
        assertThat(prompt).contains("PHOTO_SEARCH");
        assertThat(prompt).contains("ORDER_MANAGE");
        assertThat(prompt).contains("帮我推荐一款适合办公使用的商品");
        assertThat(prompt).doesNotContain("{{userMessage}}", "{{conversationMemory}}");
        assertThat(prompt)
                .contains("FUZZY_RECOMMEND")
                .contains("CONDITION_FILTER")
                .contains("MULTI_TURN_REFINE")
                .contains("PRODUCT_COMPARE")
                .contains("NEGATIVE_CONSTRAINT")
                .contains("SCENE_BUNDLE_RECOMMEND")
                .contains("PHOTO_SEARCH")
                .contains(MainIntent.CART_MANAGE.name())
                .contains("ORDER_MANAGE")
                .contains(MainIntent.OTHER.name());
        assertThat(prompt)
                .doesNotContain(MainIntent.PRICE_QUERY.name())
                .doesNotContain(MainIntent.INVENTORY_QUERY.name())
                .doesNotContain(MainIntent.REVIEW_SUMMARY.name())
                .doesNotContain(MainIntent.PRODUCT_DETAIL_QUERY.name())
                .doesNotContain(MainIntent.POLICY_QA.name())
                .doesNotContain("- " + MainIntent.CLARIFY.name())
                .doesNotContain(MainIntent.SMALL_TALK.name())
                .doesNotContain(MainIntent.UNKNOWN.name());
        assertThat(prompt)
                .contains("\"intent\"")
                .contains("\"confidence\"")
                .contains("\"needClarify\"")
                .contains("\"writeAction\"")
                .contains("\"targetWorkflow\"")
                .contains("\"subIntent\"")
                .contains("\"reason\"")
                .contains("\"clarifyQuestion\"")
                .contains("\"slots\"")
                .contains("\"action\"")
                .contains("\"type\"")
                .contains("\"targetRef\"")
                .contains("\"skuSpec\"")
                .contains("\"orderRef\"")
                .contains("\"missingSlots\"")
                .contains("\"category\"")
                .contains("\"subCategory\"")
                .contains("\"priceMax\"")
                .contains("\"audience\"")
                .contains("\"usageContext\"")
                .contains("\"productRefs\"")
                .contains("\"reviewSignals\"");
        assertThat(prompt)
                .doesNotContain("\"positive_constraints\"")
                .doesNotContain("\"negative_constraints\"")
                .doesNotContain("\"price_max\"")
                .doesNotContain("\"usage_context\"")
                .doesNotContain("\"review_signals\"")
                .doesNotContain("\"cartAction\"")
                .doesNotContain("\"orderAction\"")
                .doesNotContain("\"productRef\"")
                .doesNotContain("\"skin_type\"")
                .doesNotContain("\"skinType\"");
        assertThat(prompt)
                .doesNotContain("cleanser")
                .doesNotContain("oily skin")
                .doesNotContain("洗面奶")
                .doesNotContain("油皮");
    }
}
