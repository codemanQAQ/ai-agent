package com.bytedance.ai.graph.catalog.application;

import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogSkuView;
import com.bytedance.ai.graph.catalog.api.CatalogSpuView;
import com.bytedance.ai.graph.catalog.persistence.CatalogSkuRecord;
import com.bytedance.ai.graph.catalog.persistence.CatalogSkuRepository;
import com.bytedance.ai.graph.catalog.persistence.CatalogSpuRecord;
import com.bytedance.ai.graph.catalog.persistence.CatalogSpuRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

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
    public Optional<CatalogSpuView> findSpuByExternalRef(String externalRef) {
        if (externalRef == null || externalRef.isBlank()) {
            return Optional.empty();
        }
        return spuRepository.findByExternalRef(externalRef).map(record -> {
            List<CatalogSkuView> skuViews = skuRepository.findBySpuId(record.id()).stream()
                    .map(CatalogQueryService::toSkuView)
                    .toList();
            return toSpuView(record, skuViews);
        });
    }

    @Override
    public List<CatalogSpuView> searchActiveSpus(String keyword, int limit) {
        int safeLimit = limit <= 0 ? 5 : Math.min(limit, 20);
        return spuRepository.searchActiveByKeyword(keyword, safeLimit).stream()
                .map(record -> {
                    List<CatalogSkuView> skuViews = skuRepository.findBySpuId(record.id()).stream()
                            .map(CatalogQueryService::toSkuView)
                            .toList();
                    return toSpuView(record, skuViews);
                })
                .toList();
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
