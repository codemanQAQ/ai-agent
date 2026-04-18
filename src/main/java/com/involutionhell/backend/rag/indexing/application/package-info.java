/**
 * Indexing application layer.
 *
 * <p>This package coordinates indexing use cases, application events, outbox dispatch, recovery,
 * timeline assembly, and controller-facing orchestration. It should call into workflow and
 * supporting services, but it should not expose persistence details to other modules.
 */
package com.involutionhell.backend.rag.indexing.application;
