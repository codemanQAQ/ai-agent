package com.bytedance.ai.graph.productrecommend;

import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogSpuView;
import com.bytedance.ai.graph.session.UnifiedQueryContext;
import com.bytedance.ai.indexing.api.IndexingChunkQueryFacade;
import com.bytedance.ai.indexing.api.RagChunkSearchView;
import com.bytedance.ai.shared.metadata.RagChunkMetadataHelper;
import com.bytedance.ai.shared.metadata.RagChunkMetadataView;
import com.bytedance.ai.shared.metadata.RagChunkType;
import com.bytedance.ai.shared.metadata.RagSearchFilter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RagChunkProductRecallService implements ProductRecallService {

    private final IndexingChunkQueryFacade indexingChunkQueryFacade;
    private final CatalogQueryFacade catalogQueryFacade;
    private final RagChunkMetadataHelper metadataHelper;

    public RagChunkProductRecallService(
            IndexingChunkQueryFacade indexingChunkQueryFacade,
            CatalogQueryFacade catalogQueryFacade,
            RagChunkMetadataHelper metadataHelper
    ) {
        this.indexingChunkQueryFacade = indexingChunkQueryFacade;
        this.catalogQueryFacade = catalogQueryFacade;
        this.metadataHelper = metadataHelper;
    }

    @Override
    public ProductRecallSource source() {
        return ProductRecallSource.RAG_CHUNK;
    }

    @Override
    public List<ProductRecallCandidate> recall(ProductRecallRequest request) {
        UnifiedQueryContext context = request.queryContext();
        if (context == null || !StringUtils.hasText(context.queryText())) {
            return List.of();
        }
        Set<String> tokens = ProductRecallTextTokenizer.tokens(context.queryText());
        if (tokens.isEmpty()) {
            return List.of();
        }
        List<RagChunkType> preferredChunkTypes = preferredChunkTypes(request);
        if (preferredChunkTypes.isEmpty()) {
            return recallWithFilter(tokens, request.limit(), scopeFilter(context, List.of()));
        }

        List<ProductRecallCandidate> preferred = recallWithFilter(tokens, request.limit(), scopeFilter(context, preferredChunkTypes));
        if (preferred.size() >= request.limit()) {
            return preferred;
        }

        List<ProductRecallCandidate> fallback = recallWithFilter(tokens, request.limit(), scopeFilter(context, List.of()));
        return mergeByProductId(preferred, fallback, request.limit());
    }

    private List<ProductRecallCandidate> recallWithFilter(Set<String> tokens, int limit, RagSearchFilter filter) {
        return indexingChunkQueryFacade.findKeywordCandidates(tokens, limit * 3, filter).stream()
                .map(this::toCandidate)
                .flatMap(Optional::stream)
                .limit(limit)
                .toList();
    }

    private Optional<ProductRecallCandidate> toCandidate(RagChunkSearchView chunk) {
        RagChunkMetadataView metadata = metadataHelper.parse(String.valueOf(chunk.metadata()));
        String externalRef = firstText(chunk.externalRef(), text(metadata.raw().get("externalRef")), text(metadata.raw().get("productId")));
        if (!StringUtils.hasText(externalRef)) {
            return Optional.empty();
        }
        return catalogQueryFacade.findSpuByExternalRef(externalRef)
                .map(spu -> CatalogProductCandidateMapper.toCandidate(
                        spu,
                        source(),
                        0.65d,
                        matchedSlots(metadata),
                        List.of(toEvidence(chunk, metadata, spu))
                ));
    }

    private ProductRecallEvidence toEvidence(RagChunkSearchView chunk, RagChunkMetadataView metadata, CatalogSpuView spu) {
        Map<String, Object> evidenceMetadata = new LinkedHashMap<>(metadata.raw());
        evidenceMetadata.put("documentId", chunk.documentId());
        evidenceMetadata.put("chunkIndex", chunk.chunkIndex());
        evidenceMetadata.put("sourceUri", chunk.sourceUri());
        return new ProductRecallEvidence(
                source(),
                metadata.chunkType().name().toLowerCase(),
                chunk.title(),
                chunk.chunkText(),
                chunk.chunkId() == null ? null : String.valueOf(chunk.chunkId()),
                text(metadata.raw().get("parentChunkId")),
                spu.externalRef(),
                evidenceMetadata
        );
    }

    private Map<String, Object> matchedSlots(RagChunkMetadataView metadata) {
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put("chunkType", metadata.chunkType().name());
        if (!metadata.headingPath().isEmpty()) {
            slots.put("headingPath", metadata.headingPath());
        }
        return Map.copyOf(slots);
    }

    private RagSearchFilter scopeFilter(UnifiedQueryContext context, List<RagChunkType> chunkTypes) {
        return RagSearchFilter.of(
                "catalog-spu",
                List.of(),
                null,
                stringList(context.negativeConstraints().get("tags")),
                stringList(context.negativeConstraints().get("brand")),
                stringList(context.negativeConstraints().get("ingredients")),
                context.scope().externalRefs(),
                context.scope().productIds(),
                context.scope().catalogSpuIds(),
                chunkTypes
        );
    }

    private List<RagChunkType> preferredChunkTypes(ProductRecallRequest request) {
        if (request.plan() == null || request.plan().subScene() != ProductRecommendSubScene.DETAIL_FAQ_REVIEW_ANSWER) {
            return List.of();
        }
        return List.of(
                RagChunkType.OFFICIAL_FAQ,
                RagChunkType.USER_REVIEW,
                RagChunkType.REVIEW_SUMMARY,
                RagChunkType.MARKETING_DESCRIPTION
        );
    }

    private List<ProductRecallCandidate> mergeByProductId(
            List<ProductRecallCandidate> preferred,
            List<ProductRecallCandidate> fallback,
            int limit
    ) {
        List<ProductRecallCandidate> merged = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        appendUnique(merged, seen, preferred, limit);
        appendUnique(merged, seen, fallback, limit);
        return List.copyOf(merged);
    }

    private void appendUnique(
            List<ProductRecallCandidate> target,
            Set<String> seen,
            List<ProductRecallCandidate> source,
            int limit
    ) {
        for (ProductRecallCandidate candidate : source) {
            if (target.size() >= limit) {
                return;
            }
            String key = firstText(candidate.productId(), candidate.externalRef(), candidate.title());
            if (seen.add(key)) {
                target.add(candidate);
            }
        }
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item != null && StringUtils.hasText(String.valueOf(item)))
                    .map(String::valueOf)
                    .toList();
        }
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            return List.of(String.valueOf(value));
        }
        return List.of();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }
}
