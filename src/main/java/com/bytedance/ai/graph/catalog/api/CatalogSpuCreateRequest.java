package com.bytedance.ai.graph.catalog.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 单个 SPU 的创建请求。
 *
 * @param externalRef    业务编号（在 catalog_spu 内全局唯一）
 * @param title          标题
 * @param brand          品牌
 * @param categoryPath   类目路径
 * @param priceMin       展示价下界
 * @param priceMax       展示价上界
 * @param stock          总库存
 * @param descriptionMd  长描述 Markdown
 * @param images         图片 URL 列表
 * @param videoUrl       视频地址
 * @param skus           SKU 列表（至少 1 条）
 */
public record CatalogSpuCreateRequest(
        @NotBlank(message = "外部编号不能为空")
        @Size(max = 64, message = "外部编号长度不能超过 64 个字符")
        String externalRef,

        @NotBlank(message = "标题不能为空")
        @Size(max = 200, message = "标题长度不能超过 200 个字符")
        String title,

        @Size(max = 64, message = "品牌长度不能超过 64 个字符")
        String brand,

        @Size(max = 255, message = "类目路径长度不能超过 255 个字符")
        String categoryPath,

        @DecimalMin(value = "0.0", message = "价格下界不能为负")
        BigDecimal priceMin,

        @DecimalMin(value = "0.0", message = "价格上界不能为负")
        BigDecimal priceMax,

        @PositiveOrZero(message = "库存不能为负")
        Integer stock,

        String descriptionMd,

        List<String> images,

        String videoUrl,

        @NotEmpty(message = "至少需要一条 SKU")
        @Valid
        List<SkuDraft> skus
) {
    /**
     * SKU 子草稿。
     *
     * @param skuCode  业务编码
     * @param specJson 规格 KV
     * @param price    价格
     * @param stock    库存
     */
    public record SkuDraft(
            @NotBlank(message = "SKU 编码不能为空")
            @Size(max = 64, message = "SKU 编码长度不能超过 64 个字符")
            String skuCode,

            Map<String, Object> specJson,

            @DecimalMin(value = "0.0", message = "SKU 价格不能为负")
            BigDecimal price,

            @PositiveOrZero(message = "SKU 库存不能为负")
            Integer stock
    ) {
    }
}
