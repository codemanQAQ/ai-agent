from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[4]
MAIN_PYTHON = PROJECT_ROOT / "src" / "main" / "python"
RESOURCES_ROOT = PROJECT_ROOT / "src" / "main" / "resources"
DEFAULT_REPORT = PROJECT_ROOT / "target" / "ecommerce-recall" / "image-recall-report.json"
FAISS_INDEX = RESOURCES_ROOT / "db" / "ecommerce_offline" / "faiss" / "ecom-product.index"
ID_MAP = RESOURCES_ROOT / "db" / "ecommerce_offline" / "faiss" / "ecom-product-id-map.json"
CHUNKS = RESOURCES_ROOT / "db" / "ecommerce_offline" / "ecom-product-chunks.jsonl"
DATASET_ROOT = PROJECT_ROOT.parent / "ecommerce_agent_dataset"

DEFAULT_API_URL = "https://ark.cn-beijing.volces.com/api/v3/embeddings/multimodal"
DEFAULT_MODEL = "doubao-embedding-vision-251215"

sys.path.insert(0, str(MAIN_PYTHON))

from ecommerce_recall import EcommerceRecallRuntime  # noqa: E402
from ecommerce_recall.photo_search_recall import (  # noqa: E402
    embed_image_with_doubao,
    embed_text_with_doubao,
    photo_search_by_embedding,
    vision_signals,
)


def main() -> None:
    parser = argparse.ArgumentParser(description="Embed an imageRef, run ecommerce photo-search recall, and write final recall output.")
    parser.add_argument("--image-ref", required=True, help="Local image path, dataset-relative image path, or data URL.")
    parser.add_argument("--query", help="Optional text constraint fused with image recall.")
    parser.add_argument("--query-file", help="UTF-8 text file containing optional text constraint.")
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--speed-iterations", type=int, default=5)
    parser.add_argument("--output", default=str(DEFAULT_REPORT))
    parser.add_argument("--api-url", default=os.getenv("DOUBAO_MULTIMODAL_EMBEDDING_API_URL", DEFAULT_API_URL))
    parser.add_argument("--model", default=os.getenv("DOUBAO_EMBEDDING_MODEL", DEFAULT_MODEL))
    parser.add_argument("--timeout-seconds", type=int, default=90)
    parser.add_argument("--max-retries", type=int, default=3)
    parser.add_argument("--retry-sleep-seconds", type=float, default=2.0)
    args = parser.parse_args()

    if args.top_k <= 0:
        raise ValueError("--top-k must be positive")
    if args.speed_iterations <= 0:
        raise ValueError("--speed-iterations must be positive")

    runtime = EcommerceRecallRuntime(
        resources_root=RESOURCES_ROOT,
        dataset_root=DATASET_ROOT,
        faiss_index_path=FAISS_INDEX,
        id_map_path=ID_MAP,
        chunks_path=CHUNKS,
    )
    api_key = required_api_key()
    query_text = read_query_text(args)

    image_embedding_started = time.perf_counter()
    image_embedding, processed_image = embed_image_with_doubao(
        image_ref=args.image_ref,
        api_key=api_key,
        api_url=args.api_url,
        model=args.model,
        runtime=runtime,
        timeout_seconds=args.timeout_seconds,
        max_retries=args.max_retries,
        retry_sleep_seconds=args.retry_sleep_seconds,
    )
    image_embedding_ms = elapsed_ms(image_embedding_started)

    text_embedding = None
    text_embedding_ms = 0.0
    if query_text:
        text_embedding_started = time.perf_counter()
        text_embedding = embed_text_with_doubao(
            text=query_text,
            api_key=api_key,
            api_url=args.api_url,
            model=args.model,
            timeout_seconds=args.timeout_seconds,
            max_retries=args.max_retries,
            retry_sleep_seconds=args.retry_sleep_seconds,
        )
        text_embedding_ms = elapsed_ms(text_embedding_started)

    recall_started = time.perf_counter()
    result = photo_search_by_embedding(
        image_embedding=image_embedding,
        text_embedding=text_embedding,
        top_k=args.top_k,
        runtime=runtime,
    )
    recall_with_hydration_ms = elapsed_ms(recall_started)
    result["image"] = processed_image.public_view()
    result["model"] = args.model
    result["visionSignals"] = vision_signals(result, processed_image)

    speed_samples_ms: list[float] = []
    for _ in range(args.speed_iterations):
        speed_started = time.perf_counter()
        photo_search_by_embedding(
            image_embedding=image_embedding,
            text_embedding=text_embedding,
            top_k=args.top_k,
            runtime=runtime,
        )
        speed_samples_ms.append(elapsed_ms(speed_started))

    output = {
        "image": result["image"],
        "visionSignals": result["visionSignals"],
        "fusion": result["fusion"],
        "products": [to_rag_product_output(card, hit) for card, hit in zip(result["cards"], result["results"])],
        "speedTest": {
            "imageEmbeddingMs": round(image_embedding_ms, 2),
            "textEmbeddingMs": round(text_embedding_ms, 2),
            "recallWithHydrationMs": round(recall_with_hydration_ms, 2),
            "endToEndMs": round(image_embedding_ms + text_embedding_ms + recall_with_hydration_ms, 2),
            "recallOnly": {
                "iterations": args.speed_iterations,
                "topK": args.top_k,
                "averageMs": round(sum(speed_samples_ms) / len(speed_samples_ms), 2),
                "minMs": round(min(speed_samples_ms), 2),
                "maxMs": round(max(speed_samples_ms), 2),
            },
        },
    }

    output_path = Path(args.output)
    if not output_path.is_absolute():
        output_path = (Path.cwd() / output_path).resolve()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print_ascii_summary(output_path, args.image_ref, query_text, output)


def read_query_text(args: argparse.Namespace) -> str | None:
    if args.query:
        query = args.query
    elif args.query_file:
        query = Path(args.query_file).read_text(encoding="utf-8-sig")
    else:
        query = os.getenv("ECOM_RECALL_QUERY_TEXT", "")
    query = " ".join(query.split())
    return query or None


def required_api_key() -> str:
    api_key = (
        os.getenv("DOUBAO_EMBEDDING_API_KEY")
        or os.getenv("ARK_EMBEDDING_API_KEY")
        or os.getenv("ARK_API_KEY")
        or os.getenv("DOUBAO_API_KEY")
    )
    if not api_key:
        raise ValueError("DOUBAO_EMBEDDING_API_KEY is required for image embedding")
    return api_key


def to_rag_product_output(card: dict[str, Any], hit: dict[str, Any]) -> dict[str, Any]:
    product = card["product"]
    matched = card["evidence"]["matchedChunk"]
    return {
        "score": hit.get("fusion_score") or hit.get("score"),
        "product": {
            "productId": product["productId"],
            "externalRef": product["externalRef"],
            "title": product["title"],
            "brand": product["brand"],
            "categoryPath": product["categoryPath"],
            "priceMin": product["priceMin"],
            "priceMax": product["priceMax"],
            "stockTotal": product["stockTotal"],
            "imagePath": product["imagePath"],
        },
        "skus": card["skus"],
        "evidences": [
            {
                "score": matched_score(hit),
                "chunkId": matched["chunkId"],
                "chunkType": matched["chunkType"],
                "embeddingModality": matched["embeddingModality"],
                "sourceRef": matched["sourceRef"],
                "textPreview": matched["textPreview"],
                "fusionChannels": hit.get("fusion_channels", []),
                "channelScores": hit.get("channel_scores", {}),
            }
        ],
    }


def matched_score(hit: dict[str, Any]) -> float | None:
    channel_scores = hit.get("channel_scores") or {}
    image_score = channel_scores.get("image", {}).get("score")
    return image_score if image_score is not None else hit.get("score")


def elapsed_ms(started: float) -> float:
    return (time.perf_counter() - started) * 1000


def print_ascii_summary(output_path: Path, image_ref: str, query_text: str | None, output: dict[str, Any]) -> None:
    print("=== ecommerce photo-search recall output ===")
    print(f"report={output_path}")
    print(f"image_ref={ascii_safe(image_ref)}")
    if query_text:
        print(f"query={ascii_safe(query_text)}")
    print(f"product_count={len(output['products'])}")
    print(f"confidence={output['visionSignals']['confidence']}")
    print(f"image_embedding_ms={output['speedTest']['imageEmbeddingMs']}")
    print(f"recall_with_hydration_ms={output['speedTest']['recallWithHydrationMs']}")
    print(f"recall_only_average_ms={output['speedTest']['recallOnly']['averageMs']}")
    print("products=")
    for index, item in enumerate(output["products"][:10], start=1):
        product = item["product"]
        print(
            f"{index}. productId={product['productId']} "
            f"score={float(item['score'] or 0.0):.6f} "
            f"title={ascii_safe(product['title'])}"
        )


def ascii_safe(value: object) -> str:
    return str(value).encode("ascii", errors="backslashreplace").decode("ascii")


if __name__ == "__main__":
    main()
