package com.bytedance.ai.catalog.application;

import com.bytedance.ai.catalog.api.CatalogSpuCreateRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * 把 SPU + SKU 渲染成统一的 Markdown 文本，作为双写 rag_documents.content 的载体。
 *
 * <p>渲染规则保持稳定：相同 SPU 字段必须产出相同字节序的内容，
 * 以便 {@code DocumentCommandService} 计算出稳定的 {@code content_sha256}，
 * 让重复导入或幂等重试不会触发不必要的重新索引。
 */
@Component
public class SpuMarkdownRenderer {

    /**
     * 渲染 SPU 主体（不带 SKU 列表）。
     */
    public String render(CatalogSpuCreateRequest spu) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(spu.title()).append("\n\n");

        if (StringUtils.hasText(spu.brand())) {
            sb.append("**品牌**：").append(spu.brand()).append("  ");
        }
        if (StringUtils.hasText(spu.categoryPath())) {
            sb.append("**类目**：").append(spu.categoryPath());
        }
        sb.append("\n");

        String priceLine = renderPriceLine(spu.priceMin(), spu.priceMax());
        if (priceLine != null) {
            sb.append(priceLine).append("\n");
        }

        sb.append("\n## 商品描述\n");
        sb.append(StringUtils.hasText(spu.descriptionMd()) ? spu.descriptionMd().trim() : "(暂无描述)");
        sb.append("\n");

        if (spu.skus() != null && !spu.skus().isEmpty()) {
            sb.append("\n## 规格\n");
            for (CatalogSpuCreateRequest.SkuDraft sku : spu.skus()) {
                sb.append("- ").append(renderSkuLine(sku)).append("\n");
            }
        }

        return sb.toString().trim();
    }

    private String renderPriceLine(BigDecimal priceMin, BigDecimal priceMax) {
        if (priceMin == null && priceMax == null) {
            return null;
        }
        if (Objects.equals(priceMin, priceMax) || priceMax == null) {
            return "**价格**：¥" + priceMin.stripTrailingZeros().toPlainString();
        }
        if (priceMin == null) {
            return "**价格**：¥" + priceMax.stripTrailingZeros().toPlainString();
        }
        return "**价格**：¥" + priceMin.stripTrailingZeros().toPlainString()
                + " ~ ¥" + priceMax.stripTrailingZeros().toPlainString();
    }

    private String renderSkuLine(CatalogSpuCreateRequest.SkuDraft sku) {
        StringBuilder line = new StringBuilder();
        line.append(renderSpec(sku.specJson()));
        if (sku.price() != null) {
            line.append("：¥").append(sku.price().stripTrailingZeros().toPlainString());
        }
        if (sku.stock() != null) {
            line.append("（库存 ").append(sku.stock()).append("）");
        }
        return line.toString();
    }

    private String renderSpec(Map<String, Object> spec) {
        if (spec == null || spec.isEmpty()) {
            return "默认规格";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Object> entry : spec.entrySet()) {
            if (!first) {
                sb.append(" / ");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
            first = false;
        }
        return sb.toString();
    }
}
