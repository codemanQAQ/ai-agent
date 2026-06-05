/**
 * Catalog 模块对外暴露的公共契约（命令/查询 Facade、视图 DTO、请求 DTO、模块间事件）。
 *
 * <p>其他模块只能依赖本包内的类型；catalog 内部实现细节（persistence / application / service / web）
 * 不对外可见，由 Spring Modulith 边界检查强制约束。
 */
@org.springframework.modulith.NamedInterface("api")
package com.bytedance.ai.graph.catalog.api;
