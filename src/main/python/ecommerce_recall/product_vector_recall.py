#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import faiss
import numpy as np


DEFAULT_STOCK = 100
CATEGORY_ALIASES = {
    "食品生活": "食品饮料",
}


@dataclass(frozen=True)
class EcommerceRecallRuntime:
    resources_root: Path | None = None
    dataset_root: Path | None = None
    faiss_index_path: Path | None = None
    id_map_path: Path | None = None
    chunks_path: Path | None = None

    @classmethod
    def defaults(cls) -> "EcommerceRecallRuntime":
        module_path = Path(__file__).resolve()
        main_root = module_path.parents[2]
        project_root = module_path.parents[4]
        resources_root = main_root / "resources"
        offline_root = resources_root / "db" / "ecommerce_offline"
        return cls(
            resources_root=resources_root,
            dataset_root=project_root.parent / "ecommerce_agent_dataset",
            faiss_index_path=offline_root / "faiss" / "ecom-product.index",
            id_map_path=offline_root / "faiss" / "ecom-product-id-map.json",
            chunks_path=offline_root / "ecom-product-chunks.jsonl",
        )


def recall_by_embedding(
    query_embedding: list[float],
    top_k: int = 5,
    hydrate: bool = True,
    runtime: EcommerceRecallRuntime | None = None,
    chunk_types: set[str] | None = None,
    modalities: set[str] | None = None,
    external_refs: set[str] | None = None,
    product_ids: set[str] | None = None,
    catalog_spu_ids: set[int] | None = None,
    preview_chars: int = 240,
) -> dict[str, Any]:
    runtime = runtime or EcommerceRecallRuntime.defaults()
    faiss_index = faiss.read_index(str(required_path(runtime.faiss_index_path, "faiss_index_path")))
    id_map = load_faiss_id_map(required_path(runtime.id_map_path, "id_map_path"))

    query = np.array([query_embedding], dtype="float32")
    faiss.normalize_L2(query)
    search_k = max(top_k, 1)
    if chunk_types or modalities or external_refs or product_ids or catalog_spu_ids:
        search_k = min(max(top_k * 5, top_k), faiss_index.ntotal)
    scores, ids = faiss_index.search(query, search_k)

    results: list[dict[str, Any]] = []
    for score, faiss_id in zip(scores[0], ids[0]):
        if faiss_id < 0:
            continue
        row = id_map[int(faiss_id)]
        if chunk_types and row.get("chunk_type") not in chunk_types:
            continue
        if modalities and row.get("embedding_modality") not in modalities:
            continue
        if not row_matches_scope(row, external_refs, product_ids, catalog_spu_ids):
            continue
        results.append({"score": float(score), **row})
        if len(results) >= top_k:
            break

    response: dict[str, Any] = {
        "topK": top_k,
        "resultCount": len(results),
        "results": results,
    }
    if hydrate:
        response["cards"] = hydrate_hit_refs(results, runtime, preview_chars)
    return response


def row_matches_scope(
    row: dict[str, Any],
    external_refs: set[str] | None,
    product_ids: set[str] | None,
    catalog_spu_ids: set[int] | None,
) -> bool:
    metadata = row.get("metadata") or {}
    product_id = str(metadata.get("productId") or row.get("product_id"))
    external_ref = str(metadata.get("externalRef") or row.get("product_id"))
    if external_refs and external_ref not in external_refs:
        return False
    if product_ids and product_id not in product_ids:
        return False
    if catalog_spu_ids:
        raw_spu_id = metadata.get("spuId") or metadata.get("catalogSpuId")
        if raw_spu_id is None:
            return False
        try:
            return int(raw_spu_id) in catalog_spu_ids
        except (TypeError, ValueError):
            return False
    return True


def hydrate_by_faiss_ids(
    faiss_ids: list[int],
    scores: dict[int | str, float] | None = None,
    runtime: EcommerceRecallRuntime | None = None,
    preview_chars: int = 240,
) -> dict[str, Any]:
    runtime = runtime or EcommerceRecallRuntime.defaults()
    id_map = load_faiss_id_map(required_path(runtime.id_map_path, "id_map_path"))
    hit_refs: list[dict[str, Any]] = []
    score_map = scores or {}
    for faiss_id in faiss_ids:
        row = id_map.get(int(faiss_id))
        if row is None:
            raise KeyError(f"faiss_id not found: {faiss_id}")
        hit_refs.append(row | {"score": score_map.get(faiss_id) or score_map.get(str(faiss_id))})
    cards = hydrate_hit_refs(hit_refs, runtime, preview_chars)
    return {
        "hitCount": len(cards),
        "cards": cards,
    }


def hydrate_by_chunk_ids(
    chunk_ids: list[str],
    scores: dict[str, float] | None = None,
    runtime: EcommerceRecallRuntime | None = None,
    preview_chars: int = 240,
) -> dict[str, Any]:
    runtime = runtime or EcommerceRecallRuntime.defaults()
    chunks_by_id = chunk_index(load_jsonl(required_path(runtime.chunks_path, "chunks_path")))
    hit_refs: list[dict[str, Any]] = []
    score_map = scores or {}
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
            "score": score_map.get(chunk_id),
        })
    cards = hydrate_hit_refs(hit_refs, runtime, preview_chars, chunks_by_id=chunks_by_id)
    return {
        "hitCount": len(cards),
        "cards": cards,
    }


def hydrate_hit_refs(
    hit_refs: list[dict[str, Any]],
    runtime: EcommerceRecallRuntime,
    preview_chars: int,
    chunks_by_id: dict[str, dict[str, Any]] | None = None,
) -> list[dict[str, Any]]:
    chunks = chunks_by_id or chunk_index(load_jsonl(required_path(runtime.chunks_path, "chunks_path")))
    products = load_products(required_path(runtime.dataset_root, "dataset_root"))
    return [resolve_hit(hit_ref, chunks, products, preview_chars) for hit_ref in hit_refs]


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


def load_faiss_id_map(path: Path) -> dict[int, dict[str, Any]]:
    rows = load_json(path)
    return {int(row["faiss_id"]): row for row in rows}


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
    with path.open("r", encoding="utf-8-sig") as file:
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


def chunk_index(chunks: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    index: dict[str, dict[str, Any]] = {}
    for chunk in chunks:
        chunk_id = chunk["chunk_id"]
        if chunk_id in index:
            raise ValueError(f"duplicate chunk_id: {chunk_id}")
        index[chunk_id] = chunk
    return index


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


def required_path(path: Path | None, name: str) -> Path:
    if path is None:
        raise ValueError(f"{name} is required")
    return path


def parse_csv_values(value: str | None) -> list[str]:
    if value is None or not value.strip():
        return []
    return [item.strip() for item in value.split(",") if item.strip()]


def parse_int_csv_values(value: str | None) -> list[int]:
    return [int(item) for item in parse_csv_values(value)]


def runtime_from_args(args: argparse.Namespace) -> EcommerceRecallRuntime:
    defaults = EcommerceRecallRuntime.defaults()
    return EcommerceRecallRuntime(
        resources_root=Path(args.resources_root).resolve() if args.resources_root else defaults.resources_root,
        dataset_root=Path(args.dataset_root).resolve() if args.dataset_root else defaults.dataset_root,
        faiss_index_path=Path(args.index).resolve() if args.index else defaults.faiss_index_path,
        id_map_path=Path(args.id_map).resolve() if args.id_map else defaults.id_map_path,
        chunks_path=Path(args.chunks).resolve() if args.chunks else defaults.chunks_path,
    )


def read_request(args: argparse.Namespace) -> dict[str, Any]:
    if args.request_json:
        return json.loads(args.request_json)
    if args.request_file:
        return load_json(Path(args.request_file))
    if args.embedding_json_file:
        return {"queryEmbedding": load_json(Path(args.embedding_json_file))}
    if args.embedding_json:
        return {"queryEmbedding": json.loads(args.embedding_json)}
    return {}


def write_result(result: object, output: str | None) -> None:
    output_text = json.dumps(result, ensure_ascii=False, indent=2)
    if not output:
        print(output_text)
        return
    output_path = Path(output)
    if not output_path.is_absolute():
        output_path = (Path.cwd() / output_path).resolve()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(output_text + "\n", encoding="utf-8")
    print(json.dumps({"output": str(output_path)}, ensure_ascii=True))


def main() -> None:
    parser = argparse.ArgumentParser(description="Main-runtime product vector recall interface.")
    parser.add_argument("--mode", choices=["recall", "hydrate-faiss", "hydrate-chunk"], default="recall")
    parser.add_argument("--request-json", help="JSON request object.")
    parser.add_argument("--request-file", help="File containing JSON request object.")
    parser.add_argument("--embedding-json", help="JSON array query embedding.")
    parser.add_argument("--embedding-json-file", help="File containing JSON array query embedding.")
    parser.add_argument("--faiss-ids", help="Comma-separated FAISS ids for hydrate-faiss mode.")
    parser.add_argument("--chunk-ids", help="Comma-separated chunk ids for hydrate-chunk mode.")
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--no-hydrate", action="store_true")
    parser.add_argument("--chunk-types", help="Comma-separated chunk type filters.")
    parser.add_argument("--modalities", help="Comma-separated modality filters, for example text,image.")
    parser.add_argument("--external-refs", help="Comma-separated product externalRef filters.")
    parser.add_argument("--product-ids", help="Comma-separated productId filters.")
    parser.add_argument("--catalog-spu-ids", help="Comma-separated Catalog SPU id filters.")
    parser.add_argument("--preview-chars", type=int, default=240)
    parser.add_argument("--output")
    parser.add_argument("--resources-root")
    parser.add_argument("--dataset-root")
    parser.add_argument("--index")
    parser.add_argument("--id-map")
    parser.add_argument("--chunks")
    args = parser.parse_args()

    runtime = runtime_from_args(args)
    request = read_request(args)
    top_k = int(request.get("topK", args.top_k))
    preview_chars = int(request.get("previewChars", args.preview_chars))

    if args.mode == "recall":
        query_embedding = request.get("queryEmbedding")
        if not query_embedding:
            raise ValueError("queryEmbedding is required for recall mode")
        result = recall_by_embedding(
            query_embedding=query_embedding,
            top_k=top_k,
            hydrate=not args.no_hydrate and bool(request.get("hydrate", True)),
            runtime=runtime,
            chunk_types=set(request.get("chunkTypes") or parse_csv_values(args.chunk_types)) or None,
            modalities=set(request.get("modalities") or parse_csv_values(args.modalities)) or None,
            external_refs=set(request.get("externalRefs") or parse_csv_values(args.external_refs)) or None,
            product_ids=set(request.get("productIds") or parse_csv_values(args.product_ids)) or None,
            catalog_spu_ids=set(request.get("catalogSpuIds") or parse_int_csv_values(args.catalog_spu_ids)) or None,
            preview_chars=preview_chars,
        )
    elif args.mode == "hydrate-faiss":
        faiss_ids = request.get("faissIds") or [int(item) for item in parse_csv_values(args.faiss_ids)]
        result = hydrate_by_faiss_ids(faiss_ids, runtime=runtime, preview_chars=preview_chars)
    else:
        chunk_ids = request.get("chunkIds") or parse_csv_values(args.chunk_ids)
        result = hydrate_by_chunk_ids(chunk_ids, runtime=runtime, preview_chars=preview_chars)

    write_result(result, args.output)


if __name__ == "__main__":
    main()
