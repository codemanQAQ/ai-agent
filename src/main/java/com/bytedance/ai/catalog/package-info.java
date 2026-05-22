/**
 * Catalog 模块：电商商品域（SPU / SKU / 价格 / 库存 / LLM 抽取的结构化属性）。
 *
 * <p>导入与变更会双写一行 {@code rag_documents}（{@code source_type=catalog-spu}），
 * 借助 document 模块对外发布的 {@code DocumentIndexRequestedEvent} 自动触发既有索引链路；
 * indexing 模块无需感知 catalog 的存在。
 *
 * <p>属性抽取走模块内部异步事件 {@code CatalogAttributeExtractRequestedEvent}，
 * 由 worker 调 LLM 回填 {@code attributes_json}，为后续反选/对比/场景化推荐提供数据基础。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "RAG Catalog",
        allowedDependencies = {"common", "shared", "document::api"}
)
package com.bytedance.ai.catalog;
