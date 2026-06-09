package com.bytedance.ai.graph.productrecommend;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PositiveConstraintFilter {

    private final CategorySynonymRegistry synonymRegistry;

    public PositiveConstraintFilter(CategorySynonymRegistry synonymRegistry) {
        this.synonymRegistry = synonymRegistry;
    }

    /** 便捷构造（非 Spring 装配路径，如单测/手动构造）：自建并加载同义词表。 */
    public PositiveConstraintFilter() {
        this(defaultRegistry());
    }

    private static CategorySynonymRegistry defaultRegistry() {
        CategorySynonymRegistry registry = new CategorySynonymRegistry();
        registry.load();
        return registry;
    }

    public ProductCandidateFilterResult filter(
            List<ProductRecallCandidate> candidates,
            Map<String, Object> positiveConstraints
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return new ProductCandidateFilterResult(List.of(), List.of());
        }
        if (positiveConstraints == null || positiveConstraints.isEmpty()) {
            return new ProductCandidateFilterResult(candidates, List.of());
        }

        List<ProductRecallCandidate> kept = new ArrayList<>();
        List<ProductCandidateExclusion> exclusions = new ArrayList<>();
        for (ProductRecallCandidate candidate : candidates) {
            String reason = mismatchReason(candidate, positiveConstraints);
            if (StringUtils.hasText(reason)) {
                exclusions.add(ProductCandidateExclusion.of(candidate, reason));
            } else {
                kept.add(candidate);
            }
        }
        return new ProductCandidateFilterResult(kept, exclusions);
    }

    /**
     * 仅强制"强约束"（类目/价格/库存）。这类约束无论召回场景如何都应满足——例如用户明确说
     * "3000以内的手机"，即便意图被判为宽召回(FUZZY)，也不该把配件/其它类目商品作为兜底返回。
     * 软偏好（品牌/属性/规格/商品范围）仍由 {@link #filter} 在需要时强制。
     */
    public ProductCandidateFilterResult filterHard(
            List<ProductRecallCandidate> candidates,
            Map<String, Object> positiveConstraints
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return new ProductCandidateFilterResult(List.of(), List.of());
        }
        if (positiveConstraints == null || positiveConstraints.isEmpty()) {
            return new ProductCandidateFilterResult(candidates, List.of());
        }
        List<ProductRecallCandidate> kept = new ArrayList<>();
        List<ProductCandidateExclusion> exclusions = new ArrayList<>();
        for (ProductRecallCandidate candidate : candidates) {
            String reason = hardMismatchReason(candidate, positiveConstraints);
            if (StringUtils.hasText(reason)) {
                exclusions.add(ProductCandidateExclusion.of(candidate, reason));
            } else {
                kept.add(candidate);
            }
        }
        return new ProductCandidateFilterResult(kept, exclusions);
    }

    private String hardMismatchReason(ProductRecallCandidate candidate, Map<String, Object> constraints) {
        if (!matchesCategory(candidate, firstPresent(constraints, "category", "categoryPath", "类目"))) {
            return "不满足类目条件";
        }
        if (!matchesPrice(candidate, constraints)) {
            return "不满足价格条件";
        }
        if (!matchesStock(candidate, constraints)) {
            return "不满足库存条件";
        }
        return null;
    }

    private String mismatchReason(ProductRecallCandidate candidate, Map<String, Object> constraints) {
        if (!matchesAny(candidate.productId(), values(constraints, "productIds", "productId"))) {
            return "不满足商品范围条件";
        }
        // 注意：productRefs 是意图抽取的自然语言商品名（如"珀莱雅精华液"），用于召回提示，
        // 不是 externalRef/ID 白名单；放进这里会把召回到的候选按 ID 精确比对而全部误删。
        if (!matchesAny(candidate.externalRef(), values(constraints, "externalRefs", "externalRef"))) {
            return "不满足商品范围条件";
        }
        // 品牌/子品牌（如"特仑苏"）常出现在标题而非 brand 字段（brand="蒙牛"），
        // 故品牌约束对 brand 或 title 任一命中即可，避免精确商品名查询被全部过滤。
        if (!matchesTextAny(firstPresent(constraints, "brand", "品牌"), candidate.brand(), candidate.title())) {
            return "不满足品牌条件";
        }
        if (!matchesCategory(candidate, firstPresent(constraints, "category", "categoryPath", "类目"))) {
            return "不满足类目条件";
        }
        if (!matchesPrice(candidate, constraints)) {
            return "不满足价格条件";
        }
        if (!matchesStock(candidate, constraints)) {
            return "不满足库存条件";
        }
        if (!matchesSpecs(candidate, firstPresent(constraints, "specs", "skuSpec", "规格"))) {
            return "不满足规格条件";
        }
        return null;
    }

    private boolean matchesText(String actual, Object expected) {
        String expectedText = text(expected);
        if (!StringUtils.hasText(expectedText)) {
            return true;
        }
        return containsIgnoreCase(actual, expectedText);
    }

    /** 期望值为空则放行；否则只要任一候选字段包含期望值即视为命中。 */
    private boolean matchesTextAny(Object expected, String... actuals) {
        String expectedText = text(expected);
        if (!StringUtils.hasText(expectedText)) {
            return true;
        }
        for (String actual : actuals) {
            if (containsIgnoreCase(actual, expectedText)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 类目只按结构化类目路径判定，不看标题：
     *   - 直接包含即命中（"手机" ⊂ "数码电子/智能手机"）；
     *   - 否则按 token 重叠兼容同义类目（"蓝牙耳机" ∩ "真无线耳机" = {耳机}）；
     *   - 类目路径缺失时才退回标题包含兜底。
     * 这样能挡住标题里恰好提到品类词、实则属于其它类目的商品（如"车载手机支架"不属于"手机"）。
     */
    private boolean matchesCategory(ProductRecallCandidate candidate, Object expected) {
        String expectedText = text(expected);
        if (!StringUtils.hasText(expectedText)) {
            return true;
        }
        List<String> categoryPath = candidate.categoryPath();
        String path = categoryPath == null ? "" : String.join("/", categoryPath);
        String leaf = categoryPath == null || categoryPath.isEmpty()
                ? null : categoryPath.get(categoryPath.size() - 1);
        // 同义词桥接：用户用词与目录用词可能不同（"洗面奶" vs 目录"洁面乳/洁面"）。
        // 把约束词扩成同组等价词，任一形式命中即视为满足类目。原词排首位，先按原词判定。
        java.util.List<String> aliases = synonymRegistry.expand(expectedText);
        if (!StringUtils.hasText(path)) {
            for (String alias : aliases) {
                if (containsIgnoreCase(candidate.title(), alias)) {
                    return true;
                }
            }
            return false;
        }
        // 1) 子串匹配用【完整路径】：兼容顶级类目约束（"母婴用品" ⊂ "母婴用品/纸尿裤"）与
        //    叶子子串（"手机" ⊂ "数码电子/智能手机"）；同义词形式同样按子串判定。
        for (String alias : aliases) {
            if (containsIgnoreCase(path, alias)) {
                return true;
            }
        }
        // 2) bigram / 子序列只对【叶子类目】判定——避免顶级类目（如"服饰运动"含"运动"）把
        //    "运动裤"误命中到全部服饰运动商品；同时兼容同义/缩略叶子：
        //    蓝牙耳机 ~ 真无线耳机（共 耳机）、运动裤 ⊆ 运动长裤、跑鞋 ⊆ 跑步鞋。
        if (StringUtils.hasText(leaf)) {
            // 同义组直接等价（"洗面奶" ~ 叶子"洁面"），无需共享 token。
            for (String alias : aliases) {
                if (synonymRegistry.equivalent(alias, leaf)) {
                    return true;
                }
            }
            java.util.Set<String> have = ProductRecallTextTokenizer.tokens(leaf);
            for (String term : ProductRecallTextTokenizer.tokens(expectedText)) {
                if (term.length() >= 2 && have.contains(term)) {
                    return true;
                }
            }
            return isCjkSubsequence(expectedText, leaf);
        }
        return false;
    }

    /** needle 的汉字是否按顺序（可不连续）出现在 hay 中；少于 2 个汉字不参与（太宽松）。 */
    private boolean isCjkSubsequence(String needle, String hay) {
        if (needle == null || hay == null) {
            return false;
        }
        String n = needle.replaceAll("[^\\u4e00-\\u9fff]", "");
        if (n.length() < 2) {
            return false;
        }
        int i = 0;
        for (int c = 0; c < hay.length() && i < n.length(); c++) {
            if (hay.charAt(c) == n.charAt(i)) {
                i++;
            }
        }
        return i == n.length();
    }

    private boolean matchesPrice(ProductRecallCandidate candidate, Map<String, Object> constraints) {
        BigDecimal price = candidate.price();
        BigDecimal min = decimal(firstPresent(constraints, "priceMin", "minPrice", "价格下限"));
        BigDecimal max = decimal(firstPresent(constraints, "priceMax", "maxPrice", "预算", "价格上限"));
        if (price == null) {
            return min == null && max == null;
        }
        if (min != null && price.compareTo(min) < 0) {
            return false;
        }
        return max == null || price.compareTo(max) <= 0;
    }

    private boolean matchesStock(ProductRecallCandidate candidate, Map<String, Object> constraints) {
        Object onlyInStock = firstPresent(constraints, "inStock", "onlyInStock", "有货");
        if (onlyInStock == null || !Boolean.parseBoolean(String.valueOf(onlyInStock))) {
            return true;
        }
        return candidate.stock() != null && candidate.stock() > 0;
    }

    private boolean matchesSpecs(ProductRecallCandidate candidate, Object expectedSpecs) {
        Map<String, Object> specs = map(expectedSpecs);
        if (specs.isEmpty()) {
            return true;
        }
        String searchable = (candidate.matchedSlots().toString() + " " + candidate.evidence()).toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Object> entry : specs.entrySet()) {
            String key = text(entry.getKey());
            String value = text(entry.getValue());
            if (!StringUtils.hasText(value)) {
                continue;
            }
            boolean matched = searchable.contains(value.toLowerCase(Locale.ROOT))
                    && (!StringUtils.hasText(key) || searchable.contains(key.toLowerCase(Locale.ROOT)));
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesAny(String actual, List<String> expectedValues) {
        if (expectedValues == null || expectedValues.isEmpty()) {
            return true;
        }
        if (!StringUtils.hasText(actual)) {
            return false;
        }
        return expectedValues.stream()
                .filter(StringUtils::hasText)
                .anyMatch(expected -> actual.equals(expected) || containsIgnoreCase(actual, expected));
    }

    private Object firstPresent(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            if (values.containsKey(key) && values.get(key) != null) {
                return values.get(key);
            }
        }
        return null;
    }

    private List<String> values(Map<String, Object> values, String... keys) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (String key : keys) {
            Object value = firstPresent(values, key);
            if (value instanceof List<?> list) {
                list.stream()
                        .filter(item -> item != null && StringUtils.hasText(String.valueOf(item)))
                        .map(String::valueOf)
                        .forEach(result::add);
            } else if (value != null && StringUtils.hasText(String.valueOf(value))) {
                result.add(String.valueOf(value));
            }
        }
        return List.copyOf(result);
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key != null && item != null) {
                result.put(String.valueOf(key), item);
            }
        });
        return Map.copyOf(result);
    }

    private boolean containsIgnoreCase(String actual, String expected) {
        return StringUtils.hasText(actual)
                && StringUtils.hasText(expected)
                && actual.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
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

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }
}
