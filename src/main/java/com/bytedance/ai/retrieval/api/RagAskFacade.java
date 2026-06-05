package com.bytedance.ai.retrieval.api;

import reactor.core.publisher.Flux;

public interface RagAskFacade {

    Flux<RagAskStreamEvent> askStream(RagAskRequest request);
}
