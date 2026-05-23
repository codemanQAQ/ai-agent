package com.bytedance.ai.agent.eval;

import com.bytedance.ai.shared.support.RagJsonCodec;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验 W1 验收用例资源结构正确，避免后续 EvalRunner（W2）跑分前才发现格式错误。
 *
 * <p>不实际调 retrieval/agent，只校验 JSON 形态与"5 基础 + 5 筛选 = 10 条" 这一硬要求。
 */
class W1AcceptanceCasesTests {

    private final RagJsonCodec jsonCodec = new RagJsonCodec(JsonMapper.builder().build());

    @Test
    @SuppressWarnings("unchecked")
    void datasetMatchesContractFiveBasicPlusFiveFilter() throws Exception {
        Map<String, Object> dataset;
        try (InputStream in = new ClassPathResource("eval/w1-acceptance-cases.json").getInputStream()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            dataset = jsonCodec.readMap(body);
        }

        List<Map<String, Object>> cases = (List<Map<String, Object>>) dataset.get("cases");
        assertThat(cases).as("应固定 10 条验收用例").hasSize(10);

        long basic = cases.stream().filter(c -> "basic_recommend".equals(c.get("category"))).count();
        long filter = cases.stream().filter(c -> "filter_by_attr".equals(c.get("category"))).count();
        assertThat(basic).as("5 条基础推荐").isEqualTo(5);
        assertThat(filter).as("5 条条件筛选").isEqualTo(5);

        // 每条用例都应该有 id / query / expectedSpuRefs
        Set<String> ids = cases.stream().map(c -> (String) c.get("id")).collect(java.util.stream.Collectors.toSet());
        assertThat(ids).as("id 必须唯一").hasSize(10);
        for (Map<String, Object> aCase : cases) {
            assertThat(aCase).containsKeys("id", "query", "expectedSpuRefs", "intent", "category");
            assertThat((String) aCase.get("query")).isNotBlank();
            assertThat((List<?>) aCase.get("expectedSpuRefs"))
                    .as("case=%s 至少期望 1 条 SPU 命中", aCase.get("id"))
                    .isNotEmpty();
        }
    }
}
