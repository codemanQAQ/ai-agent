# Ecommerce Offline Database

这是一套独立的 PostgreSQL 商品离线库资产，不依赖现有 `schema.sql`。

## 文件

- `schema-postgres.sql`：创建 `ecommerce_offline` schema 和商品主数据表。
- `build_seed_sql.py`：从 `ecommerce_agent_dataset` 生成全量 seed SQL。
- `seed-ecommerce-dataset.sql`：当前数据集生成出的全量数据 SQL。
- `validate.sql`：导入后的数量和样例校验 SQL。

## 建库

```powershell
createdb ecommerce_offline
psql "postgresql://USER:PASSWORD@HOST:5432/ecommerce_offline" -f .\schema-postgres.sql
psql "postgresql://USER:PASSWORD@HOST:5432/ecommerce_offline" -f .\seed-ecommerce-dataset.sql
psql "postgresql://USER:PASSWORD@HOST:5432/ecommerce_offline" -f .\validate.sql
```

## 重新生成数据 SQL

```powershell
python .\build_seed_sql.py --output seed-ecommerce-dataset.sql
```

脚本默认从仓库同级目录读取：

```text
C:/development/ecommerce_agent_dataset
```

## 召回后回表

向量召回命中 `ecom_product_chunk.chunk_id` 后：

```sql
SELECT p.*, c.chunk_id, c.chunk_type, c.metadata
FROM ecommerce_offline.ecom_product_chunk c
JOIN ecommerce_offline.ecom_product p ON p.product_id = c.product_id
WHERE c.chunk_id = :chunk_id;
```

再按 `product_id` 获取完整结构：

```sql
SELECT * FROM ecommerce_offline.ecom_product WHERE product_id = :product_id;
SELECT * FROM ecommerce_offline.ecom_sku WHERE product_id = :product_id ORDER BY sku_id;
SELECT * FROM ecommerce_offline.ecom_product_faq WHERE product_id = :product_id ORDER BY faq_index;
SELECT * FROM ecommerce_offline.ecom_product_review WHERE product_id = :product_id ORDER BY review_index;
SELECT * FROM ecommerce_offline.ecom_product_image WHERE product_id = :product_id ORDER BY sort_order, image_id;
```

`ecom_product.raw_json` 保留了原始完整商品 JSON，如果上层需要完全复原原始数据结构，可以直接读取该字段。
