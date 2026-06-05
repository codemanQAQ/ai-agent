/**
 * Internal collaboration contract exported by the retrieval module.
 *
 * <p>This named interface is reserved for upper-layer modules (agent, ...) that need to
 * issue product searches or drive multi-turn conversation state without depending on
 * retrieval's persistence types or service classes directly.
 *
 * <p>Other modules must depend on {@code retrieval::spi} explicitly via Modulith
 * {@code allowedDependencies}; concrete adapters live in {@code retrieval.application}.
 */
@org.springframework.modulith.NamedInterface("spi")
package com.bytedance.ai.retrieval.spi;
