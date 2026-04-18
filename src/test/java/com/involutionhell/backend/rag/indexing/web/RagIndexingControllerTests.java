package com.involutionhell.backend.rag.indexing.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.involutionhell.backend.rag.indexing.api.IndexingQueryFacade;
import com.involutionhell.backend.rag.indexing.api.RagIndexJobView;
import com.involutionhell.backend.rag.indexing.api.RagIndexOutboxView;
import com.involutionhell.backend.rag.indexing.api.RagIndexTimelineDocumentView;
import com.involutionhell.backend.rag.indexing.api.RagIndexTimelineView;
import com.involutionhell.backend.rag.indexing.api.RagIndexTransitionView;
import com.involutionhell.backend.rag.infrastructure.web.RagExceptionHandler;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RagIndexingControllerTests {

    @Test
    void indexTimelineEndpointReturnsOutboxMessageId() throws Exception {
        OffsetDateTime now = OffsetDateTime.parse("2026-04-19T10:15:30+10:00");
        IndexingQueryFacade facade = new FixedIndexingQueryFacade(new RagIndexTimelineView(
                new RagIndexTimelineDocumentView(
                        42L,
                        "markdown",
                        "docs/timeline-api.md",
                        "timeline-api-1",
                        "Timeline API Test",
                        "FAILED",
                        0,
                        1,
                        "outbox stuck",
                        now,
                        null,
                        now,
                        now
                ),
                new RagIndexJobView(
                        7L,
                        42L,
                        "sha-api-1",
                        "FAILED",
                        "DISPATCHING",
                        3L,
                        "DISPATCH",
                        2,
                        1L,
                        "job-message-1",
                        "producer acknowledged but markSent failed",
                        now,
                        now,
                        now,
                        now
                ),
                new RagIndexOutboxView(
                        9L,
                        42L,
                        "sha-api-1",
                        "INDEX_DOCUMENT",
                        "SENDING",
                        2,
                        "producer-message-1",
                        "markSent failed",
                        now,
                        null,
                        null,
                        now,
                        now
                ),
                List.<RagIndexTransitionView>of()
        ));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new RagIndexingController(facade))
                .setControllerAdvice(new RagExceptionHandler())
                .build();

        mockMvc.perform(get("/public/rag/documents/index-timeline/{documentId}", 42L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.document.id").value(42))
                .andExpect(jsonPath("$.data.job.messageId").value("job-message-1"))
                .andExpect(jsonPath("$.data.outbox.messageId").value("producer-message-1"));
    }

    private record FixedIndexingQueryFacade(RagIndexTimelineView timeline) implements IndexingQueryFacade {

        @Override
        public RagIndexJobView getIndexJob(Long documentId) {
            return timeline.job();
        }

        @Override
        public RagIndexTimelineView getIndexTimeline(Long documentId) {
            return timeline;
        }
    }
}
