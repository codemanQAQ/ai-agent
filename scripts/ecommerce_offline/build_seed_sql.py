#!/usr/bin/env python3
"""Generate PostgreSQL seed SQL for ecommerce_agent_dataset."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


DEFAULT_STOCK = 100
CATEGORY_ALIASES = {
    "食品生活": "食品饮料",
}


def sql_string(value: Any) -> str:
    if value is None:
        return "NULL"
    text = str(value)
    return "'" + text.replace("'", "''") + "'"


def sql_json(value: Any) -> str:
    return sql_string(json.dumps(value, ensure_ascii=False, separators=(",", ":"))) + "::jsonb"


def sql_number(value: Any) -> str:
    if value is None:
        return "NULL"
    return str(value)


def normalize_category(value: str | None) -> str | None:
    if value is None or not value.strip():
        return None
    category = value.strip()
    return CATEGORY_ALIASES.get(category, category)


def category_path(product: dict[str, Any]) -> str | None:
    category = normalize_category(product.get("category"))
    sub_category = product.get("sub_category")
    sub_category = sub_category.strip() if isinstance(sub_category, str) and sub_category.strip() else None
    if category is None:
        return sub_category
    if sub_category is None or sub_category == category:
        return category
    return f"{category}/{sub_category}"


def product_prices(product: dict[str, Any]) -> tuple[Any, Any]:
    prices = [
        sku.get("price")
        for sku in product.get("skus", [])
        if sku is not None and sku.get("price") is not None
    ]
    if not prices:
        return product.get("base_price"), product.get("base_price")
    return min(prices), max(prices)


def product_stock_total(product: dict[str, Any]) -> int:
    return sum(int(sku.get("stock", DEFAULT_STOCK)) for sku in product.get("skus", []) if isinstance(sku, dict))


def base_metadata(product: dict[str, Any]) -> dict[str, Any]:
    product_id = product["product_id"]
    price_min, price_max = product_prices(product)
    return {
        "productId": product_id,
        "externalRef": product_id,
        "title": product.get("title"),
        "brand": product.get("brand"),
        "category": normalize_category(product.get("category")),
        "subCategory": product.get("sub_category"),
        "categoryPath": category_path(product),
        "priceMin": price_min,
        "priceMax": price_max,
        "stock": product_stock_total(product),
        "imagePath": product.get("image_path"),
    }


def sentiment(rating: Any) -> str:
    if not isinstance(rating, int):
        return "unknown"
    if rating >= 4:
        return "positive"
    if rating <= 2:
        return "negative"
    return "mixed"


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def compact_marketing(text: str | None, limit: int = 220) -> str:
    if text is None:
        return ""
    normalized = " ".join(text.split())
    if len(normalized) <= limit:
        return normalized
    return normalized[:limit].rstrip() + "..."


def chunk_id(product_id: str, chunk_type: str, index: int) -> str:
    return f"{product_id}:{chunk_type}:{index}"


def parent_chunk(product: dict[str, Any], index: int) -> tuple[str, str, str, bool, str, dict[str, Any], dict[str, Any]]:
    product_id = product["product_id"]
    metadata = base_metadata(product) | {
        "chunkLevel": "parent",
        "chunkType": "product_parent",
    }
    return (
        chunk_id(product_id, "product_parent", index),
        "parent",
        "product_parent",
        False,
        "none",
        {"field": "product"},
        metadata,
    )


def child_chunks(product: dict[str, Any]) -> list[dict[str, Any]]:
    product_id = product["product_id"]
    knowledge = product.get("rag_knowledge") or {}
    chunks: list[dict[str, Any]] = []
    filterable_metadata = base_metadata(product)

    price_min, price_max = product_prices(product)
    profile = "\n".join(
        part
        for part in [
            f"# {product.get('title')}",
            f"品牌：{product.get('brand')}" if product.get("brand") else None,
            f"类目：{category_path(product)}" if category_path(product) else None,
            f"价格区间：{price_min} ~ {price_max}" if price_min != price_max else f"价格：{price_min}",
            f"图片：{product.get('image_path')}" if product.get("image_path") else None,
            "",
            f"商品摘要：{compact_marketing(knowledge.get('marketing_description'))}",
        ]
        if part is not None
    ).strip()
    chunks.append(
        {
            "type": "product_profile",
            "index": 0,
            "text": profile,
            "embedding_required": True,
            "modality": "text",
            "source_ref": {"field": "product_profile"},
            "metadata": filterable_metadata | {"chunkLevel": "child", "chunkType": "product_profile", "parentId": product_id},
        }
    )

    marketing = knowledge.get("marketing_description")
    if marketing:
        text = "## 营销描述\n\n" + marketing.strip()
        chunks.append(
            {
                "type": "marketing_description",
                "index": 0,
                "text": text,
                "embedding_required": True,
                "modality": "text",
                "source_ref": {"field": "rag_knowledge.marketing_description"},
                "metadata": filterable_metadata | {"chunkLevel": "child", "chunkType": "marketing_description", "parentId": product_id},
            }
        )

    for index, faq in enumerate(knowledge.get("official_faq") or []):
        question = (faq.get("question") or "").strip()
        answer = (faq.get("answer") or "").strip()
        if not question and not answer:
            continue
        text = f"## 官方 FAQ\n\n问题：{question}\n回答：{answer}".strip()
        chunks.append(
            {
                "type": "official_faq",
                "index": index,
                "text": text,
                "embedding_required": True,
                "modality": "text",
                "source_ref": {"field": "rag_knowledge.official_faq", "index": index},
                "metadata": filterable_metadata | {
                    "chunkLevel": "child",
                    "chunkType": "official_faq",
                    "parentId": product_id,
                    "question": question,
                },
            }
        )

    ratings = []
    for index, review in enumerate(knowledge.get("user_reviews") or []):
        rating = review.get("rating")
        if isinstance(rating, int):
            ratings.append(rating)
        content = (review.get("content") or "").strip()
        if not content:
            continue
        text = "\n".join(
            [
                "## 用户评价",
                "",
                f"用户：{review.get('nickname') or ''}",
                f"评分：{rating if rating is not None else ''}",
                f"内容：{content}",
            ]
        ).strip()
        chunks.append(
            {
                "type": "user_review",
                "index": index,
                "text": text,
                "embedding_required": True,
                "modality": "text",
                "source_ref": {"field": "rag_knowledge.user_reviews", "index": index},
                "metadata": filterable_metadata | {
                    "chunkLevel": "child",
                    "chunkType": "user_review",
                    "parentId": product_id,
                    "rating": rating,
                    "sentiment": sentiment(rating),
                },
            }
        )

    if ratings:
        average = round(sum(ratings) / len(ratings), 2)
        distribution = {str(score): ratings.count(score) for score in range(1, 6)}
        text = "\n".join(
            [
                "## 用户评价摘要",
                "",
                f"评价数量：{len(ratings)}",
                f"平均评分：{average}",
                "评分分布：" + json.dumps(distribution, ensure_ascii=False, separators=(",", ":")),
            ]
        )
        chunks.append(
            {
                "type": "review_summary",
                "index": 0,
                "text": text,
                "embedding_required": True,
                "modality": "text",
                "source_ref": {"field": "rag_knowledge.user_reviews"},
                "metadata": filterable_metadata | {
                    "chunkLevel": "child",
                    "chunkType": "review_summary",
                    "parentId": product_id,
                    "reviewCount": len(ratings),
                    "averageRating": average,
                    "ratingDistribution": distribution,
                },
            }
        )

    image_path = product.get("image_path")
    if image_path:
        chunks.append(
            {
                "type": "image_embedding",
                "index": 0,
                "text": None,
                "embedding_required": True,
                "modality": "image",
                "source_ref": {"field": "image_path", "imagePath": image_path},
                "metadata": filterable_metadata | {
                    "chunkLevel": "child",
                    "chunkType": "image_embedding",
                    "parentId": product_id,
                    "imagePath": image_path,
                },
            }
        )

    return chunks


def load_products(dataset_root: Path) -> list[tuple[Path, dict[str, Any]]]:
    if not dataset_root.is_dir():
        raise FileNotFoundError(f"dataset root does not exist: {dataset_root}")
    products = []
    for path in sorted(dataset_root.rglob("*.json"), key=lambda item: str(item.relative_to(dataset_root))):
        with path.open("r", encoding="utf-8") as file:
            products.append((path, json.load(file)))
    if not products:
        raise ValueError(f"dataset root has no product json files: {dataset_root}")
    return products


def write_values_insert(file, table: str, columns: list[str], rows: list[list[str]], conflict: str | None = None) -> None:
    if not rows:
        return
    file.write(f"INSERT INTO {table} ({', '.join(columns)})\nVALUES\n")
    for index, row in enumerate(rows):
        suffix = "," if index < len(rows) - 1 else ""
        file.write("    (" + ", ".join(row) + ")" + suffix + "\n")
    if conflict:
        file.write(conflict + "\n")
    file.write(";\n\n")


def generate(dataset_root: Path, output: Path) -> None:
    rows = load_products(dataset_root)
    product_ids = [product["product_id"] for _, product in rows]
    output.parent.mkdir(parents=True, exist_ok=True)

    with output.open("w", encoding="utf-8", newline="\n") as file:
        file.write("-- Generated by scripts/ecommerce_offline/build_seed_sql.py\n")
        file.write("-- Target schema: ecommerce_offline\n\n")
        file.write("BEGIN;\n\n")

        seed_rows = [[sql_string(product_id)] for product_id in product_ids]
        write_values_insert(file, "pg_temp.seed_ecom_product_ids", ["product_id"], [])
        file.write("CREATE TEMP TABLE seed_ecom_product_ids(product_id varchar(64) PRIMARY KEY) ON COMMIT DROP;\n")
        write_values_insert(file, "seed_ecom_product_ids", ["product_id"], seed_rows)

        for table in [
            "ecommerce_offline.ecom_product_chunk",
            "ecommerce_offline.ecom_product_review",
            "ecommerce_offline.ecom_product_faq",
            "ecommerce_offline.ecom_product_image",
            "ecommerce_offline.ecom_sku",
            "ecommerce_offline.ecom_product_knowledge",
        ]:
            file.write(
                f"DELETE FROM {table} t USING seed_ecom_product_ids s WHERE t.product_id = s.product_id;\n"
            )
        file.write("\n")

        product_rows = []
        knowledge_rows = []
        image_rows = []
        sku_rows = []
        faq_rows = []
        review_rows = []
        chunk_rows = []

        for path, product in rows:
            product_id = product["product_id"]
            price_min, price_max = product_prices(product)
            skus = product.get("skus") or []
            stock_total = len(skus) * DEFAULT_STOCK
            product_rows.append(
                [
                    sql_string(product_id),
                    sql_string(product.get("title")),
                    sql_string(product.get("brand")),
                    sql_string(normalize_category(product.get("category"))),
                    sql_string(product.get("sub_category")),
                    sql_string(category_path(product)),
                    sql_number(product.get("base_price")),
                    sql_number(price_min),
                    sql_number(price_max),
                    str(stock_total),
                    sql_string(product.get("image_path")),
                    sql_json(product),
                    sql_string(str(path.relative_to(dataset_root)).replace("\\", "/")),
                ]
            )

            knowledge = product.get("rag_knowledge") or {}
            knowledge_rows.append(
                [sql_string(product_id), sql_string(knowledge.get("marketing_description"))]
            )

            if product.get("image_path"):
                image_rows.append(
                    [
                        sql_string(product_id),
                        sql_string(product.get("image_path")),
                        sql_string("main"),
                        "0",
                        sql_json({"source": "dataset.image_path"}),
                    ]
                )

            for sku in skus:
                sku_rows.append(
                    [
                        sql_string(sku.get("sku_id")),
                        sql_string(product_id),
                        sql_json(sku.get("properties") or {}),
                        sql_number(sku.get("price")),
                        str(DEFAULT_STOCK),
                    ]
                )

            for index, faq in enumerate(knowledge.get("official_faq") or []):
                faq_rows.append(
                    [
                        sql_string(product_id),
                        str(index),
                        sql_string(faq.get("question")),
                        sql_string(faq.get("answer")),
                    ]
                )

            for index, review in enumerate(knowledge.get("user_reviews") or []):
                rating = review.get("rating")
                review_rows.append(
                    [
                        sql_string(product_id),
                        str(index),
                        sql_string(review.get("nickname")),
                        "NULL" if rating is None else str(rating),
                        sql_string(review.get("content")),
                        sql_string(sentiment(rating)),
                    ]
                )

            parent = parent_chunk(product, 0)
            parent_id = parent[0]
            chunk_rows.append(
                [
                    sql_string(parent_id),
                    sql_string(product_id),
                    "NULL",
                    sql_string(parent[1]),
                    sql_string(parent[2]),
                    "0",
                    "NULL",
                    "NULL",
                    "false",
                    sql_string(parent[4]),
                    sql_json(parent[5]),
                    sql_json(parent[6]),
                ]
            )

            for child in child_chunks(product):
                text = child["text"]
                chunk_rows.append(
                    [
                        sql_string(chunk_id(product_id, child["type"], child["index"])),
                        sql_string(product_id),
                        sql_string(parent_id),
                        sql_string("child"),
                        sql_string(child["type"]),
                        str(child["index"]),
                        sql_string(text),
                        "NULL" if text is None else sql_string(sha256(text)),
                        "true" if child["embedding_required"] else "false",
                        sql_string(child["modality"]),
                        sql_json(child["source_ref"]),
                        sql_json(child["metadata"]),
                    ]
                )

        write_values_insert(
            file,
            "ecommerce_offline.ecom_product",
            [
                "product_id",
                "title",
                "brand",
                "category",
                "sub_category",
                "category_path",
                "base_price",
                "price_min",
                "price_max",
                "stock_total",
                "image_path",
                "raw_json",
                "source_file",
            ],
            product_rows,
            """ON CONFLICT (product_id) DO UPDATE
   SET title = EXCLUDED.title,
       brand = EXCLUDED.brand,
       category = EXCLUDED.category,
       sub_category = EXCLUDED.sub_category,
       category_path = EXCLUDED.category_path,
       base_price = EXCLUDED.base_price,
       price_min = EXCLUDED.price_min,
       price_max = EXCLUDED.price_max,
       stock_total = EXCLUDED.stock_total,
       image_path = EXCLUDED.image_path,
       raw_json = EXCLUDED.raw_json,
       source_file = EXCLUDED.source_file,
       status = 'ACTIVE',
       updated_at = now()""",
        )
        write_values_insert(
            file,
            "ecommerce_offline.ecom_product_knowledge",
            ["product_id", "marketing_description"],
            knowledge_rows,
            """ON CONFLICT (product_id) DO UPDATE
   SET marketing_description = EXCLUDED.marketing_description,
       updated_at = now()""",
        )
        write_values_insert(
            file,
            "ecommerce_offline.ecom_product_image",
            ["product_id", "image_path", "image_type", "sort_order", "metadata"],
            image_rows,
            None,
        )
        write_values_insert(
            file,
            "ecommerce_offline.ecom_sku",
            ["sku_id", "product_id", "spec_json", "price", "stock"],
            sku_rows,
            None,
        )
        write_values_insert(
            file,
            "ecommerce_offline.ecom_product_faq",
            ["product_id", "faq_index", "question", "answer"],
            faq_rows,
            None,
        )
        write_values_insert(
            file,
            "ecommerce_offline.ecom_product_review",
            ["product_id", "review_index", "nickname", "rating", "content", "sentiment"],
            review_rows,
            None,
        )
        write_values_insert(
            file,
            "ecommerce_offline.ecom_product_chunk",
            [
                "chunk_id",
                "product_id",
                "parent_chunk_id",
                "chunk_level",
                "chunk_type",
                "chunk_index",
                "text_content",
                "content_sha256",
                "embedding_required",
                "embedding_modality",
                "source_ref",
                "metadata",
            ],
            chunk_rows,
            None,
        )

        file.write("COMMIT;\n\n")
        file.write("SELECT 'products' AS label, count(*) AS count FROM ecommerce_offline.ecom_product\n")
        file.write("UNION ALL SELECT 'skus', count(*) FROM ecommerce_offline.ecom_sku\n")
        file.write("UNION ALL SELECT 'faqs', count(*) FROM ecommerce_offline.ecom_product_faq\n")
        file.write("UNION ALL SELECT 'reviews', count(*) FROM ecommerce_offline.ecom_product_review\n")
        file.write("UNION ALL SELECT 'chunks', count(*) FROM ecommerce_offline.ecom_product_chunk\n")
        file.write("UNION ALL SELECT 'embedding_required_chunks', count(*) FROM ecommerce_offline.ecom_product_chunk WHERE embedding_required;\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset-root", default="../../../ecommerce_agent_dataset")
    parser.add_argument("--output", default="seed-ecommerce-dataset.sql")
    args = parser.parse_args()

    script_dir = Path(__file__).resolve().parent
    dataset_root = (script_dir / args.dataset_root).resolve()
    output = (script_dir / args.output).resolve()
    generate(dataset_root, output)
    print(f"generated {output}")


if __name__ == "__main__":
    main()
