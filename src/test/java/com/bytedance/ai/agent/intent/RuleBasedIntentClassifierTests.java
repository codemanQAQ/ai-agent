package com.bytedance.ai.agent.intent;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.memory.ConversationMemory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedIntentClassifierTests {

    private final RuleBasedIntentClassifier classifier = new RuleBasedIntentClassifier();

    @Test
    void detectsOutOfScopeByRuleL1() {
        assertIntent("帮我写代码实现快排", IntentType.OUT_OF_SCOPE, 0.95d, "rule_l1");
        assertIntent("讲笑话", IntentType.OUT_OF_SCOPE, 0.95d, "rule_l1");
        assertIntent("今天股票新闻怎么样", IntentType.OUT_OF_SCOPE, 0.95d, "rule_l1");
    }

    @Test
    void detectsPriceFiltersByRuleL1() {
        assertIntent("推荐 300 元以下的双肩包", IntentType.FILTER_BY_ATTR, 0.9d, "rule_l1");
        assertIntent("低于 500 的蓝牙耳机", IntentType.FILTER_BY_ATTR, 0.9d, "rule_l1");
        assertIntent("500-1000 元的行李箱", IntentType.FILTER_BY_ATTR, 0.9d, "rule_l1");
        assertIntent("预算 200 块的鼠标", IntentType.FILTER_BY_ATTR, 0.9d, "rule_l1");
    }

    @Test
    void detectsRecommendationByRuleL2() {
        assertIntent("推荐适合油皮的洗面奶", IntentType.RECOMMEND_VAGUE, 0.85d, "rule_l2");
        assertIntent("帮我找通勤电脑包", IntentType.RECOMMEND_VAGUE, 0.85d, "rule_l2");
        assertIntent("有没有防晒霜", IntentType.RECOMMEND_VAGUE, 0.85d, "rule_l2");
    }

    @Test
    void detectsCompareByRuleL1() {
        assertIntent("A vs B 哪个保湿", IntentType.COMPARE, 0.9d, "rule_l1");
        assertIntent("A B C 性价比", IntentType.COMPARE, 0.9d, "rule_l1");
        assertIntent("比较一下这两款", IntentType.COMPARE, 0.9d, "rule_l1");
    }

    @Test
    void fallsBackToVagueRecommendation() {
        assertIntent("通勤双肩包", IntentType.RECOMMEND_VAGUE, 0.5d, "fallback");
        assertIntent("", IntentType.RECOMMEND_VAGUE, 0.5d, "fallback");
        assertIntent(null, IntentType.RECOMMEND_VAGUE, 0.5d, "fallback");
    }

    @Test
    void outOfScopeWinsBeforeOtherRules() {
        assertIntent("帮我写代码找 300 元以下商品", IntentType.OUT_OF_SCOPE, 0.95d, "rule_l1");
        assertIntent("帮我写代码比较 A vs B", IntentType.OUT_OF_SCOPE, 0.95d, "rule_l1");
    }

    @Test
    void detectsRefineOnlyWhenPreviousCardsExist() {
        ConversationMemory memory = new ConversationMemory(
                List.of(),
                Optional.empty(),
                null,
                null,
                List.of("SPU-9"),
                Optional.empty(),
                Optional.empty()
        );

        IntentClassification refine = classifier.classify("这些里面再便宜一点", memory);
        IntentClassification noMemory = classifier.classify("这些里面再便宜一点", ConversationMemory.empty());

        assertThat(refine.intent()).isEqualTo(IntentType.REFINE);
        assertThat(refine.confidence()).isEqualTo(0.9d);
        assertThat(refine.source()).isEqualTo("rule_l1");
        assertThat(noMemory.intent()).isNotEqualTo(IntentType.REFINE);
    }

    private void assertIntent(String message, IntentType intent, double confidence, String source) {
        IntentClassification classification = classifier.classify(message);
        assertThat(classification.intent()).isEqualTo(intent);
        assertThat(classification.confidence()).isEqualTo(confidence);
        assertThat(classification.source()).isEqualTo(source);
    }
}
