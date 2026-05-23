@org.springframework.modulith.ApplicationModule(
        displayName = "RAG Retrieval",
        allowedDependencies = {"common", "shared", "indexing::api", "catalog::api"}
)
package com.bytedance.ai.retrieval;
