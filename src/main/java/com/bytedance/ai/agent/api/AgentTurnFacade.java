package com.bytedance.ai.agent.api;

import reactor.core.publisher.Flux;

public interface AgentTurnFacade {

    Flux<AgentStreamEvent> turnStream(AgentTurnRequest request);
}
