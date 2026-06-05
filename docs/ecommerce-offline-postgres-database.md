# 电商商品离线 PostgreSQL 数据库

本文说明新的商品离线库设计。该库不复用现有 `schema.sql`，而是按 `ecommerce_agent_dataset` 和商品召回回表流程单独建模，可用于本地校验，也可作为生产商品主数据初始库。

## 1. 设计目标

- 保存完整商品主数据：SPU、SKU、图片、营销描述、FAQ、用户评价。
- 保留原始 JSON：通过 `ecom_product.raw_json` 支持完整结构回放。
- 支持结构化过滤：品牌、类目、价格、SKU 属性、库存都在 SQL 表内。
- 支持向量化任务：通过 `ecom_product_chunk` 标记哪些 child chunk 需要 embedding。
- 支持召回回表：向量召回命中 child chunk 后，用 `product_id` 回到完整商品结构。

## 2. 文件位置

```text
ai-agent/src/main/resources/db/ecommerce_offline/create-database.psql
ai-agent/src/main/resources/db/ecommerce_offline/schema-postgres.sql
ai-agent/src/main/resources/db/ecommerce_offline/seed-ecommerce-dataset.sql
ai-agent/src/main/resources/db/ecommerce_offline/validate.sql
ai-agent/src/main/resources/db/ecommerce_offline/docker-compose.postgres.yml
ai-agent/src/main/resources/db/ecommerce_offline/README.md
```

`ai-agent/scripts/ecommerce_offline/build_seed_sql.py` 只作为生成器保留，用于数据集变化后重新生成 seed SQL。

## 3. 核心表

| 表 | 作用 |
| --- | --- |
| `ecommerce_offline.ecom_product` | 商品 SPU 主表，含标题、品牌、类目、价格区间、总库存、图片路径、原始 JSON |
| `ecommerce_offline.ecom_sku` | SKU 表，含规格 JSON、价格、库存 |
| `ecommerce_offline.ecom_product_knowledge` | 商品营销描述 |
| `ecommerce_offline.ecom_product_faq` | 官方 FAQ，一问一答 |
| `ecommerce_offline.ecom_product_review` | 用户评价 |
| `ecommerce_offline.ecom_product_image` | 商品图片 |
| `ecommerce_offline.ecom_product_chunk` | 父子 chunk 元数据，供 embedding 任务和召回回表使用 |

## 4. 建库与导入

```powershell
createdb ecommerce_offline
psql "postgresql://USER:PASSWORD@HOST:5432/ecommerce_offline" -f ai-agent/src/main/resources/db/ecommerce_offline/schema-postgres.sql
psql "postgresql://USER:PASSWORD@HOST:5432/ecommerce_offline" -f ai-agent/src/main/resources/db/ecommerce_offline/seed-ecommerce-dataset.sql
psql "postgresql://USER:PASSWORD@HOST:5432/ecommerce_offline" -f ai-agent/src/main/resources/db/ecommerce_offline/validate.sql
```

如本地没有 PostgreSQL，可在 `ai-agent/src/main/resources/db/ecommerce_offline` 目录执行 Docker Compose：

```powershell
docker compose -f .\docker-compose.postgres.yml up -d
```

## 5. 重新生成 Seed SQL

```powershell
python ai-agent/scripts/ecommerce_offline/build_seed_sql.py --output seed-ecommerce-dataset.sql
```

脚本默认读取：

```text
C:/development/ecommerce_agent_dataset
```

## 6. Embedding 范围

`ecom_product_chunk.embedding_required = true` 的记录才进入 embedding 流水线。

文本 embedding：

- `product_profile`
- `marketing_description`
- `official_faq`
- `user_review`
- `review_summary`

图片 embedding：

- `image_embedding`

不直接 embedding，而走 SQL/metadata 的字段：

- `brand`
- `category`
- `sub_category`
- `category_path`
- `price_min`
- `price_max`
- `stock_total`
- `sku_id`
- `spec_json`

## 7. 召回后回表

向量库命中 child chunk 后，拿到 `chunk_id`：

```sql
SELECT c.chunk_id, c.chunk_type, c.product_id, p.raw_json
FROM ecommerce_offline.ecom_product_chunk c
JOIN ecommerce_offline.ecom_product p ON p.product_id = c.product_id
WHERE c.chunk_id = :chunk_id;
```

如果业务需要结构化完整商品卡片：

```sql
SELECT * FROM ecommerce_offline.ecom_product WHERE product_id = :product_id;
SELECT * FROM ecommerce_offline.ecom_sku WHERE product_id = :product_id ORDER BY sku_id;
SELECT * FROM ecommerce_offline.ecom_product_faq WHERE product_id = :product_id ORDER BY faq_index;
SELECT * FROM ecommerce_offline.ecom_product_review WHERE product_id = :product_id ORDER BY review_index;
SELECT * FROM ecommerce_offline.ecom_product_image WHERE product_id = :product_id ORDER BY sort_order, image_id;
```

这样可以做到：召回命中的是小 chunk，最终返回的是父级完整商品结构。
