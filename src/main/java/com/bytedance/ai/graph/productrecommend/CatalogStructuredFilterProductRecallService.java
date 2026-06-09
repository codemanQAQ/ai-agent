package com.bytedance.ai.graph.productrecommend;

import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogSkuView;
import com.bytedance.ai.graph.catalog.api.CatalogSpuView;
import com.bytedance.ai.graph.session.UnifiedQueryContext;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CatalogStructuredFilterProductRecallService implements ProductRecallService {

    private final CatalogQueryFacade catalogQueryFacade;

    public CatalogStructuredFilterProductRecallService(CatalogQueryFacade catalogQueryFacade) {
        this.catalogQueryFacade = catalogQueryFacade;
    }

    @Override
    public ProductRecallSource source() {
        return ProductRecallSource.CATALOG_FILTER;
    }

    @Override
    public List<ProductRecallCandidate> recall(ProductRecallRequest request) {
        UnifiedQueryContext context = request.queryContext();
        if (context == null || context.positiveConstraints().isEmpty()) {
            return List.of();
        }
        Map<String, Object> constraints = context.positiveConstraints();
        // 结构化关键词只取【类目/品牌】，不混 queryText：否则"预算500"这类整句会被当成关键词去 LIKE，
        // 既搜不到东西、又绕过下面的价格浏览兜底。queryText 的模糊召回交给 CATALOG_KEYWORD 源。
        String keyword = ProductRecallTextTokenizer.bestKeyword(
                text(firstPresent(constraints, "category", "categoryPath", "类目")),
                Arrays.asList(text(firstPresent(constraints, "brand", "品牌")))
        );
        int searchLimit = Math.max(request.limit() * 4, request.limit());
        // 无可用类目/品牌关键词，但给了价格（如"送礼 预算500"）：按价格区间浏览兜底，避免直接 0 召回。
        // 仍过 match() 走价格/库存/规格校验，结果按价格升序由仓储给出，下游再融合排序。
        if (!StringUtils.hasText(keyword)) {
            BigDecimal min = decimal(firstPresent(constraints, "priceMin", "minPrice", "价格下限"));
            BigDecimal max = decimal(firstPresent(constraints, "priceMax", "maxPrice", "预算", "价格上限"));
            if (min == null && max == null) {
                return List.of();
            }
            return catalogQueryFacade.browseActiveSpusByPrice(min, max, searchLimit).stream()
                    .map(spu -> match(spu, constraints))
                    .filter(MatchedSpu::matched)
                    .limit(request.limit())
                    .map(match -> CatalogProductCandidateMapper.toCandidate(
                            match.spu(),
                            source(),
                            0.6d + Math.min(match.matchedSlots().size(), 5) * 0.05d,
                            match.matchedSlots(),
                            List.of(new ProductRecallEvidence(
                                    source(),
                                    "catalog_price_browse",
                                    match.spu().title(),
                                    "Catalog price-range browse",
                                    null,
                                    null,
                                    productId(match.spu()),
                                    match.matchedSlots()
                            ))
                    ))
                    .toList();
        }
        return catalogQueryFacade.searchActiveSpus(keyword, searchLimit).stream()
                .map(spu -> match(spu, constraints))
                .filter(MatchedSpu::matched)
                .limit(request.limit())
                .map(match -> CatalogProductCandidateMapper.toCandidate(
                        match.spu(),
                        source(),
                        0.75d + Math.min(match.matchedSlots().size(), 5) * 0.05d,
                        match.matchedSlots(),
                        List.of(new ProductRecallEvidence(
                                source(),
                                "catalog_filter",
                                match.spu().title(),
                                "Catalog structured constraints matched",
                                null,
                                null,
                                productId(match.spu()),
                                match.matchedSlots()
                        ))
                ))
                .toList();
    }

    private MatchedSpu match(CatalogSpuView spu, Map<String, Object> constraints) {
        Map<String, Object> matchedSlots = new LinkedHashMap<>();
        if (!matchesTextConstraint(spu.brand(), firstPresent(constraints, "brand", "品牌"), "brand", matchedSlots)) {
            return MatchedSpu.notMatched(spu);
        }
        if (!matchesTextConstraint(spu.categoryPath(), firstPresent(constraints, "category", "categoryPath", "类目"), "category", matchedSlots)) {
            return MatchedSpu.notMatched(spu);
        }
        if (!matchesPrice(spu, constraints, matchedSlots)) {
            return MatchedSpu.notMatched(spu);
        }
        if (!matchesStock(spu, constraints, matchedSlots)) {
            return MatchedSpu.notMatched(spu);
        }
        if (!matchesSpecs(spu, constraints, matchedSlots)) {
            return MatchedSpu.notMatched(spu);
        }
        return new MatchedSpu(spu, true, Map.copyOf(matchedSlots));
    }

    private boolean matchesTextConstraint(String actual, Object expected, String key, Map<String, Object> matchedSlots) {
        String expectedText = text(expected);
        if (!StringUtils.hasText(expectedText)) {
            return true;
        }
        if (!containsIgnoreCase(actual, expectedText)) {
            return false;
        }
        matchedSlots.put(key, expectedText);
        return true;
    }

    private boolean matchesPrice(CatalogSpuView spu, Map<String, Object> constraints, Map<String, Object> matchedSlots) {
        BigDecimal min = decimal(firstPresent(constraints, "priceMin", "minPrice", "价格下限"));
        BigDecimal max = decimal(firstPresent(constraints, "priceMax", "maxPrice", "预算", "价格上限"));
        BigDecimal priceMin = spu.priceMin();
        BigDecimal priceMax = spu.priceMax() == null ? priceMin : spu.priceMax();
        if (min != null && priceMax != null && priceMax.compareTo(min) < 0) {
            return false;
        }
        if (max != null && priceMin != null && priceMin.compareTo(max) > 0) {
            return false;
        }
        if (min != null) {
            matchedSlots.put("priceMin", min);
        }
        if (max != null) {
            matchedSlots.put("priceMax", max);
        }
        return true;
    }

    private boolean matchesStock(CatalogSpuView spu, Map<String, Object> constraints, Map<String, Object> matchedSlots) {
        Object onlyInStock = firstPresent(constraints, "inStock", "onlyInStock", "有货");
        if (onlyInStock == null || !Boolean.parseBoolean(String.valueOf(onlyInStock))) {
            return true;
        }
        boolean matched = spu.stock() != null && spu.stock() > 0;
        if (matched) {
            matchedSlots.put("inStock", true);
        }
        return matched;
    }

    private boolean matchesSpecs(CatalogSpuView spu, Map<String, Object> constraints, Map<String, Object> matchedSlots) {
        Map<String, Object> specs = map(firstPresent(constraints, "specs", "skuSpec", "规格"));
        if (specs.isEmpty()) {
            return true;
        }
        boolean matched = spu.skus() != null && spu.skus().stream().anyMatch(sku -> skuMatches(sku, specs));
        if (matched) {
            matchedSlots.put("specs", specs);
        }
        return matched;
    }

    private boolean skuMatches(CatalogSkuView sku, Map<String, Object> specs) {
        Map<String, Object> actual = sku.specJson() == null ? Map.of() : sku.specJson();
        for (Map.Entry<String, Object> expected : specs.entrySet()) {
            String expectedValue = text(expected.getValue());
            if (!StringUtils.hasText(expectedValue)) {
                continue;
            }
            boolean matched = actual.entrySet().stream().anyMatch(entry ->
                    containsIgnoreCase(String.valueOf(entry.getKey()), expected.getKey())
                            && containsIgnoreCase(String.valueOf(entry.getValue()), expectedValue));
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private Object firstPresent(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            if (values.containsKey(key) && values.get(key) != null) {
                return values.get(key);
            }
        }
        return null;
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key != null && item != null) {
                result.put(String.valueOf(key), item);
            }
        });
        return Map.copyOf(result);
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        String text = text(value);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return new BigDecimal(text.replaceAll("[^0-9.]", ""));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private boolean containsIgnoreCase(String actual, String expected) {
        return StringUtils.hasText(actual)
                && StringUtils.hasText(expected)
                && actual.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
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

    private record MatchedSpu(CatalogSpuView spu, boolean matched, Map<String, Object> matchedSlots) {

        static MatchedSpu notMatched(CatalogSpuView spu) {
            return new MatchedSpu(spu, false, Map.of());
        }
    }
}
