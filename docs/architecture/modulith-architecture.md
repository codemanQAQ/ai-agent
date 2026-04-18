# RAG Modulith Architecture

## Overview

This repository keeps a single Maven module and a single deployable Spring Boot application.
The internal structure is organized as a Spring Modulith so that business boundaries can be
enforced without splitting the build into multiple projects too early.

Current logical modules:

- `common`
- `rag.shared`
- `rag.document`
- `rag.indexing`
- `rag.retrieval`
- `rag.infrastructure`

## Module Responsibilities

### `common`

Cross-module response and exception abstractions that are stable and not specific to RAG
business workflows.

### `rag.shared`

Stable shared building blocks used by multiple modules:

- configuration properties
- metadata parsing
- markdown parsing
- shared enums and small support utilities

`rag.shared` is intentionally open because it provides foundational value objects and helpers,
not orchestration logic.

### `rag.document`

Owns the document lifecycle:

- create, update, delete, reindex entrypoints
- document read model for controllers
- publication of indexing request events
- persistence of `rag_documents`

Public surfaces:

- `rag.document::api`
- `rag.document::spi`

Internal packages such as `application`, `persistence`, and `web` are implementation details.

### `rag.indexing`

Owns the indexing workflow and operational pipeline:

- indexing command/query facades
- chunk persistence and read-side lookup
- workflow state machine and audit trail
- outbox and MQ dispatch
- Milvus write/delete integration
- recovery and failure notification
- malformed-message audit and outbox reconciliation

Public surfaces:

- `rag.indexing::api`
- `rag.indexing::messaging`

`rag.indexing::messaging` is exported only because infrastructure-native runtime hints need
message payload metadata. Business modules should still prefer `rag.indexing::api`.

### `rag.retrieval`

Owns the online ask/retrieve/answer flow:

- ask facade
- query transformation and expansion
- keyword, semantic, and hybrid retrieval
- answer generation
- retrieval-specific observability

The retrieval module only reads indexed data through `rag.indexing::api`.

### `rag.infrastructure`

Owns technical assembly, not business orchestration:

- configuration properties registration and optional bean wiring
- schedulers
- RocketMQ producer configuration
- runtime hints
- RAG-scoped exception advice

This module is allowed to reference business modules only to wire technical infrastructure
around their public contracts.

## Dependency Rules

The intended dependency direction is:

- `rag.document -> common, rag.shared`
- `rag.indexing -> common, rag.shared, rag.document::api, rag.document::spi`
- `rag.retrieval -> common, rag.shared, rag.indexing::api`
- `rag.infrastructure -> common, rag.shared, rag.document::api, rag.indexing::api, rag.indexing::messaging, rag.retrieval::api`

Anything outside those contracts should be treated as an architectural regression.

## Why `document.spi` Exists

`rag.indexing` needs document information, but it should not depend directly on
`rag.document.persistence`.

`document.spi` acts as the internal collaboration contract between the modules:

- `document` keeps ownership of document persistence
- `indexing` depends on a stable capability contract
- repository/table details stay hidden behind the adapter in `rag.document`

This gives cleaner module verification, easier testing, and a safer path if document storage
changes later.

## Package-Level Layering Inside `rag.indexing`

`rag.indexing` is still a large module, so its internal packages are intentionally separated:

- `api`: contracts used by other modules
- `application`: use cases and orchestration entrypoints
- `workflow`: state machine, guards, projection, audit
- `service`: domain-supporting technical services such as chunking and vector write logic
- `messaging`: MQ payloads, listeners, and publishers
- `persistence`: repository contracts and records
- `notification`: operational notifications
- `web`: controllers

The most important internal rule is:

- `workflow` should not become a second application service layer
- `application` should coordinate use cases and call `workflow`
- `service` should provide supporting operations, not cross-module orchestration

## Event Flow

1. `rag.document` accepts a document command.
2. `rag.document` publishes `DocumentIndexRequestedEvent` or `DocumentIndexCleanupRequestedEvent`.
3. `rag.indexing` listens after commit and turns the event into an indexing command.
4. `rag.indexing` dispatches directly or through outbox + RocketMQ.
5. `rag.indexing` runs chunking, vector indexing, workflow projection, and audit.
6. `rag.retrieval` reads searchable chunks only through `rag.indexing::api`.

## RocketMQ Failure Handling

When RocketMQ is enabled, the indexing module applies the following failure strategy:

- message payload parse failures are audited into `rag_index_message_failures`
- the raw payload is stored as Base64 together with a shortened preview and message properties
- parse failures are retried through MQ until `rag.rocket-mq.parse-failure-alert-threshold` is reached
- once that threshold is reached, the application acknowledges the message and emits an explicit error log for manual intervention

Relevant configuration:

- `rag.rocket-mq.parse-failure-alert-threshold`
- `rag.rocket-mq.parse-failure-payload-preview-length`

This prevents silent drops while also avoiding endless retry loops for permanently malformed payloads.

## Outbox Sent-State Reconciliation

The outbox path distinguishes between two different failure modes:

- publish failure: the MQ send did not complete, so the outbox record becomes `FAILED` and the workflow is pushed back through `RETRY`
- sent-state persistence failure: the message may already have been published, but `markSent` failed locally; in that case the outbox record remains `SENDING` temporarily

For stuck `SENDING` rows, recovery does not blindly re-send. It first checks whether delivery is already observable from job state such as:

- `messageId`
- `attemptCount`
- `startedAt`
- `finishedAt`

If delivery evidence exists, the outbox row is marked `SENT`; otherwise it is reset for retry.

This reduces the risk of duplicate MQ publication after a successful send but failed local confirmation.

## Verification

Architecture is guarded by:

- `ApplicationModulesVerificationTests`
- `ApplicationModulesDocumentationTests`
- module-focused tests based on `@ApplicationModuleTest`

Run:

```bash
./mvnw clean test
```
