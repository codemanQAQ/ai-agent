package com.bytedance.ai.agent.memory;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.api.Slot;
import com.bytedance.ai.agent.persistence.AgentTurnPersistenceService;
import com.bytedance.ai.agent.persistence.AgentTurnRecord;
import com.bytedance.ai.retrieval.spi.AgentTurnConversationState.ConversationTurn;
import com.bytedance.ai.shared.support.RagJsonCodec;
import com.bytedance.ai.shared.support.RagLogHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 把"已开始的 {@link AgentTurnConversationState} 历史"和"agent_turn 上一轮 cards_emitted /
 * intent / slots"合成一份 {@link ConversationMemory}，供主流程下游使用。
 *
 * <p>不直接访问 {@code rag_*} 数据库，所有 retrieval 侧数据由 {@code AgentConversationSpi}
 * 通过 {@code beginTurn} 返回的 history 传进来；agent_turn 走自家 service。
 */
@Component
public class ConversationMemoryLoader {

    /**
     * 最近 N 轮 = 6 轮 user/assistant 消息（=3 完整往返）。
     * 与 plan 中 "ConversationMemory：最近 6 轮消息" 对齐。
     */
    public static final int RECENT_TURN_LIMIT = 6;

    private static final Logger log = LoggerFactory.getLogger(ConversationMemoryLoader.class);

    private final AgentTurnPersistenceService agentTurnPersistenceService;
    private final RagJsonCodec jsonCodec;

    public ConversationMemoryLoader(
            AgentTurnPersistenceService agentTurnPersistenceService,
            RagJsonCodec jsonCodec
    ) {
        this.agentTurnPersistenceService = agentTurnPersistenceService;
        this.jsonCodec = jsonCodec;
    }

    /**
     * @param conversationId   会话业务 id
     * @param history          {@code AgentTurnConversationState.history} 原样转发
     * @param summary          会话级摘要文本；W2-AGT-04 接入后非空
     */
    public ConversationMemory load(
            String conversationId,
            List<ConversationTurn> history,
            Optional<String> summary
    ) {
        List<ConversationTurn> trimmedHistory = trimRecent(history);
        Optional<AgentTurnRecord> lastTurn = findLastSucceededTurn(conversationId);
        List<String> lastSpuRefs = lastTurn.map(this::extractSpuRefs).orElse(List.of());
        Optional<IntentType> lastIntent = lastTurn.flatMap(this::parseIntent);
        Optional<Slot> lastSlots = lastTurn.flatMap(this::parseSlots);
        return new ConversationMemory(trimmedHistory, summary, lastSpuRefs, lastIntent, lastSlots);
    }

    private List<ConversationTurn> trimRecent(List<ConversationTurn> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, history.size() - RECENT_TURN_LIMIT);
        return List.copyOf(history.subList(from, history.size()));
    }

    private Optional<AgentTurnRecord> findLastSucceededTurn(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return Optional.empty();
        }
        return agentTurnPersistenceService.findRecentByConversationId(conversationId, 5).stream()
                .filter(record -> "SUCCEEDED".equals(record.status()))
                .findFirst();
    }

    @SuppressWarnings("unchecked")
    private List<String> extractSpuRefs(AgentTurnRecord record) {
        String json = record.cardsEmitted();
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            Object parsed = jsonCodec.readMap("{\"cards\":" + json + "}").get("cards");
            if (!(parsed instanceof List<?> rawList)) {
                return List.of();
            }
            LinkedHashSet<String> refs = new LinkedHashSet<>();
            for (Object item : rawList) {
                if (item instanceof Map<?, ?> map) {
                    Object ref = map.get("refId");
                    Object externalRef = map.get("externalRef");
                    if (externalRef instanceof String externalRefValue && StringUtils.hasText(externalRefValue)) {
                        refs.add(externalRefValue.trim());
                    } else if (ref instanceof String refValue && StringUtils.hasText(refValue)) {
                        refs.add(refValue.trim());
                    }
                }
            }
            return new ArrayList<>(refs);
        } catch (RuntimeException exception) {
            log.debug("decode cards_emitted failed for turnId={}: {}", record.turnId(), RagLogHelper.errorSummary(exception));
            return List.of();
        }
    }

    private Optional<IntentType> parseIntent(AgentTurnRecord record) {
        if (!StringUtils.hasText(record.intent())) {
            return Optional.empty();
        }
        try {
            return Optional.of(IntentType.valueOf(record.intent()));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private Optional<Slot> parseSlots(AgentTurnRecord record) {
        String json = record.slotsJson();
        if (!StringUtils.hasText(json)) {
            return Optional.empty();
        }
        try {
            return Optional.of(jsonCodec.read(json, Slot.class));
        } catch (RuntimeException exception) {
            log.debug("decode slots_json failed for turnId={}: {}", record.turnId(), RagLogHelper.errorSummary(exception));
            return Optional.empty();
        }
    }
}
