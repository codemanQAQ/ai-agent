package com.involutionhell.backend.rag.retrieval.api;

public interface RagAskFacade {

    RagAnswerResponse ask(RagAskRequest request);
}
