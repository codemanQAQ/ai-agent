package com.involutionhell.backend.rag.indexing.api;

public interface IndexingQueryFacade {

    RagIndexJobView getIndexJob(Long documentId);

    RagIndexTimelineView getIndexTimeline(Long documentId);
}
