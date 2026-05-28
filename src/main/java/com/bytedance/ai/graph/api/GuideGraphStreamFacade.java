package com.bytedance.ai.graph.api;

import com.bytedance.ai.graph.api.AgentStreamEvent;
import reactor.core.publisher.Flux;

public interface GuideGraphStreamFacade {

    Flux<AgentStreamEvent> turnStream(GuideGraphRequest request);
}
