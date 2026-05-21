@org.springframework.modulith.ApplicationModule(
        displayName = "RAG Indexing",
        allowedDependencies = {"common", "shared", "document::api", "document::spi"}
)
package com.bytedance.ai.indexing;
