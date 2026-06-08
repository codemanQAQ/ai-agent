package com.bytedance.ai.graph.productrecommend;

import com.bytedance.ai.graph.session.UnifiedQueryContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class SceneBundlePlanner {

    // 场景 → 角色 缓存：相同/重复场景免重复计算（②）。
    private final Map<String, List<SceneBundleRole>> roleCache = new ConcurrentHashMap<>();

    public SceneBundlePlan plan(UnifiedQueryContext queryContext) {
        return plan(queryContext, null);
    }

    /**
     * @param llmRoles 意图 LLM 在同一次调用里给出的 bundleRoles（{role, category, keywords}），可为空。
     *                 优先用 LLM 规划（灵活、零额外往返）；为空/无效再退到缓存→硬规则→类目推断。
     */
    public SceneBundlePlan plan(UnifiedQueryContext queryContext, List<Map<String, Object>> llmRoles) {
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

        List<SceneBundleRole> fromLlm = rolesFromLlm(llmRoles, positive);
        List<SceneBundleRole> roles;
        if (!fromLlm.isEmpty()) {
            roles = fromLlm;
            roleCache.put(resolvedScenario, roles);                 // 缓存 LLM 结果
        } else {
            roles = roleCache.get(resolvedScenario);                // 命中缓存直接用
            if (roles == null || roles.isEmpty()) {
                roles = rolesFor(resolvedScenario, queryText, positive);   // 硬规则 / 类目推断兜底
                roleCache.put(resolvedScenario, roles);
            }
        }
        return new SceneBundlePlan(
                resolvedScenario,
                audience,
                usageContext,
                roles,
                "按场景拆成多个商品角色，每个角色复用公共召回底座生成候选。"
        );
    }

    /** 解析意图 LLM 给出的 bundleRoles；类目必须是四个真实类目之一，否则丢弃该角色。 */
    @SuppressWarnings("unchecked")
    private List<SceneBundleRole> rolesFromLlm(List<Map<String, Object>> llmRoles, Map<String, Object> baseConstraints) {
        if (llmRoles == null || llmRoles.isEmpty()) {
            return List.of();
        }
        List<SceneBundleRole> roles = new ArrayList<>();
        int idx = 1;
        for (Map<String, Object> raw : llmRoles) {
            if (raw == null) {
                continue;
            }
            String category = firstText(raw.get("category"), raw.get("类目"));
            if (category == null || category.isBlank()) {
                continue;                                   // 无类目 → 跳过
            }
            // 不再用写死的白名单限制类目：意图 prompt 已按目录真实类目动态约束 LLM 输出；
            // 万一给了库里不存在的类目，按类目召回自然为空，不会出错。新增类目无需改这里。
            String name = firstText(raw.get("role"), raw.get("name"), raw.get("角色"), category);
            String keywords = firstText(raw.get("keywords"), raw.get("keyword"), raw.get("关键词"), name);
            roles.add(role("llm_role_" + idx++, name, keywords, category.trim(), baseConstraints));
            if (roles.size() >= 5) {
                break;                                      // 最多 5 个角色
            }
        }
        return roles;
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
        if (containsAny(scenario, queryText, "度假", "旅游", "旅行", "出游", "三亚", "海边", "海岛", "沙滩", "出行")) {
            return List.of(
                    role("sun_protection", "防晒", "防晒 防晒霜 隔离 户外", "美妆护肤", baseConstraints),
                    role("outfit", "穿搭", "度假 穿搭 服饰 轻便", "服饰运动", baseConstraints),
                    role("hydration", "补水修护", "旅行 补水 保湿 面膜", "美妆护肤", baseConstraints),
                    role("travel_device", "出行数码", "便携 充电 数码", "数码电子", baseConstraints)
            );
        }
        // 兜底：先从 query 文本识别涉及的真实类目，按类目建可召回角色（避免用整句当关键词导致召回为 0）。
        List<SceneBundleRole> inferred = inferRolesFromCategories(queryText, scenario, baseConstraints);
        if (!inferred.isEmpty()) {
            return inferred;
        }
        List<SceneBundleRole> fallback = new ArrayList<>();
        fallback.add(role("main_product", "核心商品", append(queryText, "核心 实用"), null, baseConstraints));
        fallback.add(role("addon_product", "搭配商品", append(queryText, "搭配 补充"), null, baseConstraints));
        fallback.add(role("budget_option", "预算友好", append(queryText, "性价比"), null, baseConstraints));
        return List.copyOf(fallback);
    }

    /** 从场景/文本识别涉及的真实类目（防晒→美妆护肤、穿搭→服饰运动…），按类目生成可召回的角色。 */
    private List<SceneBundleRole> inferRolesFromCategories(String queryText, String scenario, Map<String, Object> base) {
        List<SceneBundleRole> roles = new ArrayList<>();
        if (containsAny(queryText, scenario, "防晒", "护肤", "面膜", "精华", "补水", "化妆", "美妆", "隔离")) {
            roles.add(role("beauty", "美妆护肤", "防晒 护肤 补水", "美妆护肤", base));
        }
        if (containsAny(queryText, scenario, "穿搭", "衣服", "服饰", "裙", "鞋", "泳衣", "帽", "墨镜", "运动", "搭配")) {
            roles.add(role("apparel", "服饰穿搭", "穿搭 服饰 轻便", "服饰运动", base));
        }
        if (containsAny(queryText, scenario, "数码", "手机", "相机", "耳机", "充电", "电子", "平板", "电脑")) {
            roles.add(role("digital", "数码电子", "便携 数码 充电", "数码电子", base));
        }
        if (containsAny(queryText, scenario, "零食", "饮料", "食品", "吃", "喝", "补给")) {
            roles.add(role("food", "食品饮料", "零食 饮品 补给", "食品饮料", base));
        }
        return roles;
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
            // 角色类目必须权威覆盖：多轮会话里 baseConstraints 可能带着上一轮残留的 category
            // （如上轮"洗面奶"），用 putIfAbsent 会导致角色类目被忽略、按错误类目召回为空。
            constraints.put("category", category);
            // 同时清掉会跨轮串扰本角色召回的细粒度约束（子类目/上一轮商品属性），各角色按自己的类目召回。
            constraints.remove("subCategory");
            constraints.remove("attributes");
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
