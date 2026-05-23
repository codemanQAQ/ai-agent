package com.bytedance.ai.agent.intent;

import com.bytedance.ai.agent.memory.ConversationMemory;

public interface IntentClassifier {

    /**
     * 仅看本轮 message 的兜底分类入口；保留方便测试与无历史场景。
     */
    default IntentClassification classify(String message) {
        return classify(message, ConversationMemory.empty());
    }

    /**
     * 在多轮记忆下分类；REFINE 等需要历史上下文的意图必须用这个入口。
     */
    IntentClassification classify(String message, ConversationMemory memory);
}
