package com.bytedance.ai.graph.productrecommend;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ImageVectorProductRecallService implements ProductRecallService {

    private final List<ImageVectorRecallPort> recallPorts;

    public ImageVectorProductRecallService(List<ImageVectorRecallPort> recallPorts) {
        this.recallPorts = recallPorts == null ? List.of() : List.copyOf(recallPorts);
    }

    @Override
    public ProductRecallSource source() {
        return ProductRecallSource.IMAGE_VECTOR;
    }

    @Override
    public List<ProductRecallCandidate> recall(ProductRecallRequest request) {
        if (request.queryContext() == null || request.queryContext().imageEmbeddingRef() == null) {
            return List.of();
        }
        return recallPorts.stream()
                .flatMap(port -> port.recallByImage(request.queryContext(), request.limit()).stream())
                .limit(request.limit())
                .toList();
    }
}
