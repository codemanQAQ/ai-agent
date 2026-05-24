# Milvus Collection Schema（rag_chunks）

> Milvus 没有标准 DDL；这里把项目对集合形状、字段约定、索引、运维命令的所有约定集中固化。
> 改字段 / metadata key 时 **一并** 改：
> 1. `scripts/rag/milvus/create-collection.py`
> 2. 本文档
> 3. `RagMilvusNativeExpressionBuilder` 与 `RagMilvusRetriever` 里对应的 JSON path

## 0. 集合命名

| 环境 | collection | database | 备注 |
|---|---|---|---|
| dev | `rag_chunks` | `default` | docker-compose 默认 |
| staging | `rag_chunks_staging` | `rag` | 与 prod 完全同构便于切换 |
| prod | `rag_chunks` | `rag` | — |

由 `rag.milvus.collection-name` / `rag.milvus.database-name` / 环境变量 `RAG_MILVUS_COLLECTION_NAME` 控制；脚本读 `RAG_MILVUS_COLLECTION` 与 `MILVUS_DB`。

## 1. 字段列表

字段名 **强约定**，与 `org.springframework.ai.vectorstore.milvus.MilvusVectorStore` 的默认常量对齐：

| 字段 | DataType | 约束 | 用途 |
|---|---|---|---|
| `doc_id` | VARCHAR(64) | PRIMARY KEY, autoId=false | 与 `rag_chunks.id` 对齐（项目以字符串形态写入）|
| `content` | VARCHAR(65535) | NOT NULL | chunk 原文；超出长度索引侧已截断 |
| `metadata` | JSON | NOT NULL | 项目自有结构化元数据（见 §2）|
| `embedding` | FLOAT_VECTOR(dim) | NOT NULL | dim = `RagProperties.milvus.embeddingDimension`，**必须** 与 embedding 模型输出一致 |

> 改主键 / 增字段要重建集合（Milvus 不允许 ALTER 加字段）。

## 2. `metadata` JSON 约定

写入侧：`RagMilvusVectorIndexer.toMetadataMap`；读取侧：`RagMilvusRetriever` + `RagMilvusNativeExpressionBuilder`。

| key | 类型 | 来源 | 用途（被哪个表达式 / 检索路径用）|
|---|---|---|---|
| `sourceUri` | string | indexing | `LIKE "catalog://spu/%"` 前缀过滤；agent 主链路 |
| `documentTags` | string[] | indexing | 正向：`json_contains_all(.., [...])`；反向：`not json_contains_any(.., [...])` |
| `headingPathText` | string | indexing | `LIKE "%xxx%"` 标题路径过滤（小写化） |
| `headingPath` | string[] | indexing | 原始数组保留，供 Java 侧 helper 复用 |
| `chunkType` | enum | indexing | TITLE / ATTR / DESC / REVIEW / IMAGE / BODY |
| `blockType` | string | indexing | Markdown 块类型；旧路径兼容字段 |
| `brand` | string | catalog | **反选**：`metadata["brand"] not in [...]` |
| `contentSummary` | string | indexing | **反选**：`not metadata["contentSummary"] LIKE "%X%"` 每个 ingredient 一条 |
| `spuId` | string | catalog | 与 `catalog_spu.id` 对齐 |
| `externalRef` | string | catalog | 与 `catalog_spu.external_ref` 对齐；agent 卡片 refId 索引 key |

**Milvus 表达式与 metadata key 的对应关系**（见 `RagMilvusNativeExpressionBuilder`）：

```text
sourceUriPrefix      → metadata["sourceUri"] LIKE "<prefix>%"
tags                 → json_contains_all(metadata["documentTags"], [...])
headingPathContains  → metadata["headingPathText"] LIKE "%<kw>%"
mustNotTags          → not json_contains_any(metadata["documentTags"], [...])
mustNotBrands        → metadata["brand"] not in [...]
mustNotIngredients   → not metadata["contentSummary"] LIKE "%<ing>%"  （每个 ingredient 一条 AND）
```

## 3. 索引

| 字段 | 索引类型 | 参数 | 备注 |
|---|---|---|---|
| `embedding` | IVF_FLAT | metric=COSINE, nlist=1024 | 与 `RagConfiguration.vectorStore` 选项一致；小规模 demo 用 nlist=1024 即可，prod 视数据量调到 4096+ |
| `metadata["brand"]` | INVERTED (JSON path, VARCHAR) | — | Milvus 2.5+；反选品牌过滤加速 |
| `metadata["sourceUri"]` | INVERTED (JSON path, VARCHAR) | — | 同上；主链路前缀过滤加速 |
| `metadata["headingPathText"]` | INVERTED (JSON path, VARCHAR) | — | 同上 |
| `metadata["documentTags"]` | INVERTED (JSON path, ARRAY) | — | 同上；反选 tags `not json_contains_any` 加速 |

旧版本 Milvus（< 2.5）不支持 JSON path 索引：脚本会 try-except 跳过，表达式仍然能工作但走全表扫描。

## 4. 运维命令

```bash
# 0. 依赖
pip install "pymilvus>=2.5"

# 1. 起服务（dev）
docker compose -f docker/compose.milvus.yml up -d

# 2. 建集合 + 索引 + load 进内存
python scripts/rag/milvus/create-collection.py

# 3. 改维度 / 改字段：先 drop 再建（**会清空数据**）
python scripts/rag/milvus/create-collection.py --drop

# 4. 仅追加索引（如新加了一组 JSON path index，已有数据不动）
python scripts/rag/milvus/create-collection.py --upgrade

# 5. 检查
milvus_cli connect -uri http://localhost:19530
> use default
> show collections
> describe collection -c rag_chunks
> show index -c rag_chunks
```

## 5. 与 Java 侧的兼容点

- `RagProperties.milvus.embeddingDimension` ↔ `create-collection.py --dim`
  默认 2048（豆包 `embedding-vision`）；切到 OpenAI text-embedding-3-small 时改 1536，且 **必须重建集合**。
- `rag.milvus.collection-name` ↔ `--collection`：同名即可热切环境。
- `MilvusVectorStore.METADATA_FIELD_NAME = "metadata"`：脚本里硬编码同名常量，改字段名要同步改 Spring AI 端的 builder 配置。

## 6. 回滚 / 灾难恢复

| 场景 | 命令 |
|---|---|
| 集合损坏 | `--drop` 重建 + 跑 `scripts/rag/import-catalog.sh` 重灌 |
| 索引漂移 | `--upgrade` 重建索引（不清数据）|
| 维度变更 | `--drop` 重建（Milvus 不允许变更已建集合的向量维度）|
| 数据迁移 | 用 `milvus-backup` 工具；不走本脚本 |
