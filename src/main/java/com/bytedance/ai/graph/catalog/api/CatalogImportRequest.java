package com.bytedance.ai.graph.catalog.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 批量导入请求：携带若干 SPU 草稿。
 *
 * @param items SPU 列表
 */
public record CatalogImportRequest(
        @NotEmpty(message = "至少需要一条 SPU")
        @Valid
        List<CatalogSpuCreateRequest> items
) {
}
