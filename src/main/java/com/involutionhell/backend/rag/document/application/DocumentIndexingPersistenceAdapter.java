package com.involutionhell.backend.rag.document.application;

import com.involutionhell.backend.rag.document.persistence.RagDocumentRecord;
import com.involutionhell.backend.rag.document.persistence.RagDocumentRepository;
import com.involutionhell.backend.rag.document.spi.DocumentIndexingSpi;
import com.involutionhell.backend.rag.document.spi.DocumentIndexingView;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 把 document 模块内部 persistence 适配为对 indexing 暴露的 SPI。
 *
 * <p>该适配器是 document 模块边界的一部分：外部模块只依赖 {@link DocumentIndexingSpi}，
 * 真实的 repository 细节仍然留在 document 模块内部。
 */
@Service
class DocumentIndexingPersistenceAdapter implements DocumentIndexingSpi {

    private final RagDocumentRepository documentRepository;

    DocumentIndexingPersistenceAdapter(RagDocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Override
    public Optional<DocumentIndexingView> findById(Long id) {
        return documentRepository.findById(id).map(this::toView);
    }

    @Override
    public List<DocumentIndexingView> findPendingBefore(OffsetDateTime cutoff, int limit) {
        return documentRepository.findPendingBefore(cutoff, limit).stream().map(this::toView).toList();
    }

    @Override
    public List<DocumentIndexingView> findProcessingBefore(OffsetDateTime cutoff, int limit) {
        return documentRepository.findProcessingBefore(cutoff, limit).stream().map(this::toView).toList();
    }

    @Override
    public List<DocumentIndexingView> findFailedBefore(OffsetDateTime cutoff, int limit) {
        return documentRepository.findFailedBefore(cutoff, limit).stream().map(this::toView).toList();
    }

    @Override
    public List<DocumentIndexingView> findDeletingBefore(OffsetDateTime cutoff, int limit) {
        return documentRepository.findDeletingBefore(cutoff, limit).stream().map(this::toView).toList();
    }

    @Override
    public void markPending(Long id) {
        documentRepository.markPending(id);
    }

    @Override
    public void requeue(Long id, String note) {
        documentRepository.requeue(id, note);
    }

    @Override
    public void markProcessing(Long id) {
        documentRepository.markProcessing(id);
    }

    @Override
    public void markIndexed(Long id, Long indexedGeneration, int chunkCount, OffsetDateTime indexedAt) {
        documentRepository.markIndexed(id, indexedGeneration, chunkCount, indexedAt);
    }

    @Override
    public void markFailed(Long id, String errorMessage) {
        documentRepository.markFailed(id, errorMessage);
    }

    @Override
    public void markDeleting(Long id, String note) {
        documentRepository.markDeleting(id, note);
    }

    @Override
    public void deleteById(Long id) {
        documentRepository.deleteById(id);
    }

    private DocumentIndexingView toView(RagDocumentRecord record) {
        return new DocumentIndexingView(
                record.id(),
                record.sourceType(),
                record.sourceUri(),
                record.externalRef(),
                record.title(),
                record.content(),
                record.contentSha256(),
                record.indexedGeneration(),
                record.status(),
                record.chunkCount(),
                record.attemptCount(),
                record.metadata(),
                record.lastError(),
                record.lastAttemptedAt(),
                record.indexedAt(),
                record.createdAt(),
                record.updatedAt()
        );
    }
}
