package com.bytedance.ai.agent.eval;

import com.bytedance.ai.shared.support.RagJsonCodec;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验 W2 反选加分项验收用例资源结构正确，避免 EvalRunner 跑分时再发现格式错误。
 *
 * <p>不实际跑 agent，只校验"5 条 + 必填字段 + mustNot 桶名"这种硬约束。
 */
class W2NegationCasesTests {

    private final RagJsonCodec jsonCodec = new RagJsonCodec(JsonMapper.builder().build());

    @Test
    @SuppressWarnings("unchecked")
    void datasetHasFiveNegationCasesWithRequiredFields() throws Exception {
        Map<String, Object> dataset;
        try (InputStream in = new ClassPathResource("eval/w2-negation-cases.json").getInputStream()) {
            dataset = jsonCodec.readMap(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }

        List<Map<String, Object>> cases = (List<Map<String, Object>>) dataset.get("cases");
        assertThat(cases).as("反选用例固定 5 条").hasSize(5);

        for (Map<String, Object> aCase : cases) {
            assertThat(aCase)
                    .containsKeys("id", "query", "expectedSlotMustNot",
                            "expectedExcludedFacetsContains",
                            "expectedKeepSpuRefs", "expectedDropSpuRefs");
            assertThat((String) aCase.get("query")).isNotBlank();

            Map<String, Object> mustNot = (Map<String, Object>) aCase.get("expectedSlotMustNot");
            // 至少有一个桶非空（要么 tags 要么 brands 要么 ingredients）。
            long nonEmptyBuckets = mustNot.entrySet().stream()
                    .filter(e -> List.of("tags", "brands", "ingredients").contains(e.getKey()))
                    .filter(e -> e.getValue() instanceof List<?> list && !list.isEmpty())
                    .count();
            assertThat(nonEmptyBuckets)
                    .as("case=%s mustNot 至少一个桶要有内容", aCase.get("id"))
                    .isGreaterThanOrEqualTo(1);

            assertThat((List<?>) aCase.get("expectedExcludedFacetsContains"))
                    .as("case=%s 至少期望 1 条 excludedFacet", aCase.get("id"))
                    .isNotEmpty();
        }
    }
}
