package com.bytedance.ai.agent.memory;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.persistence.AgentTurnPersistenceService;
import com.bytedance.ai.agent.persistence.AgentTurnRecord;
import com.bytedance.ai.agent.persistence.AgentTurnRepository;
import com.bytedance.ai.retrieval.spi.AgentTurnConversationState.ConversationTurn;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationMemoryLoaderTests {

    private final RagJsonCodec jsonCodec = new RagJsonCodec(JsonMapper.builder().build());

    @Test
    void loadsHistorySummaryAndLastTurnSpuRefs() {
        StubRepo repo = new StubRepo();
        repo.appendSucceeded("turn-1", "c1", "FILTER_BY_ATTR",
                "{\"must\":[\"轻便\"],\"brands\":[],\"mustNot\":[]}",
                "[{\"refId\":\"#1\",\"externalRef\":\"SPU-9\"},{\"refId\":\"#2\",\"externalRef\":\"SPU-7\"}]");
        ConversationMemoryLoader loader = new ConversationMemoryLoader(new AgentTurnPersistenceService(repo, jsonCodec), jsonCodec);

        List<ConversationTurn> history = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            history.add(new ConversationTurn(i % 2 == 0 ? "user" : "assistant", "m" + i));
        }
        ConversationMemory memory = loader.load("c1", history, Optional.of("过往用户偏好通勤包"));

        assertThat(memory.recentMessages()).hasSize(ConversationMemoryLoader.RECENT_TURN_LIMIT);
        assertThat(memory.recentMessages().getFirst().content()).isEqualTo("m2");
        assertThat(memory.summary()).contains("过往用户偏好通勤包");
        assertThat(memory.lastTurnSpuRefs()).containsExactly("SPU-9", "SPU-7");
        assertThat(memory.lastTurnIntent()).contains(IntentType.FILTER_BY_ATTR);
        assertThat(memory.lastTurnSlots()).isPresent();
    }

    @Test
    void emptyOnFirstTurn() {
        ConversationMemoryLoader loader = new ConversationMemoryLoader(
                new AgentTurnPersistenceService(new StubRepo(), jsonCodec), jsonCodec);
        ConversationMemory memory = loader.load("c-new", List.of(), Optional.empty());
        assertThat(memory.hasPriorTurn()).isFalse();
        assertThat(memory.lastTurnSpuRefs()).isEmpty();
    }

    private static class StubRepo implements AgentTurnRepository {
        private final Map<String, AgentTurnRecord> rows = new LinkedHashMap<>();

        void appendSucceeded(String turnId, String conversationId, String intent, String slotsJson, String cardsJson) {
            rows.put(turnId, new AgentTurnRecord(
                    (long) rows.size() + 1,
                    turnId, "corr-" + turnId, "u", conversationId, null, null, null,
                    "SUCCEEDED", "msg", intent, "rule_l1", 0.9, slotsJson, "[]", cardsJson,
                    true, "ans", null, null, 100, null, null,
                    OffsetDateTime.now(), OffsetDateTime.now()
            ));
        }

        @Override public void createRunning(String t, String c, String u, String cv, String r, String m) {}
        @Override public Optional<AgentTurnRecord> findByTurnId(String turnId) { return Optional.ofNullable(rows.get(turnId)); }
        @Override public Optional<AgentTurnRecord> findByRequestId(String u, String c, String r) { return Optional.empty(); }
        @Override public List<AgentTurnRecord> findRecentByConversationId(String conversationId, int limit) {
            return rows.values().stream().filter(r -> r.conversationId().equals(conversationId)).limit(limit).toList();
        }
        @Override public void attachConversationMessages(String t, String u, String a) {}
        @Override public void recordIntent(String t, String i, String s, Double c, String sj) {}
        @Override public void recordToolState(String t, String tc, String ce) {}
        @Override public void markSucceeded(String t, String a, Boolean g, Integer ti, Integer to, Integer l) {}
        @Override public void markFailed(String t, String c, String m, Integer l) {}
    }
}
