package com.bytedance.ai.catalog.application;

import com.bytedance.ai.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.catalog.api.CatalogSkuView;
import com.bytedance.ai.catalog.api.CatalogSpuView;
import com.bytedance.ai.catalog.persistence.CatalogSkuRecord;
import com.bytedance.ai.catalog.persistence.CatalogSkuRepository;
import com.bytedance.ai.catalog.persistence.CatalogSpuRecord;
import com.bytedance.ai.catalog.persistence.CatalogSpuRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
class CatalogQueryService implements CatalogQueryFacade {

    private final CatalogSpuRepository spuRepository;
    private final CatalogSkuRepository skuRepository;

    CatalogQueryService(CatalogSpuRepository spuRepository, CatalogSkuRepository skuRepository) {
        this.spuRepository = spuRepository;
        this.skuRepository = skuRepository;
    }

    @Override
    public CatalogSpuView getSpu(Long spuId) {
        CatalogSpuRecord record = spuRepository.findById(spuId)
                .orElseThrow(() -> new IllegalArgumentException("catalog_spu 不存在: " + spuId));
        List<CatalogSkuView> skuViews = skuRepository.findBySpuId(spuId).stream()
                .map(CatalogQueryService::toSkuView)
                .toList();
        return toSpuView(record, skuViews);
    }

    @Override
    public List<CatalogSkuView> listSkus(Long spuId) {
        return skuRepository.findBySpuId(spuId).stream()
                .map(CatalogQueryService::toSkuView)
                .toList();
    }

    private static CatalogSpuView toSpuView(CatalogSpuRecord record, List<CatalogSkuView> skus) {
        return new CatalogSpuView(
                record.id(),
                record.externalRef(),
                record.title(),
                record.brand(),
                record.categoryPath(),
                record.priceMin(),
                record.priceMax(),
                record.stock(),
                record.descriptionMd(),
                record.images(),
                record.videoUrl(),
                record.attributesJson(),
                record.attributesStatus(),
                record.status(),
                record.documentId(),
                skus,
                record.createdAt(),
                record.updatedAt()
        );
    }

    private static CatalogSkuView toSkuView(CatalogSkuRecord record) {
        return new CatalogSkuView(
                record.id(),
                record.skuCode(),
                record.specJson(),
                record.price(),
                record.stock(),
                record.status()
        );
    }
}
