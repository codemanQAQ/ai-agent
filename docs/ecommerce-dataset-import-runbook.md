# 电商数据集离线入库运行说明

本文说明如何将本地 `ecommerce_agent_dataset` 离线导入到 Catalog 主数据表和 RAG 文档表。

## 1. 入库链路

```text
ecommerce_agent_dataset
    ↓
EcommerceDatasetCatalogImportAdapter
    ↓
CatalogImportRequest
    ↓
CatalogCommandFacade.importBatch
    ↓
CatalogImportService
    ↓
catalog_spu / catalog_sku / rag_documents
```

导入后：

- `catalog_spu` 保存商品 SPU 主数据。
- `catalog_sku` 保存 SKU 规格、价格、库存。
- `rag_documents` 保存由商品知识渲染出的 Markdown，用于后续文本切片和 embedding。
- `catalog_attribute_outbox` 会写入属性抽取任务。
- `DocumentIndexRequestedEvent` 会触发后续索引链路。

## 2. 方式一：Admin API 手动导入

启动服务后调用：

```http
POST /admin/catalog/import/ecommerce-dataset
Content-Type: application/json

{
  "datasetRoot": "C:/development/ecommerce_agent_dataset"
}
```

响应中的 `CatalogImportSummary` 会返回总数、成功数、失败数和失败明细。

## 3. 方式二：应用启动时一次性导入

仅在需要离线 bootstrap 数据时开启。

配置：

```properties
rag.catalog.dataset-import.enabled=true
rag.catalog.dataset-import.root=C:/development/ecommerce_agent_dataset
rag.catalog.dataset-import.fail-on-error=true
```

启动时会执行：

```text
EcommerceDatasetImportRunner
    ↓
EcommerceDatasetImportService#importDataset
```

如果 `fail-on-error=true` 且存在导入失败项，应用启动会失败，避免半导入状态被误认为可用。

## 4. 端到端导入验证

本验证用于确认 `100` 个商品 JSON 能完成：

```text
JSON 数据集
    ↓
CatalogImportRequest
    ↓
catalog_spu / catalog_sku
    ↓
rag_documents
```

### 4.1 导入前统计数据集

PowerShell：

```powershell
$files = Get-ChildItem -LiteralPath 'C:/development/ecommerce_agent_dataset' -Recurse -Filter '*.json'
$skuCount = 0
foreach ($file in $files) {
  $json = Get-Content -Raw -Encoding UTF8 -LiteralPath $file.FullName | ConvertFrom-Json
  $skuCount += @($json.skus).Count
}
"product_count=$($files.Count)"
"sku_count=$skuCount"
```

当前数据集预期：

```text
product_count=100
sku_count=585
```

### 4.2 执行导入

推荐在空库或已清理测试库中执行。

```http
POST /admin/catalog/import/ecommerce-dataset
Content-Type: application/json

{
  "datasetRoot": "C:/development/ecommerce_agent_dataset"
}
```

期望响应：

```json
{
  "data": {
    "total": 100,
    "succeeded": 100,
    "failed": 0
  }
}
```

### 4.3 SQL 校验

PostgreSQL 示例：

```sql
SELECT count(*) AS spu_count
FROM catalog_spu
WHERE external_ref LIKE 'p\_%' ESCAPE '\';
```

期望：

```text
spu_count = 100
```

```sql
SELECT count(*) AS sku_count
FROM catalog_sku s
JOIN catalog_spu p ON p.id = s.spu_id
WHERE p.external_ref LIKE 'p\_%' ESCAPE '\';
```

期望：

```text
sku_count = 585
```

```sql
SELECT count(*) AS rag_document_count
FROM rag_documents
WHERE source_type = 'catalog-spu'
  AND external_ref LIKE 'p\_%' ESCAPE '\';
```

期望：

```text
rag_document_count = 100
```

```sql
SELECT count(*) AS missing_document_id
FROM catalog_spu
WHERE external_ref LIKE 'p\_%' ESCAPE '\'
  AND document_id IS NULL;
```

期望：

```text
missing_document_id = 0
```

### 4.4 抽样校验

```sql
SELECT external_ref, title, brand, category_path, price_min, price_max, stock, document_id
FROM catalog_spu
WHERE external_ref = 'p_digital_001';
```

期望：

- `external_ref = p_digital_001`
- `category_path = 数码电子/智能手机`
- `price_min = 8999`
- `price_max = 12499`
- `stock = 900`
- `document_id IS NOT NULL`

```sql
SELECT sku_code, spec_json, price, stock
FROM catalog_sku
WHERE spu_id = (
  SELECT id FROM catalog_spu WHERE external_ref = 'p_digital_001'
)
ORDER BY sku_code;
```

期望：

- 返回 `9` 条 SKU。
- 每条 SKU 默认库存为 `100`。
- `spec_json` 包含 `存储`、`颜色`、`版本`。

## 5. 重复导入与幂等策略说明

当前 `catalog_spu.external_ref` 是唯一键，同一个 `product_id/externalRef` 重复导入会失败，并记录在 `CatalogImportSummary.failures` 中。

当前策略：

- 单条重复商品会失败。
- `CatalogCommandFacade.importBatch` 具有部分成功语义：某条失败不会影响其它条目继续导入。
- 已有测试覆盖：`CatalogModuleTests#importBatchPartialSuccessReportsFailures`。

推荐运行策略：

- 首次导入建议使用空库或先清理测试数据。
- 生产环境应由离线数据管道保证 `externalRef` 唯一。
- 当前导入工具更适合一次性初始化或手动补数，不适合作为持续同步任务。

后续如需支持重复执行离线任务，可选幂等策略：

- 已存在 `externalRef` 时跳过。
- 或执行 upsert，更新 SPU/SKU/RAG 文档。
- 或导入前清理指定数据集来源。

建议优先实现“已存在则跳过”：

```text
读取 externalRef
    ↓
catalog_spu.findByExternalRef(externalRef)
    ↓
存在：记录 skipped
不存在：执行 importOne
```

该策略适合 demo 数据集和离线 bootstrap，风险最低。

如果需要支持商品内容更新，再实现 upsert：

```text
SPU update
SKU replace or diff update
rag_documents update/reindex
attribute outbox re-enqueue
```

upsert 需要同时处理 SKU 删除、价格变化、文档重建索引和向量清理，复杂度更高。

## 6. 导入后验证

建议检查：

- `catalog_spu` 数量应等于商品 JSON 数量。
- `catalog_sku` 数量应等于全部 JSON 的 SKU 数量之和。
- `rag_documents.source_type = 'catalog-spu'` 数量应等于成功导入 SPU 数量。
- `catalog_spu.document_id` 应已回填。
- 每条 SPU 的 `external_ref` 应等于原始 `product_id`。

## 7. 与 embedding 的关系

本步骤只负责主数据与 RAG 文档入库，不直接生成 embedding。

后续链路：

```text
rag_documents
    ↓
文本切片
    ↓
文本 embedding
    ↓
向量库
```

图片链路另行处理：

```text
image_path
    ↓
读取本地图片
    ↓
CLIP image embedding
    ↓
图片向量库
```
