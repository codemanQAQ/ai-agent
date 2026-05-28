package com.bytedance.ai.graph.cartmanage.adapter;

import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogSkuView;
import com.bytedance.ai.graph.catalog.api.CatalogSpuView;
import com.bytedance.ai.graph.cartmanage.ProductCandidate;
import com.bytedance.ai.graph.cartmanage.ProductCatalogResolver;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ProductSearchCatalogResolver implements ProductCatalogResolver {

    private final CatalogQueryFacade catalogQueryFacade;

    public ProductSearchCatalogResolver(CatalogQueryFacade catalogQueryFacade) {
        this.catalogQueryFacade = catalogQueryFacade;
    }

    @Override
    public List<ProductCandidate> searchCandidates(String productName, int limit) {
        int safeLimit = limit <= 0 ? 5 : Math.min(limit, 20);
        List<ProductCandidate> candidates = new ArrayList<>();
        for (CatalogSpuView spu : catalogQueryFacade.searchActiveSpus(productName, safeLimit)) {
            List<CatalogSkuView> skus = spu.skus() == null ? List.of() : spu.skus();
            if (skus.isEmpty()) {
                candidates.add(candidate(spu, null));
            } else {
                for (CatalogSkuView sku : skus) {
                    candidates.add(candidate(spu, sku));
                    if (candidates.size() >= safeLimit) {
                        return List.copyOf(candidates);
                    }
                }
            }
        }
        return List.copyOf(candidates);
    }

    private ProductCandidate candidate(CatalogSpuView spu, CatalogSkuView sku) {
        return new ProductCandidate(
                String.valueOf(spu.id()),
                sku == null ? null : String.valueOf(sku.id()),
                spu.title(),
                price(spu, sku),
                brief(spu),
                spec(sku),
                spu.externalRef()
        );
    }

    private BigDecimal price(CatalogSpuView spu, CatalogSkuView sku) {
        if (sku != null && sku.price() != null) {
            return sku.price();
        }
        return spu.priceMin() != null ? spu.priceMin() : spu.priceMax();
    }

    private String brief(CatalogSpuView spu) {
        if (spu.brand() != null && !spu.brand().isBlank()) {
            return spu.brand();
        }
        return spu.categoryPath();
    }

    private String spec(CatalogSkuView sku) {
        if (sku == null || sku.specJson() == null || sku.specJson().isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, Object> entry : sku.specJson().entrySet()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.toString();
    }
}
