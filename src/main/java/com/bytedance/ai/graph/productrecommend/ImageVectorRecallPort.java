package com.bytedance.ai.graph.productrecommend;

import com.bytedance.ai.graph.session.UnifiedQueryContext;
import java.util.List;

public interface ImageVectorRecallPort {

    List<ProductRecallCandidate> recallByImage(UnifiedQueryContext queryContext, int limit);
}
