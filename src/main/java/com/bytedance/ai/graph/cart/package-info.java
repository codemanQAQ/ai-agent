/**
 * Cart 模块：会话式购物车、状态机、库存/价格 guard 与转移审计。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "RAG Cart",
        allowedDependencies = {"common", "shared", "graph.catalog::api"}
)
package com.bytedance.ai.graph.cart;
