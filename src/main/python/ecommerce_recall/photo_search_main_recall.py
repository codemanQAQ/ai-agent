#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
from typing import Any

try:
    from .photo_search_recall import (
        DEFAULT_API_URL,
        DEFAULT_MODEL,
        embed_image_with_doubao,
        embed_text_with_doubao,
        photo_search_by_embedding,
        vision_signals,
    )
    from .product_vector_recall import EcommerceRecallRuntime
except ImportError:
    import sys

    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    from ecommerce_recall.photo_search_recall import (  # type: ignore
        DEFAULT_API_URL,
        DEFAULT_MODEL,
        embed_image_with_doubao,
        embed_text_with_doubao,
        photo_search_by_embedding,
        vision_signals,
    )
    from ecommerce_recall.product_vector_recall import EcommerceRecallRuntime  # type: ignore


def main() -> None:
    parser = argparse.ArgumentParser(description="Main-process photo search recall adapter.")
    parser.add_argument("--request-json", help="JSON request object.")
    parser.add_argument("--request-file", help="File containing JSON request object.")
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--api-url", default=os.getenv("DOUBAO_MULTIMODAL_EMBEDDING_API_URL", DEFAULT_API_URL))
    parser.add_argument("--model", default=os.getenv("DOUBAO_EMBEDDING_MODEL", DEFAULT_MODEL))
    parser.add_argument("--timeout-seconds", type=int, default=90)
    parser.add_argument("--max-retries", type=int, default=3)
    parser.add_argument("--retry-sleep-seconds", type=float, default=2.0)
    parser.add_argument("--preview-chars", type=int, default=240)
    parser.add_argument("--resources-root")
    parser.add_argument("--dataset-root")
    parser.add_argument("--index")
    parser.add_argument("--id-map")
    parser.add_argument("--chunks")
    args = parser.parse_args()

    request = read_request(args)
    runtime = runtime_from_args(args)
    top_k = int(request.get("topK") or args.top_k)
    query_text = normalized_text(request.get("queryText"))
    image_embedding = image_embedding_from_request(request)
    processed_image = None

    if image_embedding is None:
        image_ref = normalized_text(request.get("imageRef"))
        if not image_ref:
            raise ValueError("imageEmbeddingRef/queryEmbedding or imageRef is required")
        image_embedding, processed_image = embed_image_with_doubao(
            image_ref=image_ref,
            api_key=required_api_key(),
            api_url=args.api_url,
            model=args.model,
            runtime=runtime,
            timeout_seconds=args.timeout_seconds,
            max_retries=args.max_retries,
            retry_sleep_seconds=args.retry_sleep_seconds,
        )

    text_embedding = None
    if query_text:
        text_embedding = embed_text_with_doubao(
            text=query_text,
            api_key=required_api_key(),
            api_url=args.api_url,
            model=args.model,
            timeout_seconds=args.timeout_seconds,
            max_retries=args.max_retries,
            retry_sleep_seconds=args.retry_sleep_seconds,
        )

    result = photo_search_by_embedding(
        image_embedding=image_embedding,
        text_embedding=text_embedding,
        top_k=top_k,
        runtime=runtime,
        external_refs=set(request.get("externalRefs") or []) or None,
        product_ids=set(request.get("productIds") or []) or None,
        catalog_spu_ids=set(int(item) for item in request.get("catalogSpuIds") or []) or None,
        preview_chars=int(request.get("previewChars") or args.preview_chars),
    )
    if processed_image is not None:
        result["image"] = processed_image.public_view()
        result["visionSignals"] = vision_signals(result, processed_image)
    result["model"] = args.model
    print(json.dumps(to_main_recall_output(result), ensure_ascii=False))


def read_request(args: argparse.Namespace) -> dict[str, Any]:
    if args.request_json:
        return json.loads(args.request_json)
    if args.request_file:
        return json.loads(Path(args.request_file).read_text(encoding="utf-8-sig"))
    return {}


def runtime_from_args(args: argparse.Namespace) -> EcommerceRecallRuntime:
    defaults = EcommerceRecallRuntime.defaults()
    return EcommerceRecallRuntime(
        resources_root=Path(args.resources_root).resolve() if args.resources_root else defaults.resources_root,
        dataset_root=Path(args.dataset_root).resolve() if args.dataset_root else defaults.dataset_root,
        faiss_index_path=Path(args.index).resolve() if args.index else defaults.faiss_index_path,
        id_map_path=Path(args.id_map).resolve() if args.id_map else defaults.id_map_path,
        chunks_path=Path(args.chunks).resolve() if args.chunks else defaults.chunks_path,
    )


def image_embedding_from_request(request: dict[str, Any]) -> list[float] | None:
    embedding = request.get("queryEmbedding") or request.get("imageEmbedding")
    if isinstance(embedding, list):
        return [float(item) for item in embedding]

    embedding_ref = normalized_text(request.get("imageEmbeddingRef"))
    if not embedding_ref:
        return None
    if embedding_ref.startswith("["):
        return [float(item) for item in json.loads(embedding_ref)]
    path = Path(embedding_ref)
    if path.is_file():
        return [float(item) for item in json.loads(path.read_text(encoding="utf-8-sig"))]
    raise ValueError(f"imageEmbeddingRef is not a JSON array or readable file: {embedding_ref}")


def required_api_key() -> str:
    api_key = (
        os.getenv("DOUBAO_EMBEDDING_API_KEY")
        or os.getenv("ARK_EMBEDDING_API_KEY")
        or os.getenv("ARK_API_KEY")
        or os.getenv("DOUBAO_API_KEY")
    )
    if not api_key:
        raise ValueError("DOUBAO_EMBEDDING_API_KEY is required when imageRef or queryText must be embedded")
    return api_key


def to_main_recall_output(result: dict[str, Any]) -> dict[str, Any]:
    return {
        "topK": result.get("topK"),
        "resultCount": result.get("resultCount"),
        "fusion": result.get("fusion") or {},
        "visionSignals": result.get("visionSignals") or {},
        "products": [
            to_product_candidate(card, hit)
            for card, hit in zip(result.get("cards") or [], result.get("results") or [])
        ],
    }


def to_product_candidate(card: dict[str, Any], hit: dict[str, Any]) -> dict[str, Any]:
    product = card.get("product") or {}
    matched = ((card.get("evidence") or {}).get("matchedChunk") or {})
    parent = ((card.get("evidence") or {}).get("parentChunk") or {})
    score = hit.get("fusion_score") or hit.get("score") or 0.0
    return {
        "productId": product.get("productId"),
        "spuId": product.get("catalogSpuId") or matched.get("metadata", {}).get("spuId"),
        "skuId": first_sku_id(card.get("skus") or []),
        "externalRef": product.get("externalRef"),
        "title": product.get("title"),
        "brand": product.get("brand"),
        "categoryPath": split_category_path(product.get("categoryPath")),
        "price": product.get("priceMin") or product.get("basePrice"),
        "stock": product.get("stockTotal"),
        "imageUrl": product.get("imagePath"),
        "rawScore": float(score),
        "rankScore": float(score),
        "matchedSlots": {
            "fusionChannels": hit.get("fusion_channels") or [],
            "channelScores": hit.get("channel_scores") or {},
        },
        "evidence": [
            {
                "evidenceType": "image_vector",
                "title": matched.get("sourceRef") or product.get("title"),
                "content": matched.get("textPreview"),
                "chunkId": matched.get("chunkId"),
                "parentChunkId": parent.get("chunkId"),
                "productId": product.get("productId"),
                "metadata": {
                    "faissId": (card.get("evidence") or {}).get("faissId"),
                    "vectorId": (card.get("evidence") or {}).get("vectorId"),
                    "score": score,
                    "chunkType": matched.get("chunkType"),
                    "embeddingModality": matched.get("embeddingModality"),
                    "sourceRef": matched.get("sourceRef"),
                },
            }
        ],
    }


def first_sku_id(skus: list[dict[str, Any]]) -> str | None:
    for sku in skus:
        sku_id = sku.get("skuId")
        if sku_id:
            return str(sku_id)
    return None


def split_category_path(value: Any) -> list[str]:
    text = normalized_text(value)
    if not text:
        return []
    return [item.strip() for item in text.split("/") if item.strip()]


def normalized_text(value: Any) -> str | None:
    if value is None:
        return None
    text = " ".join(str(value).split())
    return text or None


if __name__ == "__main__":
    main()
