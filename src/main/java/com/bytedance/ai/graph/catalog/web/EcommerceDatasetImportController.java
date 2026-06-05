package com.bytedance.ai.graph.catalog.web;

import com.bytedance.ai.common.api.ApiResponse;
import com.bytedance.ai.graph.catalog.api.CatalogImportSummary;
import com.bytedance.ai.graph.catalog.application.EcommerceDatasetImportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;

/**
 * Admin endpoint for importing the local ecommerce_agent_dataset directory.
 */
@RestController
@Validated
@RequestMapping(value = "/admin/catalog/import/ecommerce-dataset",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
public class EcommerceDatasetImportController {

    private final EcommerceDatasetImportService importService;

    public EcommerceDatasetImportController(
            EcommerceDatasetImportService importService
    ) {
        this.importService = importService;
    }

    @PostMapping
    public ApiResponse<CatalogImportSummary> importDataset(@Valid @RequestBody EcommerceDatasetImportRequest request) {
        CatalogImportSummary summary = importService.importDataset(Path.of(request.datasetRoot()));
        String message = summary.failed() == 0
                ? "电商数据集导入成功"
                : "电商数据集部分导入失败：成功 " + summary.succeeded() + " 条 / 失败 " + summary.failed() + " 条";
        return ApiResponse.ok(message, summary);
    }

    public record EcommerceDatasetImportRequest(
            @NotBlank(message = "数据集根目录不能为空")
            @Size(max = 512, message = "数据集根目录长度不能超过 512 个字符")
            String datasetRoot
    ) {
    }
}
