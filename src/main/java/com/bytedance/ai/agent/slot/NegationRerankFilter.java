package com.bytedance.ai.agent.slot;

import com.bytedance.ai.agent.api.Slot;
import com.bytedance.ai.retrieval.spi.ProductSearchHit;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 反选 rerank：拿到 top-50 召回 + {@link Slot.MustNot} 之后，
 * 对每个 hit 做"是否含 X"的二分类，命中即剔除。
 *
 * <p>典型场景：用户说"防晒霜不含酒精"，召回侧 mustNotIngredients=["酒精"]
 * 已经下推到 Milvus，但 chunk metadata 没保存全文成分表的情况下还是会漏到 top-50；
 * 这里再过一遍精过滤，按 hit snippet + title 让 LLM 判定。
 *
 * <p>结构：
 * <ul>
 *   <li>有 ChatModel：批量 prompt，一次喂全部 hits，让模型输出 boolean 列表</li>
 *   <li>无 ChatModel / LLM 异常：本地降级——直接在 snippet 里做大小写无关包含匹配</li>
 * </ul>
 *
 * <p>返回值同时包含 keepHits + excludedFacets，方便上游构造 tool.result 事件。
 */
@Component
public class NegationRerankFilter {

    private static final Logger log = LoggerFactory.getLogger(NegationRerankFilter.class);

    private static final String SYSTEM_PROMPT = """
            你是电商导购的"反选裁判"。给定若干商品的标题/简介，以及一组"用户不想要的属性"，
            判断每个商品是否"明显含有"任何一个被排除的属性。

            只输出 JSON 对象：{"verdicts":[{"index":int,"contains":bool,"reason":"短语"}...]}
            禁止解释。如果不确定，请输出 contains=false（宁误放过、不误剔除）。
            """;

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final RagJsonCodec jsonCodec;

    public NegationRerankFilter(ObjectProvider<ChatModel> chatModelProvider, RagJsonCodec jsonCodec) {
        this.chatModelProvider = chatModelProvider;
        this.jsonCodec = jsonCodec;
    }

    public Result apply(List<ProductSearchHit> hits, Slot.MustNot mustNot) {
        if (hits == null || hits.isEmpty() || mustNot == null || mustNot.isEmpty()) {
            return new Result(hits == null ? List.of() : hits, List.of());
        }
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            return localFallback(hits, mustNot);
        }
        try {
            return llmRerank(hits, mustNot, chatModel);
        } catch (RuntimeException exception) {
            log.debug("negation rerank falls back to local: error={}", RagLogHelper.errorSummary(exception));
            return localFallback(hits, mustNot);
        }
    }

    private Result llmRerank(List<ProductSearchHit> hits, Slot.MustNot mustNot, ChatModel chatModel) {
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("【用户不想要的属性】\n");
        userPrompt.append("tags=").append(mustNot.tags()).append("\n");
        userPrompt.append("brands=").append(mustNot.brands()).append("\n");
        userPrompt.append("ingredients=").append(mustNot.ingredients()).append("\n\n");
        userPrompt.append("【候选商品】\n");
        for (int i = 0; i < hits.size(); i++) {
            ProductSearchHit hit = hits.get(i);
            userPrompt.append(i).append(". ")
                    .append(safe(hit.externalRef())).append(" | ")
                    .append(safe(hit.snippet())).append("\n");
        }

        String rawOutput = ChatClient.create(chatModel)
                .prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt.toString())
                .call()
                .content();
        return parse(hits, mustNot, rawOutput);
    }

    @SuppressWarnings("unchecked")
    Result parse(List<ProductSearchHit> hits, Slot.MustNot mustNot, String rawOutput) {
        if (!StringUtils.hasText(rawOutput)) {
            return new Result(hits, List.of());
        }
        try {
            Map<String, Object> map = jsonCodec.readMap(rawOutput.trim());
            Object verdicts = map.get("verdicts");
            if (!(verdicts instanceof List<?> rawList)) {
                return new Result(hits, List.of());
            }
            boolean[] excluded = new boolean[hits.size()];
            List<String> excludedFacets = new ArrayList<>();
            for (Object item : rawList) {
                if (!(item instanceof Map<?, ?> verdict)) {
                    continue;
                }
                Object indexValue = verdict.get("index");
                Object containsValue = verdict.get("contains");
                if (!(indexValue instanceof Number indexNumber)) {
                    continue;
                }
                int index = indexNumber.intValue();
                if (index < 0 || index >= hits.size()) {
                    continue;
                }
                if (Boolean.TRUE.equals(containsValue)) {
                    excluded[index] = true;
                    Object reason = verdict.get("reason");
                    if (reason instanceof String reasonText && StringUtils.hasText(reasonText)) {
                        excludedFacets.add(reasonText.trim());
                    }
                }
            }
            return buildResult(hits, excluded, excludedFacets, mustNot);
        } catch (RuntimeException exception) {
            log.debug("negation rerank parse failed: error={}", RagLogHelper.errorSummary(exception));
            return new Result(hits, List.of());
        }
    }

    private Result localFallback(List<ProductSearchHit> hits, Slot.MustNot mustNot) {
        boolean[] excluded = new boolean[hits.size()];
        List<String> excludedFacets = new ArrayList<>();
        List<String> forbiddenAll = mustNot.flatten();
        for (int i = 0; i < hits.size(); i++) {
            String haystack = haystackLower(hits.get(i));
            for (String forbidden : forbiddenAll) {
                if (StringUtils.hasText(forbidden)
                        && haystack.contains(forbidden.toLowerCase(Locale.ROOT))) {
                    excluded[i] = true;
                    excludedFacets.add(forbidden);
                    break;
                }
            }
        }
        return buildResult(hits, excluded, excludedFacets, mustNot);
    }

    private Result buildResult(
            List<ProductSearchHit> hits,
            boolean[] excluded,
            List<String> excludedFacetReasons,
            Slot.MustNot mustNot
    ) {
        List<ProductSearchHit> keep = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            if (!excluded[i]) {
                keep.add(hits.get(i));
            }
        }
        // 兜底：哪怕 LLM 没给 reason，excludedFacets 也至少回放原始 mustNot 字符串，
        // 客户端"已为您排除：..." 永远有内容可用。
        List<String> facets = excludedFacetReasons.isEmpty()
                ? mustNot.flatten()
                : new ArrayList<>(new java.util.LinkedHashSet<>(excludedFacetReasons));
        return new Result(keep, facets);
    }

    private String haystackLower(ProductSearchHit hit) {
        StringBuilder builder = new StringBuilder();
        builder.append(safe(hit.snippet())).append(' ').append(safe(hit.externalRef()));
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * @param keepHits        过滤后留下的 hits（顺序保留）
     * @param excludedFacets  对外展示给客户端的"已为您排除"标签；不为空表示真的剔了东西
     */
    public record Result(List<ProductSearchHit> keepHits, List<String> excludedFacets) {
        public Result {
            keepHits = keepHits == null ? List.of() : List.copyOf(keepHits);
            excludedFacets = excludedFacets == null ? List.of() : List.copyOf(excludedFacets);
        }
    }
}
