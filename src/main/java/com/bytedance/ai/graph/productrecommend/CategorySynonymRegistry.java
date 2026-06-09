package com.bytedance.ai.graph.productrecommend;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 品类/商品同义词桥接表。数据驱动（resource: catalog/category-synonyms.txt），不写死在业务代码里。
 * <p>解决“用户口语词 ≠ 目录实际用词”：如用户搜“洗面奶”而目录里是“洁面乳/洁面”。
 * 召回侧用 {@link #expand} 把一个词扩展成同组等价词集合分别检索再合并；正向约束侧用
 * {@link #equivalent} 判断两个词是否同组（任一命中即满足类目条件）。
 */
@Service
public class CategorySynonymRegistry {

    private static final Logger log = LoggerFactory.getLogger(CategorySynonymRegistry.class);
    private static final String RESOURCE = "catalog/category-synonyms.txt";

    /** 词（小写） -> 所在组的全部等价词（含自身，保留原始大小写）。 */
    private final Map<String, Set<String>> groupByTerm = new ConcurrentHashMap<>();

    @PostConstruct
    void load() {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) {
            log.warn("synonym resource not found: {} (synonym bridging disabled)", RESOURCE);
            return;
        }
        int groups = 0;
        try (InputStream in = resource.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                Set<String> group = new LinkedHashSet<>();
                for (String part : trimmed.split(",")) {
                    String term = part.trim();
                    if (StringUtils.hasText(term)) {
                        group.add(term);
                    }
                }
                if (group.size() < 2) {
                    continue;
                }
                for (String term : group) {
                    groupByTerm.put(term.toLowerCase(Locale.ROOT), group);
                }
                groups++;
            }
        } catch (Exception exception) {
            log.warn("failed to load synonym resource {}: {}", RESOURCE, exception.toString());
        }
        log.info("category synonym registry loaded: {} groups, {} terms", groups, groupByTerm.size());
    }

    /**
     * 把一个词扩展为含自身的同组等价词列表（自身排首位，保持调用方原意优先）。
     * 无同义组时返回仅含自身的单元素列表；空输入返回空列表。
     */
    public List<String> expand(String term) {
        if (!StringUtils.hasText(term)) {
            return List.of();
        }
        String key = term.trim().toLowerCase(Locale.ROOT);
        Set<String> group = groupByTerm.get(key);
        if (group == null || group.isEmpty()) {
            return List.of(term.trim());
        }
        List<String> result = new ArrayList<>();
        result.add(term.trim());
        for (String synonym : group) {
            if (!synonym.equalsIgnoreCase(term.trim())) {
                result.add(synonym);
            }
        }
        return result;
    }

    /** a、b 是否属于同一同义组（含相等）。任一为空按不等价处理。 */
    public boolean equivalent(String a, String b) {
        if (!StringUtils.hasText(a) || !StringUtils.hasText(b)) {
            return false;
        }
        if (a.trim().equalsIgnoreCase(b.trim())) {
            return true;
        }
        Set<String> group = groupByTerm.get(a.trim().toLowerCase(Locale.ROOT));
        if (group == null) {
            return false;
        }
        for (String synonym : group) {
            if (synonym.equalsIgnoreCase(b.trim())) {
                return true;
            }
        }
        return false;
    }
}
