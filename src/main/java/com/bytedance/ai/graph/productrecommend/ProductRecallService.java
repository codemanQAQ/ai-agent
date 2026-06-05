package com.bytedance.ai.graph.productrecommend;

import java.util.List;

public interface ProductRecallService {

    ProductRecallSource source();

    List<ProductRecallCandidate> recall(ProductRecallRequest request);
}
