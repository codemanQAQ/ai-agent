-- Complete PostgreSQL DDL for the RAG backend.
-- Intended for a fresh PostgreSQL database.
-- Cleaned version:
-- 1. Removed owner binding: ALTER TABLE ... OWNER TO neondb_owner.
-- 2. Fixed exported sequence dependencies by using bigserial.
-- 3. Removed PostgreSQL system columns from agent_turn.
-- 4. Added practical foreign keys for fresh database usage.
-- 5. Kept pg_trgm and trigram/FTS indexes.

BEGIN;

-- Optional but recommended for hybrid keyword retrieval.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- =========================================================
-- User accounts
-- =========================================================

CREATE TABLE public.user_accounts
(
    id            bigserial PRIMARY KEY,
    username      varchar(255)             NOT NULL UNIQUE,
    password_hash varchar(255)             NOT NULL,
    display_name  varchar(255),
    enabled       boolean DEFAULT true     NOT NULL,
    roles         text    DEFAULT ''::text NOT NULL,
    permissions   text    DEFAULT ''::text NOT NULL
);

-- =========================================================
-- RAG documents
-- =========================================================

CREATE TABLE public.rag_documents
(
    id                 bigserial PRIMARY KEY,
    source_type        varchar(32)                                  NOT NULL,
    source_uri         text,
    external_ref       varchar(255),
    title              varchar(512),
    content            text                                         NOT NULL,
    content_sha256     char(64)                                     NOT NULL,
    indexed_generation bigint,
    status             varchar(16) DEFAULT 'PENDING'                NOT NULL
        CONSTRAINT rag_documents_status_chk
            CHECK (status IN ('PENDING', 'PROCESSING', 'INDEXED', 'FAILED', 'DELETING')),
    chunk_count        integer DEFAULT 0                            NOT NULL,
    attempt_count      integer DEFAULT 0                            NOT NULL,
    metadata           jsonb   DEFAULT '{}'::jsonb                  NOT NULL,
    last_error         text,
    last_attempted_at  timestamp(6) with time zone,
    indexed_at         timestamp(6) with time zone,
    created_at         timestamp(6) with time zone DEFAULT now()    NOT NULL,
    updated_at         timestamp(6) with time zone DEFAULT now()    NOT NULL
);

CREATE INDEX idx_rag_documents_status
    ON public.rag_documents (status);

CREATE INDEX idx_rag_documents_source_type
    ON public.rag_documents (source_type);

CREATE INDEX idx_rag_documents_external_ref
    ON public.rag_documents (external_ref);

CREATE INDEX idx_rag_documents_source_uri
    ON public.rag_documents (source_uri);

CREATE INDEX idx_rag_documents_indexed_generation
    ON public.rag_documents (indexed_generation);

CREATE INDEX idx_rag_documents_title_fts
    ON public.rag_documents
        USING gin (to_tsvector('simple'::regconfig, COALESCE(title, '')::text));

CREATE INDEX idx_rag_documents_title_trgm
    ON public.rag_documents
        USING gin (lower(COALESCE(title, '')::text) public.gin_trgm_ops);

-- =========================================================
-- RAG index jobs
-- =========================================================

CREATE TABLE public.rag_index_jobs
(
    id                bigserial PRIMARY KEY,
    document_id       bigint                                      NOT NULL,
    content_sha256    char(64)                                    NOT NULL,
    status            varchar(16) DEFAULT 'QUEUED'                NOT NULL
        CONSTRAINT rag_index_jobs_status_chk
            CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    stage             varchar(32) DEFAULT 'QUEUED'                NOT NULL
        CONSTRAINT rag_index_jobs_stage_chk
            CHECK (stage IN (
                             'QUEUED',
                             'DISPATCHING',
                             'PREPARING',
                             'CHUNKING',
                             'SAVE_CHUNKS',
                             'VECTOR_INDEXING',
                             'COMMIT_INDEX',
                             'COMPLETED',
                             'SKIPPED'
                )),
    version           bigint  DEFAULT 0                           NOT NULL,
    last_event        varchar(64),
    attempt_count     integer DEFAULT 0                            NOT NULL,
    target_generation bigint,
    message_id        varchar(128),
    last_error        text,
    started_at        timestamp(6) with time zone,
    finished_at       timestamp(6) with time zone,
    created_at        timestamp(6) with time zone DEFAULT now()    NOT NULL,
    updated_at        timestamp(6) with time zone DEFAULT now()    NOT NULL,
    CONSTRAINT uq_rag_index_jobs_document_sha
        UNIQUE (document_id, content_sha256),
    CONSTRAINT fk_rag_index_jobs_document
        FOREIGN KEY (document_id)
            REFERENCES public.rag_documents (id)
            ON DELETE CASCADE
);

CREATE INDEX idx_rag_index_jobs_status
    ON public.rag_index_jobs (status);

CREATE INDEX idx_rag_index_jobs_stage
    ON public.rag_index_jobs (stage);

CREATE INDEX idx_rag_index_jobs_document_id
    ON public.rag_index_jobs (document_id);

CREATE INDEX idx_rag_index_jobs_document_sha_version
    ON public.rag_index_jobs (document_id, content_sha256, version);

-- =========================================================
-- RAG index job transitions
-- =========================================================

CREATE TABLE public.rag_index_job_transitions
(
    id             bigserial PRIMARY KEY,
    document_id    bigint                                      NOT NULL,
    job_id         bigint,
    outbox_id      bigint,
    content_sha256 char(64)                                    NOT NULL,
    from_state     varchar(32),
    to_state       varchar(32)                                 NOT NULL,
    event          varchar(64)                                 NOT NULL,
    trigger_type   varchar(32)                                 NOT NULL,
    triggered_by   varchar(255),
    success        boolean DEFAULT true                        NOT NULL,
    failure_reason varchar(64),
    error_message  text,
    message_id     varchar(128),
    metadata       jsonb   DEFAULT '{}'::jsonb                 NOT NULL,
    created_at     timestamp(6) with time zone DEFAULT now()   NOT NULL,
    CONSTRAINT fk_rag_index_job_transitions_document
        FOREIGN KEY (document_id)
            REFERENCES public.rag_documents (id)
            ON DELETE CASCADE,
    CONSTRAINT fk_rag_index_job_transitions_job
        FOREIGN KEY (job_id)
            REFERENCES public.rag_index_jobs (id)
            ON DELETE SET NULL
);

CREATE INDEX idx_rag_index_job_transitions_document_created
    ON public.rag_index_job_transitions (document_id, created_at);

CREATE INDEX idx_rag_index_job_transitions_document_sha_created
    ON public.rag_index_job_transitions (document_id, content_sha256, created_at);

-- =========================================================
-- RAG index outbox
-- =========================================================

CREATE TABLE public.rag_index_outbox
(
    id              bigserial PRIMARY KEY,
    document_id     bigint                                    NOT NULL,
    content_sha256  char(64)                                  NOT NULL,
    event_type      varchar(32)                               NOT NULL,
    status          varchar(16) DEFAULT 'NEW'                 NOT NULL
        CONSTRAINT rag_index_outbox_status_chk
            CHECK (status IN ('NEW', 'SENDING', 'SENT', 'FAILED')),
    attempt_count   integer DEFAULT 0                         NOT NULL,
    message_id      varchar(128),
    last_error      text,
    next_attempt_at timestamp(6) with time zone,
    dispatched_at   timestamp(6) with time zone,
    consumed_at     timestamp(6) with time zone,
    created_at      timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at      timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT uq_rag_index_outbox_document_sha_event
        UNIQUE (document_id, content_sha256, event_type),
    CONSTRAINT fk_rag_index_outbox_document
        FOREIGN KEY (document_id)
            REFERENCES public.rag_documents (id)
            ON DELETE CASCADE
);

CREATE INDEX idx_rag_index_outbox_status_next_attempt
    ON public.rag_index_outbox (status, next_attempt_at);

CREATE INDEX idx_rag_index_outbox_document_id
    ON public.rag_index_outbox (document_id);

CREATE INDEX idx_rag_index_outbox_message_id
    ON public.rag_index_outbox (message_id);

-- Add optional FK from transitions to outbox after outbox exists.
ALTER TABLE public.rag_index_job_transitions
    ADD CONSTRAINT fk_rag_index_job_transitions_outbox
        FOREIGN KEY (outbox_id)
            REFERENCES public.rag_index_outbox (id)
            ON DELETE SET NULL;

-- =========================================================
-- RAG index message failures
-- =========================================================

CREATE TABLE public.rag_index_message_failures
(
    id               bigserial PRIMARY KEY,
    message_id       varchar(128)                              NOT NULL,
    topic            varchar(255)                              NOT NULL,
    delivery_attempt integer                                   NOT NULL,
    failure_type     varchar(32)                               NOT NULL,
    error_message    text,
    payload_base64   text,
    payload_preview  text,
    properties_json  jsonb DEFAULT '{}'::jsonb                 NOT NULL,
    created_at       timestamp(6) with time zone DEFAULT now() NOT NULL
);

CREATE INDEX idx_rag_index_message_failures_message_created
    ON public.rag_index_message_failures (message_id, created_at);

-- =========================================================
-- RAG chunks
-- =========================================================

CREATE TABLE public.rag_chunks
(
    id               bigserial PRIMARY KEY,
    document_id      bigint                                    NOT NULL,
    index_generation bigint  DEFAULT 1                         NOT NULL,
    chunk_index      integer                                   NOT NULL,
    chunk_text       text                                      NOT NULL,
    chunk_hash       char(64)                                  NOT NULL,
    char_count       integer DEFAULT 0                         NOT NULL,
    token_count      integer,
    vector_id        varchar(128),
    metadata         jsonb   DEFAULT '{}'::jsonb               NOT NULL,
    created_at       timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at       timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT fk_rag_chunks_document
        FOREIGN KEY (document_id)
            REFERENCES public.rag_documents (id)
            ON DELETE CASCADE
);

CREATE UNIQUE INDEX idx_rag_chunks_document_generation_chunk_unique
    ON public.rag_chunks (document_id, index_generation, chunk_index);

CREATE INDEX idx_rag_chunks_document_id
    ON public.rag_chunks (document_id);

CREATE INDEX idx_rag_chunks_document_generation
    ON public.rag_chunks (document_id, index_generation, chunk_index);

CREATE INDEX idx_rag_chunks_vector_id
    ON public.rag_chunks (vector_id);

CREATE INDEX idx_rag_chunks_chunk_hash
    ON public.rag_chunks (chunk_hash);

CREATE INDEX idx_rag_chunks_chunk_text_fts
    ON public.rag_chunks
        USING gin (to_tsvector('simple'::regconfig, COALESCE(chunk_text, ''::text)));

CREATE INDEX idx_rag_chunks_heading_path_text_fts
    ON public.rag_chunks
        USING gin (to_tsvector('simple'::regconfig, COALESCE(metadata ->> 'headingPathText', ''::text)));

CREATE INDEX idx_rag_chunks_chunk_text_trgm
    ON public.rag_chunks
        USING gin (lower(COALESCE(chunk_text, ''::text)) public.gin_trgm_ops);

CREATE INDEX idx_rag_chunks_heading_path_text_trgm
    ON public.rag_chunks
        USING gin (lower(COALESCE(metadata ->> 'headingPathText', ''::text)) public.gin_trgm_ops);

-- =========================================================
-- RAG embedding cache
-- =========================================================

CREATE TABLE public.rag_embedding_cache
(
    chunk_hash          char(64)                                  NOT NULL,
    embedding_model     varchar(255)                              NOT NULL,
    embedding_dimension integer                                   NOT NULL,
    embedding_json      text                                      NOT NULL,
    created_at          timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at          timestamp(6) with time zone DEFAULT now() NOT NULL,
    PRIMARY KEY (chunk_hash, embedding_model, embedding_dimension)
);

CREATE INDEX idx_rag_embedding_cache_model
    ON public.rag_embedding_cache (embedding_model, embedding_dimension);

-- =========================================================
-- RAG users
-- =========================================================

CREATE TABLE public.rag_users
(
    id            bigserial PRIMARY KEY,
    user_id       varchar(128)                              NOT NULL UNIQUE,
    metadata      jsonb DEFAULT '{}'::jsonb                 NOT NULL,
    first_seen_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    last_seen_at  timestamp(6) with time zone DEFAULT now() NOT NULL
);

-- =========================================================
-- Agent conversations
-- =========================================================

CREATE TABLE public.agent_conversations
(
    id              bigserial PRIMARY KEY,
    conversation_id varchar(128)                              NOT NULL UNIQUE,
    user_id         varchar(128)                              NOT NULL,
    title           varchar(200),
    status          varchar(16) DEFAULT 'ACTIVE'              NOT NULL
        CONSTRAINT rag_conversations_status_chk
            CHECK (status IN ('ACTIVE', 'ARCHIVED', 'DELETED')),
    message_count   integer DEFAULT 0                         NOT NULL,
    metadata        jsonb   DEFAULT '{}'::jsonb               NOT NULL,
    created_at      timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at      timestamp(6) with time zone DEFAULT now() NOT NULL,
    last_message_at timestamp(6) with time zone
);

CREATE INDEX idx_rag_conversations_user_cursor
    ON public.agent_conversations (user_id ASC, last_message_at DESC, id DESC);

-- =========================================================
-- Agent conversation messages
-- =========================================================

CREATE TABLE public.agent_conversation_messages
(
    id              bigserial PRIMARY KEY,
    message_id      varchar(128)                              NOT NULL UNIQUE,
    conversation_id bigint                                    NOT NULL,
    role            varchar(16)                               NOT NULL
        CONSTRAINT rag_messages_role_chk
            CHECK (role IN ('user', 'assistant', 'system')),
    content         text                                      NOT NULL,
    status          varchar(16) DEFAULT 'SUCCEEDED'           NOT NULL
        CONSTRAINT rag_messages_status_chk
            CHECK (status IN ('PENDING', 'STREAMING', 'SUCCEEDED', 'FAILED')),
    token_count     integer,
    correlation_id  varchar(128),
    sequence_no     integer                                   NOT NULL,
    metadata        jsonb DEFAULT '{}'::jsonb                 NOT NULL,
    created_at      timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT uq_rag_messages_conversation_sequence
        UNIQUE (conversation_id, sequence_no),
    CONSTRAINT fk_agent_conversation_messages_conversation
        FOREIGN KEY (conversation_id)
            REFERENCES public.agent_conversations (id)
            ON DELETE CASCADE
);

CREATE INDEX idx_rag_messages_conversation_sequence
    ON public.agent_conversation_messages (conversation_id, sequence_no);

-- =========================================================
-- RAG ask runs
-- =========================================================

CREATE TABLE public.rag_ask_runs
(
    id                   bigserial PRIMARY KEY,
    run_id               varchar(128)                              NOT NULL UNIQUE,
    correlation_id       varchar(128)                              NOT NULL UNIQUE,
    user_id              varchar(128)                              NOT NULL,
    conversation_id      bigint                                    NOT NULL,
    user_message_id      bigint,
    assistant_message_id bigint,
    request_id           varchar(128),
    question             text                                      NOT NULL,
    retrieval_question   text,
    top_k                integer,
    filters              jsonb DEFAULT '{}'::jsonb                 NOT NULL,
    retrieval_queries    jsonb DEFAULT '[]'::jsonb                 NOT NULL,
    retrieved_contexts   jsonb DEFAULT '[]'::jsonb                 NOT NULL,
    notices              jsonb DEFAULT '[]'::jsonb                 NOT NULL,
    generated_by_model   boolean DEFAULT false                     NOT NULL,
    degraded             boolean DEFAULT false                     NOT NULL,
    status               varchar(16) DEFAULT 'RUNNING'             NOT NULL
        CONSTRAINT rag_ask_runs_status_chk
            CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    error_code           varchar(64),
    error_message        text,
    started_at           timestamp(6) with time zone DEFAULT now() NOT NULL,
    completed_at         timestamp(6) with time zone,
    CONSTRAINT fk_rag_ask_runs_conversation
        FOREIGN KEY (conversation_id)
            REFERENCES public.agent_conversations (id)
            ON DELETE CASCADE,
    CONSTRAINT fk_rag_ask_runs_user_message
        FOREIGN KEY (user_message_id)
            REFERENCES public.agent_conversation_messages (id)
            ON DELETE SET NULL,
    CONSTRAINT fk_rag_ask_runs_assistant_message
        FOREIGN KEY (assistant_message_id)
            REFERENCES public.agent_conversation_messages (id)
            ON DELETE SET NULL
);

CREATE INDEX idx_rag_ask_runs_user_started
    ON public.rag_ask_runs (user_id ASC, started_at DESC);

CREATE INDEX idx_rag_ask_runs_conversation_started
    ON public.rag_ask_runs (conversation_id ASC, started_at DESC);

CREATE UNIQUE INDEX uq_rag_ask_runs_request
    ON public.rag_ask_runs (user_id, conversation_id, request_id)
    WHERE request_id IS NOT NULL;

-- =========================================================
-- Shopping cart
-- =========================================================

CREATE TABLE public.shopping_cart
(
    id                    bigserial PRIMARY KEY,
    cart_id               varchar(64)                               NOT NULL UNIQUE,
    user_id               varchar(64)                               NOT NULL,
    conversation_id       varchar(64)                               NOT NULL,
    state                 varchar(32) DEFAULT 'IDLE'                NOT NULL
        CONSTRAINT shopping_cart_state_chk
            CHECK (state IN ('IDLE', 'ITEM_PROPOSED', 'IN_CART', 'CHECKING_OUT', 'PLACED', 'CANCELLED')),
    currency              varchar(8) DEFAULT 'CNY'                  NOT NULL,
    subtotal_amount       numeric(12, 2) DEFAULT 0                  NOT NULL,
    item_count            integer DEFAULT 0                         NOT NULL,
    shipping_address_json jsonb DEFAULT '{}'::jsonb                 NOT NULL,
    version               bigint DEFAULT 0                          NOT NULL,
    created_at            timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at            timestamp(6) with time zone DEFAULT now() NOT NULL
);

CREATE INDEX idx_shopping_cart_user_conversation
    ON public.shopping_cart (user_id, conversation_id);

CREATE INDEX idx_shopping_cart_state
    ON public.shopping_cart (state);

-- =========================================================
-- Cart item
-- =========================================================

CREATE TABLE public.cart_item
(
    id             bigserial PRIMARY KEY,
    cart_id        bigint                                    NOT NULL,
    spu_id         bigint                                    NOT NULL,
    external_ref   varchar(64),
    title          varchar(255)                              NOT NULL,
    brand          varchar(64),
    image_url      varchar(512),
    quantity       integer DEFAULT 1                         NOT NULL
        CONSTRAINT cart_item_quantity_chk
            CHECK (quantity > 0),
    unit_price     numeric(10, 2),
    stock_snapshot integer,
    status         varchar(16) DEFAULT 'ACTIVE'              NOT NULL
        CONSTRAINT cart_item_status_chk
            CHECK (status IN ('ACTIVE', 'REMOVED')),
    created_at     timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at     timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT fk_cart_item_cart
        FOREIGN KEY (cart_id)
            REFERENCES public.shopping_cart (id)
            ON DELETE CASCADE
);

CREATE INDEX idx_cart_item_cart_status
    ON public.cart_item (cart_id, status);

CREATE INDEX idx_cart_item_spu
    ON public.cart_item (spu_id);

-- =========================================================
-- Cart transition audit
-- =========================================================

CREATE TABLE public.cart_transition_audit
(
    id               bigserial PRIMARY KEY,
    cart_id          bigint,
    business_cart_id varchar(64),
    from_state       varchar(32),
    to_state         varchar(32)                               NOT NULL,
    event            varchar(32)                               NOT NULL,
    triggered_by     varchar(64),
    success          boolean DEFAULT true                      NOT NULL,
    failure_reason   varchar(64),
    error_message    text,
    metadata         jsonb DEFAULT '{}'::jsonb                 NOT NULL,
    created_at       timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT fk_cart_transition_audit_cart
        FOREIGN KEY (cart_id)
            REFERENCES public.shopping_cart (id)
            ON DELETE SET NULL
);

CREATE INDEX idx_cart_transition_audit_cart_created
    ON public.cart_transition_audit (cart_id, created_at);

CREATE INDEX idx_cart_transition_audit_business_cart_created
    ON public.cart_transition_audit (business_cart_id, created_at);

-- =========================================================
-- Delivery address
-- =========================================================

CREATE TABLE public.delivery_address
(
    id            bigserial PRIMARY KEY,
    user_id       varchar(64)                               NOT NULL,
    receiver_name varchar(128)                              NOT NULL,
    phone         varchar(64)                               NOT NULL,
    province      varchar(128),
    city          varchar(128),
    district      varchar(128),
    detail        varchar(512)                              NOT NULL,
    postal_code   varchar(32),
    is_default    boolean DEFAULT false                     NOT NULL,
    created_at    timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at    timestamp(6) with time zone DEFAULT now() NOT NULL
);

CREATE INDEX idx_delivery_address_user_default
    ON public.delivery_address (user_id, is_default);

-- =========================================================
-- Customer order
-- =========================================================

CREATE TABLE public.customer_order
(
    id                    bigserial PRIMARY KEY,
    order_id              varchar(64)                               NOT NULL UNIQUE,
    cart_id               varchar(64),
    user_id               varchar(64)                               NOT NULL,
    conversation_id       varchar(64)                               NOT NULL,
    status                varchar(32) DEFAULT 'PLACED'              NOT NULL
        CONSTRAINT customer_order_status_chk
            CHECK (status IN ('PLACED', 'CANCELLED')),
    currency              varchar(8) DEFAULT 'CNY'                  NOT NULL,
    subtotal_amount       numeric(12, 2) DEFAULT 0                  NOT NULL,
    item_count            integer DEFAULT 0                         NOT NULL,
    delivery_address_id   bigint,
    delivery_address_json jsonb DEFAULT '{}'::jsonb                 NOT NULL,
    price_change_json     jsonb DEFAULT '[]'::jsonb                 NOT NULL,
    placed_at             timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_at            timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at            timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT fk_customer_order_delivery_address
        FOREIGN KEY (delivery_address_id)
            REFERENCES public.delivery_address (id)
            ON DELETE SET NULL
);

CREATE INDEX idx_customer_order_user_created
    ON public.customer_order (user_id ASC, created_at DESC);

CREATE INDEX idx_customer_order_cart_id
    ON public.customer_order (cart_id);

-- =========================================================
-- Order item
-- =========================================================

CREATE TABLE public.order_item
(
    id           bigserial PRIMARY KEY,
    order_id     bigint                                    NOT NULL,
    spu_id       bigint                                    NOT NULL,
    external_ref varchar(64),
    title        varchar(255)                              NOT NULL,
    brand        varchar(64),
    image_url    varchar(512),
    quantity     integer                                   NOT NULL
        CONSTRAINT order_item_quantity_chk
            CHECK (quantity > 0),
    unit_price   numeric(10, 2),
    line_amount  numeric(12, 2),
    created_at   timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT fk_order_item_order
        FOREIGN KEY (order_id)
            REFERENCES public.customer_order (id)
            ON DELETE CASCADE
);

CREATE INDEX idx_order_item_order_id
    ON public.order_item (order_id);

CREATE INDEX idx_order_item_spu
    ON public.order_item (spu_id);

-- =========================================================
-- Catalog SPU
-- =========================================================

CREATE TABLE public.catalog_spu
(
    id                       bigserial PRIMARY KEY,
    external_ref             varchar(64)                               NOT NULL UNIQUE,
    title                    varchar(255)                              NOT NULL,
    brand                    varchar(64),
    category_path            varchar(255),
    price_min                numeric(10, 2),
    price_max                numeric(10, 2),
    stock                    integer DEFAULT 0                         NOT NULL,
    description_md           text,
    images                   jsonb DEFAULT '[]'::jsonb                 NOT NULL,
    video_url                varchar(512),
    attributes_json          jsonb DEFAULT '{}'::jsonb                 NOT NULL,
    attributes_status        varchar(16) DEFAULT 'PENDING'             NOT NULL
        CONSTRAINT catalog_spu_attr_status_chk
            CHECK (attributes_status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'SKIPPED')),
    attributes_attempt_count integer DEFAULT 0                         NOT NULL,
    attributes_last_error    text,
    attributes_attempted_at  timestamp(6) with time zone,
    status                   varchar(16) DEFAULT 'ACTIVE'              NOT NULL
        CONSTRAINT catalog_spu_status_chk
            CHECK (status IN ('ACTIVE', 'DRAFT', 'REMOVED')),
    version                  bigint DEFAULT 0                          NOT NULL,
    document_id              bigint,
    created_at               timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at               timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT fk_catalog_spu_document
        FOREIGN KEY (document_id)
            REFERENCES public.rag_documents (id)
            ON DELETE SET NULL
);

CREATE INDEX idx_catalog_spu_attr_status
    ON public.catalog_spu (attributes_status);

CREATE INDEX idx_catalog_spu_category
    ON public.catalog_spu (category_path);

CREATE INDEX idx_catalog_spu_brand
    ON public.catalog_spu (brand);

CREATE INDEX idx_catalog_spu_document_id
    ON public.catalog_spu (document_id);

-- Now that catalog_spu exists, add optional catalog foreign keys.
ALTER TABLE public.cart_item
    ADD CONSTRAINT fk_cart_item_spu
        FOREIGN KEY (spu_id)
            REFERENCES public.catalog_spu (id)
            ON DELETE RESTRICT;

ALTER TABLE public.order_item
    ADD CONSTRAINT fk_order_item_spu
        FOREIGN KEY (spu_id)
            REFERENCES public.catalog_spu (id)
            ON DELETE RESTRICT;

-- =========================================================
-- Catalog SKU
-- =========================================================

CREATE TABLE public.catalog_sku
(
    id         bigserial PRIMARY KEY,
    spu_id     bigint                                    NOT NULL,
    sku_code   varchar(64)                               NOT NULL,
    spec_json  jsonb DEFAULT '{}'::jsonb                 NOT NULL,
    price      numeric(10, 2)                            NOT NULL,
    stock      integer DEFAULT 0                         NOT NULL,
    status     varchar(16) DEFAULT 'ACTIVE'              NOT NULL
        CONSTRAINT catalog_sku_status_chk
            CHECK (status IN ('ACTIVE', 'REMOVED')),
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT uq_catalog_sku_code
        UNIQUE (spu_id, sku_code),
    CONSTRAINT fk_catalog_sku_spu
        FOREIGN KEY (spu_id)
            REFERENCES public.catalog_spu (id)
            ON DELETE CASCADE
);

CREATE INDEX idx_catalog_sku_spu_id
    ON public.catalog_sku (spu_id);

-- =========================================================
-- Catalog attribute outbox
-- =========================================================

CREATE TABLE public.catalog_attribute_outbox
(
    id              bigserial PRIMARY KEY,
    spu_id          bigint                                    NOT NULL,
    external_ref    varchar(64)                               NOT NULL,
    payload_json    jsonb DEFAULT '{}'::jsonb                 NOT NULL,
    status          varchar(16) DEFAULT 'PENDING'             NOT NULL
        CONSTRAINT catalog_attr_outbox_status_chk
            CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED')),
    attempt_count   integer DEFAULT 0                         NOT NULL,
    last_error      text,
    next_send_after timestamp(6) with time zone,
    message_id      varchar(128),
    created_at      timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at      timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT fk_catalog_attribute_outbox_spu
        FOREIGN KEY (spu_id)
            REFERENCES public.catalog_spu (id)
            ON DELETE CASCADE
);

CREATE INDEX idx_catalog_attr_outbox_status_next
    ON public.catalog_attribute_outbox (status, next_send_after);

CREATE INDEX idx_catalog_attr_outbox_spu_id
    ON public.catalog_attribute_outbox (spu_id);

-- =========================================================
-- Agent turn
-- =========================================================

CREATE TABLE public.agent_turn
(
    id              bigserial PRIMARY KEY,
    turn_id         varchar(64)                              NOT NULL,
    request_id      varchar(64),
    conversation_id varchar(64)                              NOT NULL,
    user_id         varchar(64)                              NOT NULL,
    status          varchar(32) DEFAULT 'PENDING'            NOT NULL
        CONSTRAINT agent_turn_status_chk
            CHECK (status IN (
                'PENDING',
                'RUNNING',
                'SUCCEEDED',
                'FAILED',
                'CANCELLED',
                'WAITING_CLARIFICATION',
                'WAITING_CONFIRMATION'
            )),
    intent          varchar(64),
    target_workflow varchar(64),
    created_at      timestamp with time zone DEFAULT now()   NOT NULL,
    completed_at    timestamp with time zone
);

CREATE UNIQUE INDEX agent_turn_turn_id_key
    ON public.agent_turn USING btree (turn_id);

CREATE INDEX idx_agent_turn_user_conversation_created
    ON public.agent_turn (user_id, conversation_id, created_at DESC);

CREATE INDEX idx_agent_turn_request_id
    ON public.agent_turn (request_id);

-- ----------------------------------------------------------------------------
-- cart_manage_subgraph: persisted candidate-selection state across turns.
-- Created when a CART_MANAGE / ADD turn produces multiple product candidates;
-- consumed on the next turn when the user replies with a selection (e.g.
-- "选第 1 个"). Scoped strictly by (user_id, conversation_id) so candidates
-- never leak across conversations.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.pending_cart_actions
(
    id              bigserial PRIMARY KEY,
    user_id         varchar(255) NOT NULL,
    conversation_id varchar(255) NOT NULL,
    action          varchar(50)  NOT NULL,
    product_name    varchar(1024),
    quantity        integer,
    candidates      jsonb        NOT NULL DEFAULT '[]'::jsonb,
    status          varchar(50)  NOT NULL,
    created_at      timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expire_at       timestamp    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_pending_cart_user_conv
    ON public.pending_cart_actions (user_id, conversation_id);
CREATE INDEX IF NOT EXISTS idx_pending_cart_status
    ON public.pending_cart_actions (status);
CREATE INDEX IF NOT EXISTS idx_pending_cart_expire
    ON public.pending_cart_actions (expire_at);

-- ----------------------------------------------------------------------------
-- order_manage_workflow: persisted multi-turn pending order state.
-- Checkout and address collection never create an order directly. Only an
-- active WAITING_CONFIRMATION row can be confirmed into an order.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.pending_order_actions
(
    id                 bigserial PRIMARY KEY,
    user_id            varchar(255) NOT NULL,
    conversation_id    varchar(255) NOT NULL,
    cart_snapshot      jsonb        NOT NULL DEFAULT '{}'::jsonb,
    cart_snapshot_hash varchar(128),
    address_snapshot   jsonb        NOT NULL DEFAULT '{}'::jsonb,
    amount_snapshot    numeric(12, 2),
    status             varchar(50)  NOT NULL,
    fail_reason        text,
    order_no           varchar(64),
    created_at         timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expire_at          timestamp    NOT NULL,
    CONSTRAINT pending_order_actions_status_chk
        CHECK (status IN (
            'WAITING_ADDRESS',
            'WAITING_CONFIRMATION',
            'CREATING',
            'ORDER_CREATED',
            'CANCELLED',
            'FAILED',
            'EXPIRED'
        ))
);

CREATE INDEX IF NOT EXISTS idx_pending_order_action_user_conv
    ON public.pending_order_actions (user_id, conversation_id);
CREATE INDEX IF NOT EXISTS idx_pending_order_action_status
    ON public.pending_order_actions (status);
CREATE INDEX IF NOT EXISTS idx_pending_order_action_expire
    ON public.pending_order_actions (expire_at);

CREATE TABLE IF NOT EXISTS public.mock_orders
(
    id              bigserial PRIMARY KEY,
    order_no        varchar(64)    NOT NULL UNIQUE,
    user_id         varchar(255)   NOT NULL,
    conversation_id varchar(255)   NOT NULL,
    items_json      jsonb          NOT NULL DEFAULT '{}'::jsonb,
    address_json    jsonb          NOT NULL DEFAULT '{}'::jsonb,
    total_amount    numeric(12, 2) NOT NULL,
    status          varchar(32)    NOT NULL
        CONSTRAINT mock_orders_status_chk
            CHECK (status IN ('CREATED')),
    created_at      timestamp      NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_mock_orders_user_conv
    ON public.mock_orders (user_id, conversation_id);

COMMIT;
