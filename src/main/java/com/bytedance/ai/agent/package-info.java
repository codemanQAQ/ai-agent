/**
 * RAG Agent 模块：面向电商导购的一轮式编排入口。
 *
 * <p>W1 只做规则意图、确定性工具调用、SSE 流式响应与独立 {@code agent_turn} 审计表；
 * 多轮记忆复用 retrieval 暴露的会话 SPI，商品卡片复用 catalog 查询接口。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "RAG Agent",
        allowedDependencies = {
                "common",
                "shared",
                "catalog::api",
                "retrieval::api",
                "retrieval::spi"
        }
)
package com.bytedance.ai.agent;
