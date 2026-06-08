package com.bytedance.ai.graph.productrecommend;

import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogSpuView;
import com.bytedance.ai.graph.session.UnifiedQueryContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        // 指名/对比场景：意图把具体商品放进 productRefs（如["珀莱雅精华液","兰蔻小黑瓶"]）。
        // 按每个商品名分别召回再合并，否则只看 category/brand/整句会漏掉这些被点名的商品。
        List<String> productRefs = stringList(context.positiveConstraints().get("productRefs"));
        // 意图 LLM 间歇性会把对比商品名漏抽（productRefs 为空），导致退化成按通用品类词召回、丢失
        // 被点名的品牌。对比场景下做确定性兜底：直接从用户原文按"和/与/vs/、"切出对比实体。
        if (productRefs.isEmpty() && request.plan() != null
                && request.plan().subScene() == ProductRecommendSubScene.PRODUCT_COMPARE) {
            productRefs = deriveCompareRefs(context.queryText());
        }
        if (!productRefs.isEmpty()) {
            String category = text(context.positiveConstraints().get("category"));
            List<List<CatalogSpuView>> perRef = new ArrayList<>();
            for (String ref : productRefs) {
                perRef.add(searchByRef(ref, category, request.limit()));
            }
            // 轮转交错合并：每个被点名商品的首选轮流排在最前，避免某个 ref 的近邻候选
            // 一次性占满 limit、把其他被点名商品（如"兰蔻小黑瓶"）挤到末尾被截掉。
            Map<String, ProductRecallCandidate> merged = new LinkedHashMap<>();
            int maxPerRef = perRef.stream().mapToInt(List::size).max().orElse(0);
            for (int i = 0; i < maxPerRef; i++) {
                for (int r = 0; r < perRef.size(); r++) {
                    List<CatalogSpuView> list = perRef.get(r);
                    if (i < list.size()) {
                        CatalogSpuView spu = list.get(i);
                        merged.putIfAbsent(String.valueOf(spu.id()), toCandidate(spu, productRefs.get(r)));
                    }
                }
            }
            if (!merged.isEmpty()) {
                return List.copyOf(merged.values());
            }
        }
        // 常规：优先用抽取出的结构化槽位（category/brand）做关键词；整句 queryText 仅作兜底。
        String keyword = ProductRecallTextTokenizer.bestKeyword(
                text(context.positiveConstraints().get("category")),
                Arrays.asList(text(context.positiveConstraints().get("brand")), context.queryText())
        );
        if (keyword == null) {
            return List.of();
        }
        return catalogQueryFacade.searchActiveSpus(keyword, request.limit()).stream()
                .map(spu -> toCandidate(spu, keyword))
                .toList();
    }

    /**
     * 按一个商品名召回。
     * <p>有品类约束时（如对比手机 category="手机"），先把 ref 的"品牌部分"（去掉品类词后的残余，
     * 如"小米手机"→"小米"、"苹果手机"→"苹果"）单独检索，品类收窄交给下游正向约束过滤器。这样能
     * 绕开通用品类词的两类问题：① "手机"会顺带命中 description_md 里提到该词的笔记本/平板等跨品类
     * 商品；② iPhone 标题是英文"Apple iPhone"、不含中文"苹果/手机"，用品类词打分会和 vivo/OPPO
     * 同档而被灌入。按品牌召回 + 品类过滤对"品牌+品类"型对比一致生效。
     * <p>无品类约束（如对比"珀莱雅精华液和兰蔻小黑瓶"，category=null）时退回 token 打分：整串与逐
     * token 检索结果合并后，按"ref token 在标题/品牌上的命中数"取最高档，剔除只蹭品类词的噪声。
     */
    private List<CatalogSpuView> searchByRef(String ref, String category, int limit) {
        String brand = brandPart(ref, category);
        if (brand != null) {
            List<CatalogSpuView> byBrand = catalogQueryFacade.searchActiveSpus(brand, Math.max(limit, 20));
            if (!byBrand.isEmpty()) {
                return byBrand;
            }
        }
        return tokenTierSearch(ref, limit);
    }

    /**
     * ref 去掉品类词后的"品牌部分"；无品类约束、品类词不在 ref 内、或残余过短时返回 null（不走品牌召回）。
     */
    private String brandPart(String ref, String category) {
        if (ref == null || category == null || category.isBlank()) {
            return null;
        }
        String brand = ref;
        for (String term : ProductRecallTextTokenizer.tokens(category)) {
            if (term != null && term.length() >= 2) {
                brand = brand.replace(term, "");
            }
        }
        brand = brand.trim();
        return brand.length() >= 2 && !brand.equals(ref) ? brand : null;
    }

    private List<CatalogSpuView> tokenTierSearch(String ref, int limit) {
        Set<String> terms = ProductRecallTextTokenizer.tokens(ref);
        Map<Long, CatalogSpuView> byId = new LinkedHashMap<>();
        for (CatalogSpuView spu : catalogQueryFacade.searchActiveSpus(ref, limit)) {
            byId.putIfAbsent(spu.id(), spu);
        }
        for (String term : terms) {
            if (term == null || term.length() < 2) {
                continue;
            }
            for (CatalogSpuView spu : catalogQueryFacade.searchActiveSpus(term, limit)) {
                byId.putIfAbsent(spu.id(), spu);
            }
        }
        if (byId.isEmpty()) {
            return List.of();
        }
        int best = byId.values().stream().mapToInt(spu -> refMatchCount(spu, terms)).max().orElse(0);
        if (best == 0) {
            return List.of();
        }
        List<CatalogSpuView> ranked = byId.values().stream()
                .filter(spu -> refMatchCount(spu, terms) == best)
                .toList();
        return ranked.size() > limit ? ranked.subList(0, limit) : ranked;
    }

    /** ref 的 token 在该商品标题/品牌里命中的数量，用于相关性排序。 */
    private int refMatchCount(CatalogSpuView spu, Set<String> terms) {
        String hay = ((spu.title() == null ? "" : spu.title()) + " "
                + (spu.brand() == null ? "" : spu.brand())).toLowerCase();
        int n = 0;
        for (String term : terms) {
            if (term != null && term.length() >= 2 && hay.contains(term.toLowerCase())) {
                n++;
            }
        }
        return n;
    }

    private ProductRecallCandidate toCandidate(CatalogSpuView spu, String keyword) {
        return CatalogProductCandidateMapper.toCandidate(
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
        );
    }

    /**
     * 从对比类原文切出被对比的实体，作为 productRefs 的确定性兜底（不依赖 LLM 抽取）。
     * 先去掉对比引导/收尾词，再按"和/与/跟/还是/vs/、/，"切分；至少切出 2 段才视为有效对比。
     */
    private List<String> deriveCompareRefs(String queryText) {
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }
        String stripped = queryText.trim()
                .replaceAll("(?i)对比一下|对比下|对比|比较一下|比较|对照|pk|区别|哪个更好|哪个好|哪款好|哪个值得买|怎么选|怎么样|的区别", " ");
        String[] parts = stripped.split("(?i)\\s*(和|与|跟|还是|vs|、|，|,|及|或)\\s*");
        List<String> refs = new ArrayList<>();
        for (String part : parts) {
            String token = part.trim();
            if (token.length() >= 2) {
                refs.add(token);
            }
        }
        return refs.size() >= 2 ? refs : List.of();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            String text = text(item);
            if (text != null) {
                result.add(text);
            }
        }
        return result;
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
