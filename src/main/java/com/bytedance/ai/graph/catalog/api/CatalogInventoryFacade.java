package com.bytedance.ai.graph.catalog.api;

public interface CatalogInventoryFacade {

    void decreaseStock(Long spuId, int quantity);
}
