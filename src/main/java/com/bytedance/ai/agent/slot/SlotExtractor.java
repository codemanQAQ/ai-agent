package com.bytedance.ai.agent.slot;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.api.Slot;
import com.bytedance.ai.agent.memory.ConversationMemory;

public interface SlotExtractor {

    /**
     * 兜底入口：不看历史。保留供独立测试。
     */
    default Slot extract(String message, IntentType intent) {
        return extract(message, intent, ConversationMemory.empty());
    }

    /**
     * 带历史的入口：REFINE 时会复用 {@link ConversationMemory#lastTurnSlots()} 作为基线，
     * 仅在其上叠加新约束（"再便宜一点 / 换成 X 品牌"）。
     */
    Slot extract(String message, IntentType intent, ConversationMemory memory);
}
