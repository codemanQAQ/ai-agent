package com.bytedance.ai.catalog.application;

import com.bytedance.ai.catalog.api.CatalogAttributeExtractRequestedEvent;
import com.bytedance.ai.catalog.api.CatalogCommandFacade;
import com.bytedance.ai.catalog.api.CatalogImportRequest;
import com.bytedance.ai.catalog.api.CatalogImportSummary;
import com.bytedance.ai.catalog.api.CatalogSpuCreateRequest;
import com.bytedance.ai.catalog.persistence.CatalogSpuRecord;
import com.bytedance.ai.catalog.persistence.CatalogSpuRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Catalog 写入侧 Facade 实现。
 *
 * <p>本服务只负责编排：循环遍历批量请求，委托 {@link CatalogImportService} 走单条事务。
 * 单条失败的异常会被捕获并归集到 {@link CatalogImportSummary#failures()}，
 * 不会影响其它条目；这种设计配合 REQUIRES_NEW 让导入具备「部分成功」语义。
 */
@Service
class CatalogCommandService implements CatalogCommandFacade {

    private static final Logger log = LoggerFactory.getLogger(CatalogCommandService.class);
    private static final String MANUAL_TRIGGER = "manual-retry";

    private final CatalogImportService catalogImportService;
    private final CatalogSpuRepository spuRepository;
    private final ApplicationEventPublisher eventPublisher;

    CatalogCommandService(
            CatalogImportService catalogImportService,
            CatalogSpuRepository spuRepository,
            ApplicationEventPublisher eventPublisher
    ) {
        this.catalogImportService = catalogImportService;
        this.spuRepository = spuRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public CatalogImportSummary importBatch(CatalogImportRequest request) {
        List<CatalogSpuCreateRequest> items = request.items();
        List<Long> succeededIds = new ArrayList<>(items.size());
        List<CatalogImportSummary.Failure> failures = new ArrayList<>();

        for (CatalogSpuCreateRequest item : items) {
            try {
                Long spuId = catalogImportService.importOne(item);
                succeededIds.add(spuId);
            } catch (RuntimeException exception) {
                log.warn(
                        "catalog import single SPU failed: externalRef={}, reason={}",
                        item.externalRef(),
                        exception.getMessage()
                );
                failures.add(new CatalogImportSummary.Failure(item.externalRef(), exception.getMessage()));
            }
        }
        log.info(
                "catalog import batch completed: total={}, succeeded={}, failed={}",
                items.size(),
                succeededIds.size(),
                failures.size()
        );
        return new CatalogImportSummary(items.size(), succeededIds.size(), failures.size(), succeededIds, failures);
    }

    @Override
    public void requestAttributeExtraction(Long spuId) {
        CatalogSpuRecord record = spuRepository.findById(spuId)
                .orElseThrow(() -> new IllegalArgumentException("catalog_spu 不存在: " + spuId));
        eventPublisher.publishEvent(new CatalogAttributeExtractRequestedEvent(record.id(), MANUAL_TRIGGER));
        log.info(
                "catalog attribute extraction requested manually: spuId={}, externalRef={}",
                spuId,
                record.externalRef()
        );
    }
}
