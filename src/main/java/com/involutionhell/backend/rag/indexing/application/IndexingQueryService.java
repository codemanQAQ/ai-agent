package com.involutionhell.backend.rag.indexing.application;

import com.involutionhell.backend.rag.document.spi.DocumentIndexingSpi;
import com.involutionhell.backend.rag.document.spi.DocumentIndexingView;
import com.involutionhell.backend.rag.indexing.api.IndexingQueryFacade;
import com.involutionhell.backend.rag.indexing.api.RagIndexJobView;
import com.involutionhell.backend.rag.indexing.api.RagIndexTimelineView;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexJobRepository;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexJobRecord;
import com.involutionhell.backend.rag.indexing.application.RagIndexTimelineService;
import org.springframework.stereotype.Service;

@Service
class IndexingQueryService implements IndexingQueryFacade {

    private final DocumentIndexingSpi documentIndexingSpi;
    private final RagIndexJobRepository indexJobRepository;
    private final RagIndexTimelineService indexTimelineService;

    IndexingQueryService(
            DocumentIndexingSpi documentIndexingSpi,
            RagIndexJobRepository indexJobRepository,
            RagIndexTimelineService indexTimelineService
    ) {
        this.documentIndexingSpi = documentIndexingSpi;
        this.indexJobRepository = indexJobRepository;
        this.indexTimelineService = indexTimelineService;
    }

    @Override
    public RagIndexJobView getIndexJob(Long documentId) {
        DocumentIndexingView document = documentIndexingSpi.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("RAG 文档不存在: " + documentId));
        RagIndexJobRecord job = indexJobRepository.findByDocumentIdAndContentSha256(documentId, document.contentSha256())
                .orElse(null);
        if (job == null) {
            return null;
        }

        return new RagIndexJobView(
                job.id(),
                job.documentId(),
                job.contentSha256(),
                job.status(),
                job.stage(),
                job.version(),
                job.lastEvent(),
                job.attemptCount(),
                job.targetGeneration(),
                job.messageId(),
                job.lastError(),
                job.startedAt(),
                job.finishedAt(),
                job.createdAt(),
                job.updatedAt()
        );
    }

    @Override
    public RagIndexTimelineView getIndexTimeline(Long documentId) {
        return indexTimelineService.getTimeline(documentId);
    }
}
