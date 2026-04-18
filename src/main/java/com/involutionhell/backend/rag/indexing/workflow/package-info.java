/**
 * Indexing workflow engine internals.
 *
 * <p>This package contains the state machine, guards, projectors, and audit support that govern
 * indexing state transitions. It is intentionally internal to the indexing module and should be
 * driven by the application layer rather than used as a second public orchestration entrypoint.
 */
package com.involutionhell.backend.rag.indexing.workflow;
