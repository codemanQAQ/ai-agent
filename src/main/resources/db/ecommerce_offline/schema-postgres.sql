-- Standalone PostgreSQL schema for the ecommerce offline product database.
-- This schema is intentionally independent from src/main/resources/db/migration/schema.sql.

BEGIN;

-- =========================================================
-- Ecommerce offline schema
-- =========================================================

CREATE SCHEMA IF NOT EXISTS ecommerce_offline;

COMMENT ON SCHEMA ecommerce_offline IS
    '独立电商商品离线库：保存商品主数据、SKU、知识内容、图片和可向量化 chunk。';

-- =========================================================
-- Product SPU master data
-- =========================================================

CREATE TABLE IF NOT EXISTS ecommerce_offline.ecom_product (
    product_id       varchar(64) PRIMARY KEY,
    title            varchar(512) NOT NULL,
    brand            varchar(128),
    category         varchar(128),
    sub_category     varchar(128),
    category_path    varchar(255),
    base_price       numeric(12, 2),
    price_min        numeric(12, 2),
    price_max        numeric(12, 2),
    stock_total      integer DEFAULT 0 NOT NULL,
    image_path       text,
    raw_json         jsonb DEFAULT '{}'::jsonb NOT NULL,
    source_file      text,
    data_version     varchar(64) DEFAULT 'ecommerce_agent_dataset' NOT NULL,
    status           varchar(16) DEFAULT 'ACTIVE' NOT NULL
        CHECK (status IN ('ACTIVE', 'DRAFT', 'REMOVED')),
    created_at       timestamptz DEFAULT now() NOT NULL,
    updated_at       timestamptz DEFAULT now() NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ecom_product_category
    ON ecommerce_offline.ecom_product(category_path);

CREATE INDEX IF NOT EXISTS idx_ecom_product_brand
    ON ecommerce_offline.ecom_product(brand);

CREATE INDEX IF NOT EXISTS idx_ecom_product_price
    ON ecommerce_offline.ecom_product(price_min, price_max);

CREATE INDEX IF NOT EXISTS idx_ecom_product_title_fts
    ON ecommerce_offline.ecom_product
    USING gin(to_tsvector('simple', COALESCE(title, '')));

COMMENT ON TABLE ecommerce_offline.ecom_product IS
    '商品 SPU 主表；作为召回回表的父级实体，并保留原始商品 JSON。';
COMMENT ON COLUMN ecommerce_offline.ecom_product.product_id IS
    '数据集商品 ID，全局唯一；也是向量召回 child chunk 回表的 product_id。';
COMMENT ON COLUMN ecommerce_offline.ecom_product.title IS
    '商品标题，用于展示、关键词搜索和商品卡片。';
COMMENT ON COLUMN ecommerce_offline.ecom_product.brand IS
    '品牌，作为结构化过滤字段，不优先单独 embedding。';
COMMENT ON COLUMN ecommerce_offline.ecom_product.category IS
    '一级类目，已做目录类目和 JSON 类目命名归一。';
COMMENT ON COLUMN ecommerce_offline.ecom_product.sub_category IS
    '二级类目。';
COMMENT ON COLUMN ecommerce_offline.ecom_product.category_path IS
    '类目路径，格式通常为 category/sub_category，用于类目过滤和排序。';
COMMENT ON COLUMN ecommerce_offline.ecom_product.base_price IS
    '数据集原始基础价格。';
COMMENT ON COLUMN ecommerce_offline.ecom_product.price_min IS
    'SKU 价格下界；用于预算过滤。';
COMMENT ON COLUMN ecommerce_offline.ecom_product.price_max IS
    'SKU 价格上界；用于预算过滤。';
COMMENT ON COLUMN ecommerce_offline.ecom_product.stock_total IS
    'SPU 总库存；当前由 SKU 默认库存求和生成。';
COMMENT ON COLUMN ecommerce_offline.ecom_product.image_path IS
    '商品主图路径，指向 ecommerce_agent_dataset 中的本地图片。';
COMMENT ON COLUMN ecommerce_offline.ecom_product.raw_json IS
    '原始完整商品 JSON；需要完全复原数据结构时优先读取该字段。';
COMMENT ON COLUMN ecommerce_offline.ecom_product.source_file IS
    '商品 JSON 相对数据集根目录的来源文件路径。';
COMMENT ON COLUMN ecommerce_offline.ecom_product.data_version IS
    '数据版本或数据集标识。';
COMMENT ON COLUMN ecommerce_offline.ecom_product.status IS
    '商品状态：ACTIVE 可售，DRAFT 草稿，REMOVED 下架。';

-- =========================================================
-- Product SKU
-- =========================================================

CREATE TABLE IF NOT EXISTS ecommerce_offline.ecom_sku (
    sku_id       varchar(96) PRIMARY KEY,
    product_id   varchar(64) NOT NULL
        REFERENCES ecommerce_offline.ecom_product(product_id)
        ON DELETE CASCADE,
    spec_json    jsonb DEFAULT '{}'::jsonb NOT NULL,
    price        numeric(12, 2) NOT NULL,
    stock        integer DEFAULT 100 NOT NULL,
    status       varchar(16) DEFAULT 'ACTIVE' NOT NULL
        CHECK (status IN ('ACTIVE', 'REMOVED')),
    created_at   timestamptz DEFAULT now() NOT NULL,
    updated_at   timestamptz DEFAULT now() NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ecom_sku_product
    ON ecommerce_offline.ecom_sku(product_id);

CREATE INDEX IF NOT EXISTS idx_ecom_sku_spec_gin
    ON ecommerce_offline.ecom_sku USING gin(spec_json);

COMMENT ON TABLE ecommerce_offline.ecom_sku IS
    '商品 SKU 表；保存规格、价格、库存，支撑结构化过滤和购物车 SKU 选择。';
COMMENT ON COLUMN ecommerce_offline.ecom_sku.sku_id IS
    '数据集 SKU ID，全局唯一。';
COMMENT ON COLUMN ecommerce_offline.ecom_sku.product_id IS
    '所属 SPU 商品 ID，关联 ecom_product.product_id。';
COMMENT ON COLUMN ecommerce_offline.ecom_sku.spec_json IS
    'SKU 规格键值，如颜色、容量、尺码；用于 SQL/metadata 过滤，不优先单独 embedding。';
COMMENT ON COLUMN ecommerce_offline.ecom_sku.price IS
    'SKU 售价。';
COMMENT ON COLUMN ecommerce_offline.ecom_sku.stock IS
    'SKU 库存；当前数据集无库存字段，默认写入 100。';
COMMENT ON COLUMN ecommerce_offline.ecom_sku.status IS
    'SKU 状态：ACTIVE 可售，REMOVED 下架。';

-- =========================================================
-- Product knowledge
-- =========================================================

CREATE TABLE IF NOT EXISTS ecommerce_offline.ecom_product_knowledge (
    product_id              varchar(64) PRIMARY KEY
        REFERENCES ecommerce_offline.ecom_product(product_id)
        ON DELETE CASCADE,
    marketing_description   text,
    created_at              timestamptz DEFAULT now() NOT NULL,
    updated_at              timestamptz DEFAULT now() NOT NULL
);

COMMENT ON TABLE ecommerce_offline.ecom_product_knowledge IS
    '商品营销知识表；保存 rag_knowledge.marketing_description。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_knowledge.product_id IS
    '所属 SPU 商品 ID。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_knowledge.marketing_description IS
    '营销描述，自然语言内容，需要生成 marketing_description text embedding。';

-- =========================================================
-- Official FAQ
-- =========================================================

CREATE TABLE IF NOT EXISTS ecommerce_offline.ecom_product_faq (
    faq_id      bigserial PRIMARY KEY,
    product_id  varchar(64) NOT NULL
        REFERENCES ecommerce_offline.ecom_product(product_id)
        ON DELETE CASCADE,
    faq_index   integer NOT NULL,
    question    text NOT NULL,
    answer      text NOT NULL,
    created_at  timestamptz DEFAULT now() NOT NULL,
    updated_at  timestamptz DEFAULT now() NOT NULL,
    UNIQUE (product_id, faq_index)
);

CREATE INDEX IF NOT EXISTS idx_ecom_product_faq_product
    ON ecommerce_offline.ecom_product_faq(product_id);

COMMENT ON TABLE ecommerce_offline.ecom_product_faq IS
    '商品官方 FAQ；一问一答一行，适合精确问答召回。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_faq.faq_id IS
    'FAQ 内部主键。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_faq.product_id IS
    '所属 SPU 商品 ID。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_faq.faq_index IS
    'FAQ 在原始 JSON 数组中的顺序。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_faq.question IS
    '官方问题文本，需要进入 official_faq text embedding。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_faq.answer IS
    '官方回答文本，需要进入 official_faq text embedding。';

-- =========================================================
-- User reviews
-- =========================================================

CREATE TABLE IF NOT EXISTS ecommerce_offline.ecom_product_review (
    review_id     bigserial PRIMARY KEY,
    product_id    varchar(64) NOT NULL
        REFERENCES ecommerce_offline.ecom_product(product_id)
        ON DELETE CASCADE,
    review_index  integer NOT NULL,
    nickname      varchar(128),
    rating        integer CHECK (rating BETWEEN 1 AND 5),
    content       text NOT NULL,
    sentiment     varchar(16) DEFAULT 'unknown' NOT NULL
        CHECK (sentiment IN ('positive', 'mixed', 'negative', 'unknown')),
    created_at    timestamptz DEFAULT now() NOT NULL,
    updated_at    timestamptz DEFAULT now() NOT NULL,
    UNIQUE (product_id, review_index)
);

CREATE INDEX IF NOT EXISTS idx_ecom_product_review_product
    ON ecommerce_offline.ecom_product_review(product_id);

CREATE INDEX IF NOT EXISTS idx_ecom_product_review_rating
    ON ecommerce_offline.ecom_product_review(rating);

COMMENT ON TABLE ecommerce_offline.ecom_product_review IS
    '商品用户评价；一条评价一行，用于口碑、缺点、负向约束和评价摘要。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_review.review_id IS
    '评价内部主键。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_review.product_id IS
    '所属 SPU 商品 ID。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_review.review_index IS
    '评价在原始 JSON 数组中的顺序。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_review.nickname IS
    '评价用户昵称。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_review.rating IS
    '评分，范围 1 到 5。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_review.content IS
    '评价正文，需要进入 user_review text embedding。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_review.sentiment IS
    '规则生成的情绪标签：positive、mixed、negative、unknown。';

-- =========================================================
-- Product images
-- =========================================================

CREATE TABLE IF NOT EXISTS ecommerce_offline.ecom_product_image (
    image_id     bigserial PRIMARY KEY,
    product_id   varchar(64) NOT NULL
        REFERENCES ecommerce_offline.ecom_product(product_id)
        ON DELETE CASCADE,
    image_path   text NOT NULL,
    image_type   varchar(32) DEFAULT 'main' NOT NULL,
    sort_order   integer DEFAULT 0 NOT NULL,
    metadata     jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at   timestamptz DEFAULT now() NOT NULL,
    updated_at   timestamptz DEFAULT now() NOT NULL,
    UNIQUE (product_id, image_path)
);

CREATE INDEX IF NOT EXISTS idx_ecom_product_image_product
    ON ecommerce_offline.ecom_product_image(product_id);

COMMENT ON TABLE ecommerce_offline.ecom_product_image IS
    '商品图片表；保存主图路径和图片元数据，供图片向量化任务读取。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_image.image_id IS
    '图片内部主键。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_image.product_id IS
    '所属 SPU 商品 ID。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_image.image_path IS
    '本地图片路径。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_image.image_type IS
    '图片类型，当前主图为 main。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_image.sort_order IS
    '同一商品多图时的展示排序。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_image.metadata IS
    '图片补充元数据。';

-- =========================================================
-- Product chunks for retrieval and embedding
-- =========================================================

CREATE TABLE IF NOT EXISTS ecommerce_offline.ecom_product_chunk (
    chunk_id             varchar(128) PRIMARY KEY,
    product_id           varchar(64) NOT NULL
        REFERENCES ecommerce_offline.ecom_product(product_id)
        ON DELETE CASCADE,
    parent_chunk_id      varchar(128),
    chunk_level          varchar(16) NOT NULL
        CHECK (chunk_level IN ('parent', 'child')),
    chunk_type           varchar(64) NOT NULL,
    chunk_index          integer NOT NULL,
    text_content         text,
    content_sha256       char(64),
    embedding_required   boolean DEFAULT false NOT NULL,
    embedding_modality   varchar(16) DEFAULT 'none' NOT NULL
        CHECK (embedding_modality IN ('none', 'text', 'image')),
    source_ref           jsonb DEFAULT '{}'::jsonb NOT NULL,
    metadata             jsonb DEFAULT '{}'::jsonb NOT NULL,
    vector_id            varchar(256),
    embedding_status     varchar(16) DEFAULT 'PENDING' NOT NULL
        CHECK (embedding_status IN ('PENDING', 'PROCESSING', 'INDEXED', 'FAILED', 'SKIPPED')),
    embedding_model      varchar(255),
    embedding_dimension  integer,
    embedding_attempt_count integer DEFAULT 0 NOT NULL,
    embedding_last_error text,
    embedding_attempted_at timestamptz,
    embedded_at          timestamptz,
    created_at           timestamptz DEFAULT now() NOT NULL,
    updated_at           timestamptz DEFAULT now() NOT NULL,
    UNIQUE (product_id, chunk_type, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_ecom_product_chunk_product
    ON ecommerce_offline.ecom_product_chunk(product_id);

CREATE INDEX IF NOT EXISTS idx_ecom_product_chunk_parent
    ON ecommerce_offline.ecom_product_chunk(parent_chunk_id);

CREATE INDEX IF NOT EXISTS idx_ecom_product_chunk_embedding
    ON ecommerce_offline.ecom_product_chunk(embedding_required, embedding_modality, chunk_type);

CREATE INDEX IF NOT EXISTS idx_ecom_product_chunk_embedding_status
    ON ecommerce_offline.ecom_product_chunk(embedding_status, embedding_required, embedding_modality);

CREATE INDEX IF NOT EXISTS idx_ecom_product_chunk_metadata_gin
    ON ecommerce_offline.ecom_product_chunk USING gin(metadata);

CREATE INDEX IF NOT EXISTS idx_ecom_product_chunk_metadata_product_id
    ON ecommerce_offline.ecom_product_chunk((metadata ->> 'productId'));

CREATE INDEX IF NOT EXISTS idx_ecom_product_chunk_metadata_external_ref
    ON ecommerce_offline.ecom_product_chunk((metadata ->> 'externalRef'));

CREATE INDEX IF NOT EXISTS idx_ecom_product_chunk_metadata_chunk_type
    ON ecommerce_offline.ecom_product_chunk((metadata ->> 'chunkType'));

CREATE INDEX IF NOT EXISTS idx_ecom_product_chunk_metadata_catalog_spu_id
    ON ecommerce_offline.ecom_product_chunk((metadata ->> 'catalogSpuId'));

CREATE INDEX IF NOT EXISTS idx_ecom_product_chunk_text_fts
    ON ecommerce_offline.ecom_product_chunk
    USING gin(to_tsvector('simple', COALESCE(text_content, '')));

COMMENT ON TABLE ecommerce_offline.ecom_product_chunk IS
    '商品父子 chunk 表；保存 embedding 任务输入和向量召回回表所需元数据。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_chunk.chunk_id IS
    'chunk 全局 ID，格式通常为 product_id:chunk_type:index。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_chunk.product_id IS
    '所属 SPU 商品 ID；召回命中 child chunk 后用它回查完整商品。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_chunk.parent_chunk_id IS
    '父 chunk ID；child chunk 指向 product_parent。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_chunk.chunk_level IS
    'chunk 层级：parent 表示商品父级，child 表示可检索子片段。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_chunk.chunk_type IS
    'chunk 类型，如 product_parent、product_profile、marketing_description、official_faq、user_review、review_summary、image_embedding。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_chunk.chunk_index IS
    '同一商品同一 chunk 类型下的顺序号。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_chunk.text_content IS
    '文本 chunk 内容；图片 embedding chunk 可以为空。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_chunk.content_sha256 IS
    '文本内容 SHA-256，用于幂等 embedding 和变更检测。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_chunk.embedding_required IS
    '是否需要进入 embedding 流水线。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_chunk.embedding_modality IS
    'embedding 模态：none、text、image。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_chunk.source_ref IS
    'chunk 来源定位，如原始字段名、数组下标或图片路径。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_chunk.metadata IS
    '召回和调试元数据；必须携带 productId、parentId、chunkType 等回表字段。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_chunk.vector_id IS
    '写入向量库后的向量 ID。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_chunk.embedding_status IS
    '文本/图片向量化状态：PENDING、PROCESSING、INDEXED、FAILED、SKIPPED。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_chunk.embedding_model IS
    '最近一次成功或尝试使用的 embedding 模型。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_chunk.embedding_dimension IS
    '最近一次成功或尝试使用的向量维度。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_chunk.embedding_attempt_count IS
    'embedding 尝试次数。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_chunk.embedding_last_error IS
    '最近一次 embedding 失败原因。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_chunk.embedding_attempted_at IS
    '最近一次开始 embedding 的时间。';
COMMENT ON COLUMN ecommerce_offline.ecom_product_chunk.embedded_at IS
    '最近一次成功写入向量库的时间。';

-- =========================================================
-- Shopping cart
-- =========================================================

CREATE TABLE IF NOT EXISTS ecommerce_offline.ecom_shopping_cart (
    cart_id               varchar(64) PRIMARY KEY,
    user_id               varchar(64) NOT NULL,
    conversation_id       varchar(64),
    state                 varchar(32) DEFAULT 'IDLE' NOT NULL
        CHECK (state IN ('IDLE', 'ITEM_PROPOSED', 'IN_CART', 'CHECKING_OUT', 'PLACED', 'CANCELLED')),
    currency              varchar(8) DEFAULT 'CNY' NOT NULL,
    subtotal_amount       numeric(12, 2) DEFAULT 0 NOT NULL,
    item_count            integer DEFAULT 0 NOT NULL,
    shipping_address_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    version               bigint DEFAULT 0 NOT NULL,
    created_at            timestamptz DEFAULT now() NOT NULL,
    updated_at            timestamptz DEFAULT now() NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ecom_cart_user_conversation
    ON ecommerce_offline.ecom_shopping_cart(user_id, conversation_id);

CREATE INDEX IF NOT EXISTS idx_ecom_cart_state
    ON ecommerce_offline.ecom_shopping_cart(state);

COMMENT ON TABLE ecommerce_offline.ecom_shopping_cart IS
    '购物车主表；保存用户当前购物车状态、金额快照和收货地址快照。';
COMMENT ON COLUMN ecommerce_offline.ecom_shopping_cart.cart_id IS
    '业务购物车 ID。';
COMMENT ON COLUMN ecommerce_offline.ecom_shopping_cart.user_id IS
    '用户 ID。';
COMMENT ON COLUMN ecommerce_offline.ecom_shopping_cart.conversation_id IS
    '关联会话 ID，可为空，方便脱离 Agent 会话直接使用。';
COMMENT ON COLUMN ecommerce_offline.ecom_shopping_cart.state IS
    '购物车状态机状态。';
COMMENT ON COLUMN ecommerce_offline.ecom_shopping_cart.shipping_address_json IS
    '结算时使用的收货地址快照。';

-- =========================================================
-- Cart item
-- =========================================================

CREATE TABLE IF NOT EXISTS ecommerce_offline.ecom_cart_item (
    id             bigserial PRIMARY KEY,
    cart_id        varchar(64) NOT NULL
        REFERENCES ecommerce_offline.ecom_shopping_cart(cart_id)
        ON DELETE CASCADE,
    product_id     varchar(64) NOT NULL
        REFERENCES ecommerce_offline.ecom_product(product_id)
        ON DELETE RESTRICT,
    sku_id         varchar(96)
        REFERENCES ecommerce_offline.ecom_sku(sku_id)
        ON DELETE RESTRICT,
    title          varchar(512) NOT NULL,
    brand          varchar(128),
    image_path     text,
    quantity       integer DEFAULT 1 NOT NULL
        CHECK (quantity > 0),
    unit_price     numeric(12, 2),
    stock_snapshot integer,
    spec_snapshot  jsonb DEFAULT '{}'::jsonb NOT NULL,
    status         varchar(16) DEFAULT 'ACTIVE' NOT NULL
        CHECK (status IN ('ACTIVE', 'REMOVED')),
    created_at     timestamptz DEFAULT now() NOT NULL,
    updated_at     timestamptz DEFAULT now() NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ecom_cart_item_cart_status
    ON ecommerce_offline.ecom_cart_item(cart_id, status);

CREATE INDEX IF NOT EXISTS idx_ecom_cart_item_product
    ON ecommerce_offline.ecom_cart_item(product_id);

CREATE INDEX IF NOT EXISTS idx_ecom_cart_item_sku
    ON ecommerce_offline.ecom_cart_item(sku_id);

COMMENT ON TABLE ecommerce_offline.ecom_cart_item IS
    '购物车商品项；引用离线商品库 product_id/sku_id，并保存展示与价格快照。';
COMMENT ON COLUMN ecommerce_offline.ecom_cart_item.product_id IS
    '商品 SPU ID，关联 ecom_product.product_id。';
COMMENT ON COLUMN ecommerce_offline.ecom_cart_item.sku_id IS
    'SKU ID，关联 ecom_sku.sku_id，可为空表示仅选中 SPU。';
COMMENT ON COLUMN ecommerce_offline.ecom_cart_item.spec_snapshot IS
    '加入购物车时的 SKU 规格快照。';

-- =========================================================
-- Cart transition audit
-- =========================================================

CREATE TABLE IF NOT EXISTS ecommerce_offline.ecom_cart_transition_audit (
    id               bigserial PRIMARY KEY,
    cart_id          varchar(64),
    from_state       varchar(32),
    to_state         varchar(32) NOT NULL,
    event            varchar(32) NOT NULL,
    triggered_by     varchar(64),
    success          boolean DEFAULT true NOT NULL,
    failure_reason   varchar(64),
    error_message    text,
    metadata         jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at       timestamptz DEFAULT now() NOT NULL,
    CONSTRAINT fk_ecom_cart_audit_cart
        FOREIGN KEY (cart_id)
        REFERENCES ecommerce_offline.ecom_shopping_cart(cart_id)
        ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_ecom_cart_audit_cart_created
    ON ecommerce_offline.ecom_cart_transition_audit(cart_id, created_at);

COMMENT ON TABLE ecommerce_offline.ecom_cart_transition_audit IS
    '购物车状态流转审计表；用于排查加购、结算、取消等状态变化。';

-- =========================================================
-- Delivery address
-- =========================================================

CREATE TABLE IF NOT EXISTS ecommerce_offline.ecom_delivery_address (
    id            bigserial PRIMARY KEY,
    user_id       varchar(64) NOT NULL,
    receiver_name varchar(128) NOT NULL,
    phone         varchar(64) NOT NULL,
    province      varchar(128),
    city          varchar(128),
    district      varchar(128),
    detail        varchar(512) NOT NULL,
    postal_code   varchar(32),
    is_default    boolean DEFAULT false NOT NULL,
    created_at    timestamptz DEFAULT now() NOT NULL,
    updated_at    timestamptz DEFAULT now() NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ecom_delivery_address_user_default
    ON ecommerce_offline.ecom_delivery_address(user_id, is_default);

COMMENT ON TABLE ecommerce_offline.ecom_delivery_address IS
    '用户收货地址表；下单时生成订单地址快照。';

-- =========================================================
-- Customer order
-- =========================================================

CREATE TABLE IF NOT EXISTS ecommerce_offline.ecom_customer_order (
    order_id              varchar(64) PRIMARY KEY,
    cart_id               varchar(64),
    user_id               varchar(64) NOT NULL,
    conversation_id       varchar(64),
    status                varchar(32) DEFAULT 'PLACED' NOT NULL
        CHECK (status IN ('PLACED', 'CANCELLED')),
    currency              varchar(8) DEFAULT 'CNY' NOT NULL,
    subtotal_amount       numeric(12, 2) DEFAULT 0 NOT NULL,
    item_count            integer DEFAULT 0 NOT NULL,
    delivery_address_id   bigint
        REFERENCES ecommerce_offline.ecom_delivery_address(id)
        ON DELETE SET NULL,
    delivery_address_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    price_change_json     jsonb DEFAULT '[]'::jsonb NOT NULL,
    placed_at             timestamptz DEFAULT now() NOT NULL,
    created_at            timestamptz DEFAULT now() NOT NULL,
    updated_at            timestamptz DEFAULT now() NOT NULL,
    CONSTRAINT fk_ecom_order_cart
        FOREIGN KEY (cart_id)
        REFERENCES ecommerce_offline.ecom_shopping_cart(cart_id)
        ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_ecom_order_user_created
    ON ecommerce_offline.ecom_customer_order(user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ecom_order_cart
    ON ecommerce_offline.ecom_customer_order(cart_id);

COMMENT ON TABLE ecommerce_offline.ecom_customer_order IS
    '客户订单主表；保存下单金额、地址快照、价格变化和订单状态。';
COMMENT ON COLUMN ecommerce_offline.ecom_customer_order.price_change_json IS
    '下单时价格变化明细，便于向用户说明购物车价格和最终价格差异。';

-- =========================================================
-- Order item
-- =========================================================

CREATE TABLE IF NOT EXISTS ecommerce_offline.ecom_order_item (
    id            bigserial PRIMARY KEY,
    order_id      varchar(64) NOT NULL
        REFERENCES ecommerce_offline.ecom_customer_order(order_id)
        ON DELETE CASCADE,
    product_id    varchar(64) NOT NULL
        REFERENCES ecommerce_offline.ecom_product(product_id)
        ON DELETE RESTRICT,
    sku_id        varchar(96)
        REFERENCES ecommerce_offline.ecom_sku(sku_id)
        ON DELETE RESTRICT,
    title         varchar(512) NOT NULL,
    brand         varchar(128),
    image_path    text,
    quantity      integer NOT NULL
        CHECK (quantity > 0),
    unit_price    numeric(12, 2),
    line_amount   numeric(12, 2),
    spec_snapshot jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at    timestamptz DEFAULT now() NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ecom_order_item_order
    ON ecommerce_offline.ecom_order_item(order_id);

CREATE INDEX IF NOT EXISTS idx_ecom_order_item_product
    ON ecommerce_offline.ecom_order_item(product_id);

COMMENT ON TABLE ecommerce_offline.ecom_order_item IS
    '订单商品明细；保存下单瞬间的商品、SKU、价格和规格快照。';

-- =========================================================
-- Agent session state
-- =========================================================

CREATE TABLE IF NOT EXISTS ecommerce_offline.ecom_agent_session_state (
    id              bigserial PRIMARY KEY,
    user_id         varchar(64) NOT NULL,
    conversation_id varchar(64) NOT NULL,
    state_json      jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at      timestamptz DEFAULT now() NOT NULL,
    updated_at      timestamptz DEFAULT now() NOT NULL,
    CONSTRAINT uq_ecom_agent_session_state_user_conv
        UNIQUE (user_id, conversation_id)
);

CREATE INDEX IF NOT EXISTS idx_ecom_agent_session_state_user_conv
    ON ecommerce_offline.ecom_agent_session_state(user_id, conversation_id);

COMMENT ON TABLE ecommerce_offline.ecom_agent_session_state IS
    'Agent global session state store; keeps recommendation constraints, candidate snapshots, multimodal history, cart and order state.';

-- =========================================================
-- Pending cart action
-- =========================================================

CREATE TABLE IF NOT EXISTS ecommerce_offline.ecom_pending_cart_action (
    id              bigserial PRIMARY KEY,
    user_id         varchar(64) NOT NULL,
    conversation_id varchar(64) NOT NULL,
    action          varchar(50) NOT NULL,
    product_name    varchar(1024),
    quantity        integer,
    candidates      jsonb DEFAULT '[]'::jsonb NOT NULL,
    status          varchar(50) NOT NULL,
    created_at      timestamptz DEFAULT now() NOT NULL,
    updated_at      timestamptz DEFAULT now() NOT NULL,
    expire_at       timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ecom_pending_cart_user_conv
    ON ecommerce_offline.ecom_pending_cart_action(user_id, conversation_id);

CREATE INDEX IF NOT EXISTS idx_ecom_pending_cart_status
    ON ecommerce_offline.ecom_pending_cart_action(status);

CREATE INDEX IF NOT EXISTS idx_ecom_pending_cart_expire
    ON ecommerce_offline.ecom_pending_cart_action(expire_at);

COMMENT ON TABLE ecommerce_offline.ecom_pending_cart_action IS
    '购物车多轮待确认动作；保存候选商品列表，支持用户下一轮选择“第几个”。';

-- =========================================================
-- Pending order action
-- =========================================================

CREATE TABLE IF NOT EXISTS ecommerce_offline.ecom_pending_order_action (
    id                 bigserial PRIMARY KEY,
    user_id            varchar(64) NOT NULL,
    conversation_id    varchar(64) NOT NULL,
    cart_snapshot      jsonb DEFAULT '{}'::jsonb NOT NULL,
    cart_snapshot_hash varchar(128),
    address_snapshot   jsonb DEFAULT '{}'::jsonb NOT NULL,
    amount_snapshot    numeric(12, 2),
    status             varchar(50) NOT NULL
        CHECK (status IN (
            'WAITING_ADDRESS',
            'WAITING_CONFIRMATION',
            'CREATING',
            'ORDER_CREATED',
            'CANCELLED',
            'FAILED',
            'EXPIRED'
        )),
    fail_reason        text,
    order_id           varchar(64),
    created_at         timestamptz DEFAULT now() NOT NULL,
    updated_at         timestamptz DEFAULT now() NOT NULL,
    expire_at          timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ecom_pending_order_user_conv
    ON ecommerce_offline.ecom_pending_order_action(user_id, conversation_id);

CREATE INDEX IF NOT EXISTS idx_ecom_pending_order_status
    ON ecommerce_offline.ecom_pending_order_action(status);

CREATE INDEX IF NOT EXISTS idx_ecom_pending_order_expire
    ON ecommerce_offline.ecom_pending_order_action(expire_at);

COMMENT ON TABLE ecommerce_offline.ecom_pending_order_action IS
    '订单多轮待确认动作；结算和地址收集完成后，等待用户确认再创建订单。';

COMMIT;
