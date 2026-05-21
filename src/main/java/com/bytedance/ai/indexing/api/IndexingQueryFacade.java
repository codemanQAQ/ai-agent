package com.bytedance.ai.indexing.api;

public interface IndexingQueryFacade {

    RagIndexJobView getIndexJob(Long documentId);

    RagIndexTimelineView getIndexTimeline(Long documentId);
}
