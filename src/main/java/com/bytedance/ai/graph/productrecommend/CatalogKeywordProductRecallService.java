package com.bytedance.ai.graph.productrecommend;

import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogSpuView;
import com.bytedance.ai.graph.session.UnifiedQueryContext;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CatalogKeywordProductRecallService implements ProductRecallService {

    private final CatalogQueryFacade catalogQueryFacade;

    public CatalogKeywordProductRecallService(CatalogQueryFacade catalogQueryFacade) {
        this.catalogQueryFacade = catalogQueryFacade;
    }

    @Override
    public ProductRecallSource source() {
        return ProductRecallSource.CATALOG_KEYWORD;
    }

    @Override
    public List<ProductRecallCandidate> recall(ProductRecallRequest request) {
        UnifiedQueryContext context = request.queryContext();
        if (context == null) {
            return List.of();
        }
        // 优先用抽取出的结构化槽位（category/brand）做关键词；整句 queryText 仅作兜底，
        // 否则 LIKE '%整句%' 几乎匹配不到任何商品。
        String keyword = ProductRecallTextTokenizer.bestKeyword(
                text(context.positiveConstraints().get("category")),
                Arrays.asList(text(context.positiveConstraints().get("brand")), context.queryText())
        );
        if (keyword == null) {
            return List.of();
        }
        List<CatalogSpuView> spus = catalogQueryFacade.searchActiveSpus(keyword, request.limit());
        return spus.stream()
                .map(spu -> CatalogProductCandidateMapper.toCandidate(
                        spu,
                        source(),
                        keywordScore(spu, keyword),
                        Map.of("keyword", keyword),
                        List.of(new ProductRecallEvidence(
                                source(),
                                "catalog_keyword",
                                spu.title(),
                                "Catalog keyword matched: " + keyword,
                                null,
                                null,
                                productId(spu),
                                Map.of("keyword", keyword)
                        ))
                ))
                .toList();
    }

    private double keywordScore(CatalogSpuView spu, String keyword) {
        String normalized = keyword == null ? "" : keyword.toLowerCase();
        double score = 0.5d;
        if (spu.title() != null && spu.title().toLowerCase().contains(normalized)) {
            score += 0.25d;
        }
        if (spu.brand() != null && spu.brand().toLowerCase().contains(normalized)) {
            score += 0.15d;
        }
        if (spu.categoryPath() != null && spu.categoryPath().toLowerCase().contains(normalized)) {
            score += 0.1d;
        }
        return score;
    }

    private String productId(CatalogSpuView spu) {
        return spu.externalRef() == null || spu.externalRef().isBlank() ? String.valueOf(spu.id()) : spu.externalRef();
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }
}
