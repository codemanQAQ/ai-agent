@org.springframework.modulith.ApplicationModule(
        displayName = "RAG Indexing",
        allowedDependencies = {"common", "rag.shared", "rag.document::api", "rag.document::spi"}
)
package com.involutionhell.backend.rag.indexing;
