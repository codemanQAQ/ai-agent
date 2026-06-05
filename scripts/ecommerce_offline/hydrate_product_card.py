#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


DEFAULT_STOCK = 100
CATEGORY_ALIASES = {
    "食品生活": "食品饮料",
}


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as file:
        for line_no, line in enumerate(file, start=1):
            if not line.strip():
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as exc:
                raise ValueError(f"invalid jsonl at {path}:{line_no}") from exc
    return rows


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as file:
        return json.load(file)


def load_products(dataset_root: Path) -> dict[str, dict[str, Any]]:
    if not dataset_root.is_dir():
        raise FileNotFoundError(f"dataset root does not exist: {dataset_root}")
    products: dict[str, dict[str, Any]] = {}
    for path in sorted(dataset_root.rglob("*.json"), key=lambda item: str(item.relative_to(dataset_root))):
        product = load_json(path)
        product_id = product.get("product_id")
        if not product_id:
            raise ValueError(f"product_id is missing: {path}")
        if product_id in products:
            raise ValueError(f"duplicate product_id {product_id}: {path}")
        product["_dataset_file"] = str(path.relative_to(dataset_root)).replace("\\", "/")
        products[product_id] = product
    if not products:
        raise ValueError(f"dataset root has no product json files: {dataset_root}")
    return products


def normalize_category(value: Any) -> str | None:
    if not isinstance(value, str) or not value.strip():
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
        if isinstance(sku, dict) and sku.get("price") is not None
    ]
    if not prices:
        return product.get("base_price"), product.get("base_price")
    return min(prices), max(prices)


def product_stock_total(product: dict[str, Any]) -> int:
    skus = product.get("skus") or []
    return sum(int(sku.get("stock", DEFAULT_STOCK)) for sku in skus if isinstance(sku, dict))


def product_rating_summary(product: dict[str, Any]) -> dict[str, Any]:
    reviews = (product.get("rag_knowledge") or {}).get("user_reviews") or []
    ratings = [
        review.get("rating")
        for review in reviews
        if isinstance(review, dict) and isinstance(review.get("rating"), int)
    ]
    if not ratings:
        return {
            "reviewCount": 0,
            "averageRating": None,
            "ratingDistribution": {str(score): 0 for score in range(1, 6)},
        }
    return {
        "reviewCount": len(ratings),
        "averageRating": round(sum(ratings) / len(ratings), 2),
        "ratingDistribution": {str(score): ratings.count(score) for score in range(1, 6)},
    }


def text_preview(text: str | None, limit: int) -> str | None:
    if text is None:
        return None
    normalized = " ".join(text.split())
    if len(normalized) <= limit:
        return normalized
    return normalized[:limit].rstrip() + "..."


def parse_csv_values(value: str | None, value_name: str) -> list[str]:
    if value is None or not value.strip():
        return []
    values = [item.strip() for item in value.split(",") if item.strip()]
    if not values:
        raise ValueError(f"{value_name} has no usable values")
    return values


def faiss_id_index(id_map_rows: list[dict[str, Any]]) -> dict[int, dict[str, Any]]:
    index: dict[int, dict[str, Any]] = {}
    for row in id_map_rows:
        faiss_id = int(row["faiss_id"])
        if faiss_id in index:
            raise ValueError(f"duplicate faiss_id: {faiss_id}")
        index[faiss_id] = row
    return index


def chunk_index(chunks: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    index: dict[str, dict[str, Any]] = {}
    for chunk in chunks:
        chunk_id = chunk["chunk_id"]
        if chunk_id in index:
            raise ValueError(f"duplicate chunk_id: {chunk_id}")
        index[chunk_id] = chunk
    return index


def resolve_hit(
    hit_ref: dict[str, Any],
    chunks_by_id: dict[str, dict[str, Any]],
    products_by_id: dict[str, dict[str, Any]],
    preview_chars: int,
) -> dict[str, Any]:
    child_chunk_id = hit_ref["chunk_id"]
    child_chunk = chunks_by_id.get(child_chunk_id)
    if child_chunk is None:
        raise KeyError(f"chunk not found for hit: {child_chunk_id}")

    parent_chunk_id = child_chunk.get("parent_chunk_id") or child_chunk["chunk_id"]
    parent_chunk = chunks_by_id.get(parent_chunk_id)
    if parent_chunk is None:
        raise KeyError(f"parent chunk not found: {parent_chunk_id}")

    product_id = parent_chunk.get("product_id") or child_chunk.get("product_id")
    product = products_by_id.get(product_id)
    if product is None:
        raise KeyError(f"product not found: {product_id}")

    return assemble_product_card(hit_ref, child_chunk, parent_chunk, product, preview_chars)


def assemble_product_card(
    hit_ref: dict[str, Any],
    child_chunk: dict[str, Any],
    parent_chunk: dict[str, Any],
    product: dict[str, Any],
    preview_chars: int,
) -> dict[str, Any]:
    knowledge = product.get("rag_knowledge") or {}
    faq = knowledge.get("official_faq") or []
    reviews = knowledge.get("user_reviews") or []
    price_min, price_max = product_prices(product)
    product_id = product["product_id"]

    return {
        "product": {
            "productId": product_id,
            "externalRef": product_id,
            "title": product.get("title"),
            "brand": product.get("brand"),
            "category": normalize_category(product.get("category")),
            "subCategory": product.get("sub_category"),
            "categoryPath": category_path(product),
            "basePrice": product.get("base_price"),
            "priceMin": price_min,
            "priceMax": price_max,
            "stockTotal": product_stock_total(product),
            "imagePath": product.get("image_path"),
            "datasetFile": product.get("_dataset_file"),
        },
        "skus": [
            {
                "skuId": sku.get("sku_id"),
                "properties": sku.get("properties") or {},
                "price": sku.get("price"),
                "stock": sku.get("stock", DEFAULT_STOCK),
            }
            for sku in product.get("skus", [])
            if isinstance(sku, dict)
        ],
        "knowledge": {
            "marketingDescription": knowledge.get("marketing_description"),
            "officialFaq": faq,
            "userReviews": reviews,
            "faqCount": len(faq),
            "reviewSummary": product_rating_summary(product),
        },
        "evidence": {
            "faissId": hit_ref.get("faiss_id"),
            "vectorId": hit_ref.get("vector_id"),
            "score": hit_ref.get("score"),
            "matchedChunk": {
                "chunkId": child_chunk["chunk_id"],
                "chunkType": child_chunk.get("chunk_type"),
                "chunkIndex": child_chunk.get("chunk_index"),
                "embeddingModality": child_chunk.get("embedding_modality"),
                "sourceRef": child_chunk.get("source_ref"),
                "metadata": child_chunk.get("metadata") or {},
                "textPreview": text_preview(child_chunk.get("text_content"), preview_chars),
            },
            "parentChunk": {
                "chunkId": parent_chunk["chunk_id"],
                "chunkType": parent_chunk.get("chunk_type"),
                "metadata": parent_chunk.get("metadata") or {},
            },
        },
        "lookupTrace": {
            "faissIdMap": "faiss_id -> chunk_id",
            "chunkLookup": "SELECT * FROM ecommerce_offline.ecom_product_chunk WHERE chunk_id = :chunkId",
            "parentLookup": "SELECT * FROM ecommerce_offline.ecom_product_chunk WHERE chunk_id = :parentChunkId",
            "productLookup": "SELECT * FROM ecommerce_offline.ecom_product WHERE product_id = :productId",
            "skuLookup": "SELECT * FROM ecommerce_offline.ecom_sku WHERE product_id = :productId",
            "knowledgeLookup": [
                "SELECT * FROM ecommerce_offline.ecom_product_knowledge WHERE product_id = :productId",
                "SELECT * FROM ecommerce_offline.ecom_product_faq WHERE product_id = :productId ORDER BY faq_index",
                "SELECT * FROM ecommerce_offline.ecom_product_review WHERE product_id = :productId ORDER BY review_index",
            ],
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Hydrate FAISS child hits back to parent chunks and full product cards."
    )
    parser.add_argument("--faiss-ids", help="Comma-separated FAISS ids, for example: 1,2,3")
    parser.add_argument("--chunk-ids", help="Comma-separated chunk ids, for example: p_beauty_001:official_faq:0")
    parser.add_argument("--scores-json", help="Optional JSON object mapping faiss_id or chunk_id to score.")
    parser.add_argument("--preview-chars", type=int, default=240)
    parser.add_argument("--output")
    parser.add_argument(
        "--id-map",
        default="../../src/main/resources/db/ecommerce_offline/faiss/ecom-product-id-map.json",
    )
    parser.add_argument(
        "--chunks",
        default="../../src/main/resources/db/ecommerce_offline/ecom-product-chunks.jsonl",
    )
    parser.add_argument("--dataset-root", default="../../../ecommerce_agent_dataset")
    args = parser.parse_args()

    faiss_ids = [int(item) for item in parse_csv_values(args.faiss_ids, "--faiss-ids")]
    chunk_ids = parse_csv_values(args.chunk_ids, "--chunk-ids")
    if not faiss_ids and not chunk_ids:
        raise ValueError("one of --faiss-ids or --chunk-ids is required")

    script_dir = Path(__file__).resolve().parent
    id_map_path = (script_dir / args.id_map).resolve()
    chunks_path = (script_dir / args.chunks).resolve()
    dataset_root = (script_dir / args.dataset_root).resolve()
    scores = json.loads(args.scores_json) if args.scores_json else {}

    id_map_rows = load_json(id_map_path)
    faiss_rows_by_id = faiss_id_index(id_map_rows)
    chunks_by_id = chunk_index(load_jsonl(chunks_path))
    products_by_id = load_products(dataset_root)

    hit_refs: list[dict[str, Any]] = []
    for faiss_id in faiss_ids:
        row = faiss_rows_by_id.get(faiss_id)
        if row is None:
            raise KeyError(f"faiss_id not found: {faiss_id}")
        hit_refs.append(row | {"score": scores.get(str(faiss_id))})
    for chunk_id in chunk_ids:
        chunk = chunks_by_id.get(chunk_id)
        if chunk is None:
            raise KeyError(f"chunk_id not found: {chunk_id}")
        hit_refs.append({
            "faiss_id": None,
            "chunk_id": chunk_id,
            "product_id": chunk.get("product_id"),
            "parent_chunk_id": chunk.get("parent_chunk_id"),
            "chunk_type": chunk.get("chunk_type"),
            "chunk_index": chunk.get("chunk_index"),
            "embedding_modality": chunk.get("embedding_modality"),
            "vector_id": None,
            "score": scores.get(chunk_id),
        })

    cards = [
        resolve_hit(hit_ref, chunks_by_id, products_by_id, args.preview_chars)
        for hit_ref in hit_refs
    ]
    result = {
        "hitCount": len(cards),
        "cards": cards,
    }

    output_text = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        output_path = Path(args.output)
        if not output_path.is_absolute():
            output_path = (Path.cwd() / output_path).resolve()
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(output_text + "\n", encoding="utf-8")
        print(json.dumps({"output": str(output_path), "hitCount": len(cards)}, ensure_ascii=False, indent=2))
    else:
        print(output_text)


if __name__ == "__main__":
    main()
