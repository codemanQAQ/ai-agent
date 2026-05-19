/**
 * Technical infrastructure shell for the RAG system.
 *
 * <p>This module owns framework-facing concerns such as bean wiring, scheduling, runtime hints,
 * and integration configuration. It may depend on public contracts of business modules, but it
 * should not become a place for cross-module business orchestration.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "RAG Infrastructure",
        allowedDependencies = {
                "common",
                "shared",
                "document::api",
                "indexing::messaging",
                "indexing::api",
                "retrieval::api"
        },
        type = org.springframework.modulith.ApplicationModule.Type.OPEN
)
package com.involutionhell.backend.rag.infrastructure;
