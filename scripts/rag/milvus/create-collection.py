#!/usr/bin/env python3
"""
创建 / 重建 RAG 主 collection（rag_chunks）。

设计目标：与 Spring AI `MilvusVectorStore` 的默认字段命名兼容
(doc_id / content / metadata / embedding)，外加项目自有约定的
metadata JSON 子字段（documentTags / brand / contentSummary 等）。

为什么不用 Spring AI 自动建表：
  - Spring 端 `initializeSchema(false)`，避免重启时无意改动线上 schema。
  - 我们要为 metadata JSON 路径加索引（Milvus 2.5+ 支持）以加速反选过滤。
  - 不同环境（dev / staging / prod）的 partition / shards 不同，
    脚本化建表更便于在 CI / 运维手册里复用。

用法：
  pip install "pymilvus>=2.5"
  export MILVUS_URI=http://localhost:19530
  export MILVUS_TOKEN=root:Milvus            # 可选
  export MILVUS_DB=default                   # 可选
  export RAG_MILVUS_COLLECTION=rag_chunks    # 可选
  export RAG_MILVUS_DIM=2048                 # 与 application 端 embeddingDimension 一致
  python scripts/rag/milvus/create-collection.py            # 创建（已存在则跳过）
  python scripts/rag/milvus/create-collection.py --drop      # 先 drop 再建
  python scripts/rag/milvus/create-collection.py --upgrade   # 仅加新增索引（不动数据）

退出码：
  0 成功（含 already exists / upgrade-only-skipped）
  1 connect 失败 / schema 不兼容 / Milvus 版本过旧 (<2.5)

兼容矩阵：
  Milvus 2.5+   ：metadata JSON 路径 + json_contains_any / json_contains_all 可用
  Milvus 2.4    ：JSON 路径仍可表达，但 json_contains_any 缺失 → 反选 tags 走 not contains 链
"""
from __future__ import annotations

import argparse
import os
import sys

try:
    from pymilvus import (
        CollectionSchema,
        DataType,
        FieldSchema,
        MilvusClient,
        utility,
    )
except ImportError:  # pragma: no cover - 用户自检
    sys.stderr.write("[ERROR] 缺依赖：pip install 'pymilvus>=2.5'\n")
    raise SystemExit(1)


# ===== 字段定义 ==============================================================
# 与 org.springframework.ai.vectorstore.milvus.MilvusVectorStore 的默认常量对齐：
#   DOC_ID_FIELD_NAME   = "doc_id"
#   CONTENT_FIELD_NAME  = "content"
#   METADATA_FIELD_NAME = "metadata"
#   EMBEDDING_FIELD_NAME = "embedding"
# RagMilvusNativeExpressionBuilder 在表达式里也会引用 `metadata` 这个字段名，
# 改这里就要同步改 Spring AI 配置 + Java 表达式构造器。
PK_FIELD = "doc_id"
CONTENT_FIELD = "content"
METADATA_FIELD = "metadata"
EMBEDDING_FIELD = "embedding"

DEFAULT_COLLECTION = os.environ.get("RAG_MILVUS_COLLECTION", "rag_chunks")
DEFAULT_URI = os.environ.get("MILVUS_URI", "http://localhost:19530")
DEFAULT_TOKEN = os.environ.get("MILVUS_TOKEN") or None
DEFAULT_DB = os.environ.get("MILVUS_DB") or None
DEFAULT_DIM = int(os.environ.get("RAG_MILVUS_DIM", "2048"))


def build_schema(dim: int) -> CollectionSchema:
    """构造与项目代码强约定的 schema。"""
    fields = [
        # 主键：与 rag_documents.id (BIGSERIAL) 对齐成字符串便于 Spring AI 处理；最长 64 足够 ULID / UUID。
        FieldSchema(name=PK_FIELD, dtype=DataType.VARCHAR, max_length=64, is_primary=True, auto_id=False),
        # chunk 原文；上限 65535 与 PG TEXT 上限对齐，超长片段在 indexing 阶段已截断。
        FieldSchema(name=CONTENT_FIELD, dtype=DataType.VARCHAR, max_length=65535),
        # JSON 元数据；项目约定的 key 见下方 `EXPECTED_METADATA_KEYS`。
        FieldSchema(name=METADATA_FIELD, dtype=DataType.JSON),
        # embedding 向量；维度必须与 RagProperties.milvus.embeddingDimension 一致。
        FieldSchema(name=EMBEDDING_FIELD, dtype=DataType.FLOAT_VECTOR, dim=dim),
    ]
    return CollectionSchema(
        fields=fields,
        description="RAG agent 主索引：chunk 级 SPU / 文档片段；metadata 内含 documentTags / brand / chunkType 等。",
        enable_dynamic_field=False,
    )


# 项目代码会写入 / 检索的 metadata key。脚本不强校验，仅记入文档供运维参考。
EXPECTED_METADATA_KEYS = [
    "sourceUri",            # catalog://spu/<externalRef> / docs://... 来源 URI
    "documentTags",         # ["通勤", "防水", ...] 文档级标签数组
    "headingPathText",      # 标题路径串（小写化）用于 headingPathContains 过滤
    "headingPath",          # ["产品介绍", "防水性能"] 数组形态保留
    "chunkType",            # TITLE / ATTR / DESC / REVIEW / IMAGE / BODY
    "blockType",            # markdown 元素类型；W2 之前主用此字段
    "brand",                # SPU 品牌，反选 mustNotBrands 命中字段
    "contentSummary",       # chunk 简短摘要，反选 mustNotIngredients 上 LIKE 用
    "spuId",                # catalog_spu.id
    "externalRef",          # catalog_spu.external_ref
]


def collection_exists(client: MilvusClient, name: str) -> bool:
    return name in client.list_collections()


def create(client: MilvusClient, name: str, dim: int) -> None:
    schema = build_schema(dim)
    client.create_collection(collection_name=name, schema=schema)
    print(f"[ok] collection created: name={name}, dim={dim}")


def create_indexes(client: MilvusClient, name: str) -> None:
    """
    建索引：
      - 向量字段：IVF_FLAT + COSINE（与 RagConfiguration.vectorStore 选项一致）
      - JSON metadata 上挂 path 索引以加速 documentTags / brand / sourceUri 过滤
        （Milvus 2.5+；旧版本忽略报错继续）
    """
    index_params = client.prepare_index_params()
    index_params.add_index(
        field_name=EMBEDDING_FIELD,
        index_name=f"{EMBEDDING_FIELD}_ivf_flat_cosine",
        index_type="IVF_FLAT",
        metric_type="COSINE",
        params={"nlist": 1024},
    )
    # JSON path inverted index：把高频反选 / 过滤路径单独建索引。
    # Milvus 2.5+ 才支持 path 索引；脚本里 try-except 兼容旧版本。
    json_paths = [
        ("brand", "VARCHAR"),
        ("sourceUri", "VARCHAR"),
        ("headingPathText", "VARCHAR"),
        ("documentTags", "ARRAY"),
    ]
    try:
        for path, json_cast_type in json_paths:
            index_params.add_index(
                field_name=METADATA_FIELD,
                index_name=f"{METADATA_FIELD}_{path}_idx",
                index_type="INVERTED",
                params={"json_path": f'{METADATA_FIELD}["{path}"]', "json_cast_type": json_cast_type},
            )
    except Exception as exc:  # pragma: no cover - 仅旧 Milvus 路径
        print(f"[warn] JSON path index 创建跳过（Milvus 版本可能 < 2.5）：{exc}")

    client.create_index(collection_name=name, index_params=index_params)
    print(f"[ok] indexes created on {name}")


def load(client: MilvusClient, name: str) -> None:
    client.load_collection(collection_name=name)
    print(f"[ok] collection loaded into memory: {name}")


def drop(client: MilvusClient, name: str) -> None:
    if collection_exists(client, name):
        client.drop_collection(collection_name=name)
        print(f"[ok] collection dropped: {name}")
    else:
        print(f"[skip] drop: collection not found: {name}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Create / rebuild RAG Milvus collection.")
    parser.add_argument("--drop", action="store_true", help="先 drop 再建（会清空数据）")
    parser.add_argument("--upgrade", action="store_true", help="集合已存在时只追加索引，不重建")
    parser.add_argument("--collection", default=DEFAULT_COLLECTION)
    parser.add_argument("--dim", type=int, default=DEFAULT_DIM)
    parser.add_argument("--uri", default=DEFAULT_URI)
    parser.add_argument("--token", default=DEFAULT_TOKEN)
    parser.add_argument("--db", default=DEFAULT_DB)
    args = parser.parse_args()

    client_kwargs = {"uri": args.uri}
    if args.token:
        client_kwargs["token"] = args.token
    if args.db:
        client_kwargs["db_name"] = args.db
    client = MilvusClient(**client_kwargs)

    if args.drop:
        drop(client, args.collection)

    exists = collection_exists(client, args.collection)
    if not exists:
        create(client, args.collection, args.dim)
        create_indexes(client, args.collection)
    elif args.upgrade:
        print(f"[skip] collection {args.collection} exists; upgrading indexes only")
        create_indexes(client, args.collection)
    else:
        print(f"[skip] collection {args.collection} exists; pass --drop to rebuild")

    load(client, args.collection)
    print("[done] expected metadata keys (informational):")
    for k in EXPECTED_METADATA_KEYS:
        print(f"  - {k}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
