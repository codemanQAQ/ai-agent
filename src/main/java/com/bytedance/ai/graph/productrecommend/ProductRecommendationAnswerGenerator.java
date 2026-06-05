package com.bytedance.ai.graph.productrecommend;

import java.util.Map;
import java.util.Optional;
import reactor.core.publisher.Flux;

public interface ProductRecommendationAnswerGenerator {

    Optional<String> generate(Map<String, Object> answerContext, String fallbackAnswer);

    /**
     * 流式生成推荐文案，逐 token 返回增量内容。默认返回空流（调用方应回退到确定性文案）。
     * 由 SSE 层消费以降低首字延迟。
     */
    default Flux<String> generateStream(Map<String, Object> answerContext, String fallbackAnswer) {
        return Flux.empty();
    }
}
