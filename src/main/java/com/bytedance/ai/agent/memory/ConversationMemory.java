package com.bytedance.ai.agent.memory;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.api.Slot;
import com.bytedance.ai.retrieval.spi.AgentTurnConversationState.ConversationTurn;

import java.util.List;
import java.util.Optional;

/**
 * 单次 agent 编排可见的多轮记忆快照。
 *
 * <p>由 {@link ConversationMemoryLoader} 在 {@code AgentTurnService} 主流程开始时构造一次，
 * 后续 {@code IntentClassifier} / {@code SlotExtractor} / {@code SearchProductsToolCallback}
 * / {@code AgentAnswerGenerator} 共享同一个对象，避免重复查 DB。
 *
 * @param recentMessages    最近 N 轮成功消息（user / assistant 交替），最新在后
 * @param summary           对更早历史的 LLM 摘要文本（{@link ConversationSummarizer} 写入）；可为空
 * @param lastTurnSpuRefs   上一轮命中并展示给用户的 SPU externalRef 列表（用于 REFINE 子集重排）
 * @param lastTurnIntent    上一轮判定的意图；可为空（首轮 / 历史缺失）
 * @param lastTurnSlots     上一轮抽到的槽位；可为空
 */
public record ConversationMemory(
        List<ConversationTurn> recentMessages,
        Optional<String> summary,
        List<String> lastTurnSpuRefs,
        Optional<IntentType> lastTurnIntent,
        Optional<Slot> lastTurnSlots
) {

    public ConversationMemory {
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
        summary = summary == null ? Optional.empty() : summary;
        lastTurnSpuRefs = lastTurnSpuRefs == null ? List.of() : List.copyOf(lastTurnSpuRefs);
        lastTurnIntent = lastTurnIntent == null ? Optional.empty() : lastTurnIntent;
        lastTurnSlots = lastTurnSlots == null ? Optional.empty() : lastTurnSlots;
    }

    public static ConversationMemory empty() {
        return new ConversationMemory(List.of(), Optional.empty(), List.of(), Optional.empty(), Optional.empty());
    }

    public boolean hasPriorTurn() {
        return !recentMessages.isEmpty() || !lastTurnSpuRefs.isEmpty();
    }
}
