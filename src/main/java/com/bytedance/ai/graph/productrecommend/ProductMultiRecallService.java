package com.bytedance.ai.graph.productrecommend;

import com.bytedance.ai.graph.session.UnifiedQueryContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ProductMultiRecallService {

    private final List<ProductRecallService> recallServices;

    public ProductMultiRecallService(List<ProductRecallService> recallServices) {
        this.recallServices = recallServices == null
                ? List.of()
                : recallServices.stream()
                .sorted(Comparator.comparing(service -> service.source().name()))
                .toList();
    }

    public List<ProductRecallCandidate> recall(UnifiedQueryContext queryContext, int perSourceLimit) {
        ProductRecallRequest request = new ProductRecallRequest(queryContext, perSourceLimit);
        List<ProductRecallCandidate> candidates = new ArrayList<>();
        for (ProductRecallService service : recallServices) {
            candidates.addAll(service.recall(request));
        }
        return List.copyOf(candidates);
    }

    public List<ProductRecallCandidate> recall(UnifiedQueryContext queryContext, ProductRecallPlan plan) {
        if (plan == null) {
            return recall(queryContext, 10);
        }
        Set<ProductRecallSource> enabledSources = Set.copyOf(plan.enabledSources());
        ProductRecallRequest request = new ProductRecallRequest(queryContext, plan.perSourceLimit(), plan);
        List<ProductRecallCandidate> candidates = new ArrayList<>();
        for (ProductRecallService service : recallServices) {
            if (enabledSources.contains(service.source())) {
                candidates.addAll(service.recall(request));
            }
        }
        return List.copyOf(candidates);
    }
}
