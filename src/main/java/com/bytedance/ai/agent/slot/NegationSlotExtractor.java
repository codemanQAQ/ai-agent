package com.bytedance.ai.agent.slot;

import com.bytedance.ai.agent.api.Slot;
import com.bytedance.ai.shared.support.RagJsonCodec;
import com.bytedance.ai.shared.support.RagLogHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 反选语义抽取器，专门把"不含 X / 非 X / 不要 X / 无 X"等否定表达分到
 * {@link Slot.MustNot#tags()} / {@link Slot.MustNot#brands()} / {@link Slot.MustNot#ingredients()}。
 *
 * <p>独立于 {@link LlmSlotExtractor}，原因：
 * <ol>
 *   <li>否定语义对模型敏感，写在主 slot prompt 里容易被正例淹没；分开走可以单独喂 few-shot。</li>
 *   <li>主 slot 抽取走 RECOMMEND_VAGUE / FILTER_BY_ATTR / REFINE 共用，
 *       反选不是每个意图都需要，分开还能省 token。</li>
 *   <li>降级路径不一样：主 slot 退化到正则抽 priceRange；反选退化到本地否定词库。</li>
 * </ol>
 *
 * <p>调用顺序：在 {@code AgentTurnService} 拿到主 slot 之后立即调一次，
 * 把抽出的 MustNot 通过 {@link Slot#withMustNot} 合并回去。
 */
@Component
public class NegationSlotExtractor {

    private static final Logger log = LoggerFactory.getLogger(NegationSlotExtractor.class);
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[\\s\\S]*}");

    /** 否定语缀本地词库：覆盖"不含 / 不要 / 非 / 无 / 排除 / 去掉"等中文表达。 */
    private static final Pattern NEGATION_FRAGMENT = Pattern.compile(
            "(不含|不要|不带|不喜欢|不能要|不想要|无|没有|去掉|排除|除掉|去除|" +
                    "非|不是|不为)" +
                    "\\s*" +
                    // 否定的对象：连续 2-8 个汉字 / 英文 / 数字
                    "([\\u4e00-\\u9fa5A-Za-z0-9·\\-]{1,16})"
    );

    /** 已知"成分 / 材质"关键字白名单，用于把命中归到 ingredients 而不是 tags。 */
    private static final java.util.Set<String> KNOWN_INGREDIENTS = java.util.Set.of(
            "酒精", "香精", "色素", "防腐剂", "皂基", "硅油", "尼泊金",
            "ARM", "x86", "动物皮", "真皮", "化纤", "聚酯纤维"
    );

    /** 已知品牌白名单（demo 数据集），命中归到 brands。 */
    private static final java.util.Set<String> KNOWN_BRANDS = java.util.Set.of(
            "Apple", "苹果", "Sony", "索尼", "Samsung", "三星",
            "Huawei", "华为", "Xiaomi", "小米", "Acme"
    );

    private static final String SYSTEM_PROMPT = """
            你是电商导购的"反选语义抽取器"。只输出 JSON 对象，禁止解释。

            目标：从用户消息中识别用户**不想要**的属性，分桶到三类：
            - tags：通用标签 / 风格 / 颜色 / 功能（例："黑色"、"塑料感"、"机械键盘"）
            - brands：品牌名（例："苹果"、"Apple"、"Sony"）
            - ingredients：成分 / 材质（例："酒精"、"香精"、"ARM"、"真皮"）

            JSON 形态：{"tags":string[],"brands":string[],"ingredients":string[]}
            没有就给空数组。同一项只放一个桶。

            few-shot 示例：
            user: 防晒霜，不含酒精和香精
              -> {"tags":[],"brands":[],"ingredients":["酒精","香精"]}
            user: 推荐蓝牙耳机，非苹果品牌
              -> {"tags":[],"brands":["Apple"],"ingredients":[]}
            user: 黑色双肩包不要这种深色调
              -> {"tags":["黑色","深色"],"brands":[],"ingredients":[]}
            user: 笔记本不要 ARM 芯片
              -> {"tags":[],"brands":[],"ingredients":["ARM"]}
            user: 想找一款补水面霜
              -> {"tags":[],"brands":[],"ingredients":[]}
            """;

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final RagJsonCodec jsonCodec;

    public NegationSlotExtractor(ObjectProvider<ChatModel> chatModelProvider, RagJsonCodec jsonCodec) {
        this.chatModelProvider = chatModelProvider;
        this.jsonCodec = jsonCodec;
    }

    public Slot.MustNot extract(String message) {
        if (!StringUtils.hasText(message)) {
            return Slot.MustNot.empty();
        }
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            return fallback(message);
        }
        try {
            String rawOutput = ChatClient.create(chatModel)
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user("message=" + message)
                    .call()
                    .content();
            return parse(rawOutput);
        } catch (RuntimeException exception) {
            log.debug("negation slot extraction falls back to regex: error={}", RagLogHelper.errorSummary(exception));
            return fallback(message);
        }
    }

    Slot.MustNot parse(String rawOutput) {
        if (!StringUtils.hasText(rawOutput)) {
            return Slot.MustNot.empty();
        }
        Map<String, Object> map = jsonCodec.readMap(extractJsonObject(rawOutput));
        return new Slot.MustNot(
                stringList(map.get("tags")),
                stringList(map.get("brands")),
                stringList(map.get("ingredients"))
        );
    }

    /**
     * 本地降级：扫"不含 X / 非 X / 不要 X"片段，按 KNOWN_INGREDIENTS / KNOWN_BRANDS 词库分桶，
     * 其它归到 tags。
     */
    Slot.MustNot fallback(String message) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        LinkedHashSet<String> brands = new LinkedHashSet<>();
        LinkedHashSet<String> ingredients = new LinkedHashSet<>();
        Matcher matcher = NEGATION_FRAGMENT.matcher(message);
        while (matcher.find()) {
            String target = matcher.group(2);
            if (!StringUtils.hasText(target)) {
                continue;
            }
            String trimmed = target.trim();
            // 词库扫描：同一段否定后缀可能并列多个关键词（"酒精和香精"），逐一抽出。
            boolean hit = false;
            for (String ingredient : KNOWN_INGREDIENTS) {
                if (trimmed.contains(ingredient)) {
                    ingredients.add(ingredient);
                    hit = true;
                }
            }
            for (String brand : KNOWN_BRANDS) {
                if (trimmed.contains(brand)) {
                    brands.add(brand);
                    hit = true;
                }
            }
            if (!hit) {
                tags.add(trimmed);
            }
        }
        return new Slot.MustNot(new ArrayList<>(tags), new ArrayList<>(brands), new ArrayList<>(ingredients));
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }
        return rawList.stream()
                .filter(item -> item != null && StringUtils.hasText(String.valueOf(item)))
                .map(item -> String.valueOf(item).trim())
                .toList();
    }

    private String extractJsonObject(String rawOutput) {
        String trimmed = rawOutput.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        Matcher matcher = JSON_OBJECT_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group();
        }
        throw new IllegalArgumentException("negation 输出未包含 JSON 对象");
    }
}
