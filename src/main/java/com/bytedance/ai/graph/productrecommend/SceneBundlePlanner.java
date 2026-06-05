package com.bytedance.ai.graph.productrecommend;

import com.bytedance.ai.graph.session.UnifiedQueryContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SceneBundlePlanner {

    public SceneBundlePlan plan(UnifiedQueryContext queryContext) {
        Map<String, Object> positive = queryContext == null ? Map.of() : queryContext.positiveConstraints();
        String queryText = queryContext == null ? null : queryContext.queryText();
        String scenario = firstText(
                positive.get("scenario"),
                positive.get("usageContext"),
                positive.get("场景"),
                positive.get("使用场景"),
                queryText
        );
        String resolvedScenario = normalizeScenario(scenario);
        String audience = firstText(positive.get("audience"), positive.get("人群"), positive.get("送给谁"));
        String usageContext = firstText(positive.get("usageContext"), positive.get("使用场景"), positive.get("用途"));
        List<SceneBundleRole> roles = rolesFor(resolvedScenario, queryText, positive);
        return new SceneBundlePlan(
                resolvedScenario,
                audience,
                usageContext,
                roles,
                "按场景拆成多个商品角色，每个角色复用公共召回底座生成候选。"
        );
    }

    private List<SceneBundleRole> rolesFor(String scenario, String queryText, Map<String, Object> baseConstraints) {
        if (containsAny(scenario, queryText, "露营", "户外", "野餐")) {
            return List.of(
                    role("lighting", "照明", "露营 户外 照明 灯", "数码电子", baseConstraints),
                    role("storage", "收纳", "露营 户外 收纳 便携", "食品饮料", baseConstraints),
                    role("sun_protection", "防晒", "户外 防晒 防护", "美妆护肤", baseConstraints),
                    role("hydration", "补水", "户外 补水 保湿", "美妆护肤", baseConstraints)
            );
        }
        if (containsAny(scenario, queryText, "通勤", "上班", "办公", "学习")) {
            return List.of(
                    role("portable_device", "便携设备", "通勤 办公 便携 数码", "数码电子", baseConstraints),
                    role("personal_care", "随身护理", "通勤 清爽 护理", "美妆护肤", baseConstraints),
                    role("snack_drink", "补给零食", "办公 通勤 零食 饮品", "食品饮料", baseConstraints)
            );
        }
        if (containsAny(scenario, queryText, "送礼", "礼物", "生日", "节日")) {
            return List.of(
                    role("gift_feature", "主礼品", "送礼 高质感 实用", null, baseConstraints),
                    role("gift_addon", "搭配小礼", "送礼 搭配 小件", null, baseConstraints),
                    role("gift_packaging", "氛围搭配", "礼物 精致 氛围", null, baseConstraints)
            );
        }
        if (containsAny(scenario, queryText, "熬夜", "修护", "护肤")) {
            return List.of(
                    role("cleanse", "清洁", "熬夜 护肤 清洁 温和", "美妆护肤", baseConstraints),
                    role("repair", "修护", "熬夜 修护 保湿", "美妆护肤", baseConstraints),
                    role("snack_drink", "夜间补给", "熬夜 低负担 饮品 零食", "食品饮料", baseConstraints)
            );
        }
        List<SceneBundleRole> fallback = new ArrayList<>();
        fallback.add(role("main_product", "核心商品", append(queryText, "核心 实用"), null, baseConstraints));
        fallback.add(role("addon_product", "搭配商品", append(queryText, "搭配 补充"), null, baseConstraints));
        fallback.add(role("budget_option", "预算友好", append(queryText, "性价比"), null, baseConstraints));
        return List.copyOf(fallback);
    }

    private SceneBundleRole role(
            String roleId,
            String name,
            String query,
            String category,
            Map<String, Object> baseConstraints
    ) {
        Map<String, Object> constraints = new LinkedHashMap<>(baseConstraints == null ? Map.of() : baseConstraints);
        constraints.put("bundleRole", name);
        if (category != null && !category.isBlank()) {
            constraints.putIfAbsent("category", category);
        }
        return new SceneBundleRole(roleId, name, query, Map.copyOf(constraints), "为场景中的“" + name + "”角色补齐商品。");
    }

    private String normalizeScenario(String value) {
        if (value == null || value.isBlank()) {
            return "通用组合";
        }
        String text = value.trim();
        if (containsAny(text, null, "露营", "户外", "野餐")) {
            return "露营/户外";
        }
        if (containsAny(text, null, "通勤", "上班", "办公", "学习")) {
            return "通勤/办公";
        }
        if (containsAny(text, null, "送礼", "礼物", "生日", "节日")) {
            return "送礼";
        }
        if (containsAny(text, null, "熬夜", "修护", "护肤")) {
            return "熬夜护肤";
        }
        return text;
    }

    private boolean containsAny(String first, String second, String... keywords) {
        String text = ((first == null ? "" : first) + " " + (second == null ? "" : second)).toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String firstText(Object... values) {
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (!text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private String append(String queryText, String suffix) {
        if (queryText == null || queryText.isBlank()) {
            return suffix;
        }
        return queryText + " " + suffix;
    }
}
