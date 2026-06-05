# Ecommerce Offline PostgreSQL Resources

该目录保存独立电商商品离线库的运行时 SQL 资产，不依赖 `db/migration/schema.sql`。

## 文件

- `create-database.psql`：连接维护库时创建 `ecommerce_offline` 数据库，使用 `psql` 执行。
- `schema-postgres.sql`：创建 `ecommerce_offline` schema、商品域表、交易域表和多轮待确认动作表。
- `seed-ecommerce-dataset.sql`：写入当前 `ecommerce_agent_dataset` 全量商品数据。
- `ecom-product-chunks.jsonl`：Python 切分生成的父子 chunk JSONL，供离线 embedding 任务或人工校验使用。
- `ecom-product-chunks-summary.json`：chunk 数量统计。
- `ecom-product-embeddings.jsonl`：豆包多模态 embedding 结果，包含 `vector_id`、模型、维度和 fingerprint。
- `update-embedding-status.sql`：将 FAISS/embedding 构建结果回写到 `ecom_product_chunk` 的 SQL。
- `validate.sql`：导入后的数量与样例校验。
- `docker-compose.postgres.yml`：本地 PostgreSQL 实例配置，首次启动会自动执行 schema 和 seed。

## 本地启动数据库

在本目录执行：

```powershell
docker compose -f .\docker-compose.postgres.yml up -d
```

连接串：

```text
postgresql://ecommerce_user:ecommerce_password@localhost:54329/ecommerce_offline
```

校验：

```powershell
psql "postgresql://ecommerce_user:ecommerce_password@localhost:54329/ecommerce_offline" -f .\validate.sql
```

## 手动创建数据库

如果已经有 PostgreSQL 实例：

```powershell
psql "postgresql://USER:PASSWORD@HOST:5432/postgres" -f .\create-database.psql
psql "postgresql://USER:PASSWORD@HOST:5432/ecommerce_offline" -f .\schema-postgres.sql
psql "postgresql://USER:PASSWORD@HOST:5432/ecommerce_offline" -f .\seed-ecommerce-dataset.sql
psql "postgresql://USER:PASSWORD@HOST:5432/ecommerce_offline" -f .\validate.sql
```

## 预期数据量

```text
products = 100
skus = 585
faqs = 439
reviews = 453
images = 100
chunks = 1392
embedding_required_chunks = 1292
text_embedding_chunks = 1192
image_embedding_chunks = 100
```

## 重新生成 Chunk JSONL

从仓库根目录执行：

```powershell
python ai-agent/scripts/ecommerce_offline/chunk_products.py
```

输出：

```text
ai-agent/src/main/resources/db/ecommerce_offline/ecom-product-chunks.jsonl
ai-agent/src/main/resources/db/ecommerce_offline/ecom-product-chunks-summary.json
```

## 生成 Embedding

文本和图片统一使用豆包/火山方舟多模态 Embeddings API，默认接口：

```text
https://ark.cn-beijing.volces.com/api/v3/embeddings/multimodal
```

默认模型：

```text
doubao-embedding-vision-251215
```

先 dry run：

```powershell
python ai-agent/scripts/ecommerce_offline/embed_chunks_doubao.py --dry-run
```

真实调用：

```powershell
$env:DOUBAO_EMBEDDING_API_KEY="你的火山方舟 Embedding API Key"
python ai-agent/scripts/ecommerce_offline/embed_chunks_doubao.py
```

输出：

```text
ai-agent/src/main/resources/db/ecommerce_offline/ecom-product-embeddings.jsonl
```

脚本支持断点续跑：同一个 `chunk_id + embedding_fingerprint + model` 已存在于输出文件时会跳过。文本 chunk 的 fingerprint 是 `content_sha256`，图片 chunk 的 fingerprint 是图片路径。

注意：豆包多模态 embedding 接口一次返回一个多模态输入的向量，所以脚本固定逐 chunk 请求，`--batch-size` 保持为 `1`。

仅处理文本或图片时：

```powershell
python ai-agent/scripts/ecommerce_offline/embed_chunks_doubao.py --modalities text
python ai-agent/scripts/ecommerce_offline/embed_chunks_doubao.py --modalities image
```

## 构建 FAISS 向量库

```powershell
conda run -n rag python ai-agent/scripts/ecommerce_offline/build_faiss_index.py
```

输出：

```text
ai-agent/src/main/resources/db/ecommerce_offline/faiss/ecom-product.index
ai-agent/src/main/resources/db/ecommerce_offline/faiss/ecom-product-id-map.json
ai-agent/src/main/resources/db/ecommerce_offline/faiss/ecom-product-faiss-summary.json
```

当前 FAISS 统计：

```text
vector_count = 1292
dimension = 2048
text = 1192
image = 100
```

## 回写 Embedding 状态

生成回写 SQL：

```powershell
python ai-agent/scripts/ecommerce_offline/sync_embedding_status_sql.py
```

执行回写：

```powershell
psql "postgresql://ecommerce_user:ecommerce_password@localhost:54329/ecommerce_offline" -f .\update-embedding-status.sql
```

回写字段：

- `vector_id`：FAISS/id-map 外部可追踪的向量标识。
- `embedding_status`：`INDEXED`、`SKIPPED` 或 `FAILED`。
- `embedding_model`：本次向量化使用的模型。
- `embedding_dimension`：向量维度，当前为 `2048`。

## FAISS 召回回表

FAISS 搜索返回的是 `faiss_id`。先用 `ecom-product-id-map.json` 映射到 `chunk_id`：

```text
faiss_id -> chunk_id
```

再到 SQL 查询命中的 child chunk：

```sql
SELECT *
FROM ecommerce_offline.ecom_product_chunk
WHERE chunk_id = :chunk_id;
```

然后用 `parent_chunk_id` 查询父 chunk：

```sql
SELECT *
FROM ecommerce_offline.ecom_product_chunk
WHERE chunk_id = :parent_chunk_id;
```

父 chunk 和 child chunk 都携带 `product_id`，再回表拿完整商品结构：

```sql
SELECT * FROM ecommerce_offline.ecom_product WHERE product_id = :product_id;
SELECT * FROM ecommerce_offline.ecom_sku WHERE product_id = :product_id ORDER BY sku_id;
SELECT * FROM ecommerce_offline.ecom_product_faq WHERE product_id = :product_id ORDER BY faq_index;
SELECT * FROM ecommerce_offline.ecom_product_review WHERE product_id = :product_id ORDER BY review_index;
SELECT * FROM ecommerce_offline.ecom_product_image WHERE product_id = :product_id ORDER BY sort_order, image_id;
```

可用脚本验证 child 命中后的 parent/SPU 回查和商品卡片组装：

```powershell
python ai-agent/scripts/ecommerce_offline/hydrate_product_card.py --faiss-ids 1,2 --output ai-agent/src/main/resources/db/ecommerce_offline/faiss/ecom-product-hydration-sample.json
```

也可以在 FAISS 搜索后直接回查并输出商品卡片：

```powershell
conda run -n rag python ai-agent/scripts/ecommerce_offline/search_faiss_index.py --embedding-json-file ai-agent/src/main/resources/db/ecommerce_offline/faiss/ecom-product-query-sample.json --top-k 5 --hydrate --output ai-agent/src/main/resources/db/ecommerce_offline/faiss/ecom-product-search-hydration-sample.json
```

主流程调用入口已放到 `src/main/python/ecommerce_recall/product_vector_recall.py`。该入口可直接 import 函数，也可通过 CLI 调用：

```powershell
conda run -n rag python ai-agent/src/main/python/ecommerce_recall/product_vector_recall.py --embedding-json-file ai-agent/src/main/resources/db/ecommerce_offline/faiss/ecom-product-query-sample.json --top-k 5 --output ai-agent/src/main/resources/db/ecommerce_offline/faiss/ecom-product-main-recall-sample.json
```

脚本输出的商品卡片包含：

- `product`：SPU 主信息、类目、价格区间、库存、图片。
- `skus`：SKU 规格、价格、默认库存。
- `knowledge`：营销描述、FAQ、用户评价、评分统计。
- `evidence`：命中的 child chunk、parent chunk、`vector_id` 和来源字段。
- `lookupTrace`：线上服务可替换成真实 SQL 查询的回表链路。

## 表分组

商品主数据：

- `ecom_product`
- `ecom_sku`
- `ecom_product_knowledge`
- `ecom_product_faq`
- `ecom_product_review`
- `ecom_product_image`

检索与 embedding：

- `ecom_product_chunk`

交易域：

- `ecom_shopping_cart`
- `ecom_cart_item`
- `ecom_cart_transition_audit`
- `ecom_delivery_address`
- `ecom_customer_order`
- `ecom_order_item`

Agent 多轮待确认动作：

- `ecom_pending_cart_action`
- `ecom_pending_order_action`

PostgreSQL 数据库实例本体不适合提交到 `resources`；这里提交的是可重复创建生产库的 SQL 与本地实例配置。实际数据库由 PostgreSQL 根据这些脚本创建。
