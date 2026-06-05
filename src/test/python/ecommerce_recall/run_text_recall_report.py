from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[4]
MAIN_PYTHON = PROJECT_ROOT / "src" / "main" / "python"
RESOURCES_ROOT = PROJECT_ROOT / "src" / "main" / "resources"
DEFAULT_REPORT = PROJECT_ROOT / "target" / "ecommerce-recall" / "text-recall-report.json"
FAISS_INDEX = RESOURCES_ROOT / "db" / "ecommerce_offline" / "faiss" / "ecom-product.index"
ID_MAP = RESOURCES_ROOT / "db" / "ecommerce_offline" / "faiss" / "ecom-product-id-map.json"
CHUNKS = RESOURCES_ROOT / "db" / "ecommerce_offline" / "ecom-product-chunks.jsonl"
DATASET_ROOT = PROJECT_ROOT.parent / "ecommerce_agent_dataset"

DEFAULT_API_URL = "https://ark.cn-beijing.volces.com/api/v3/embeddings/multimodal"
DEFAULT_MODEL = "doubao-embedding-vision-251215"

sys.path.insert(0, str(MAIN_PYTHON))

from ecommerce_recall import EcommerceRecallRuntime, recall_by_embedding  # noqa: E402


def main() -> None:
    parser = argparse.ArgumentParser(description="Embed arbitrary text, run ecommerce RAG recall, and write final recall output.")
    parser.add_argument("--query", help="Arbitrary user text to recall products for.")
    parser.add_argument("--query-file", help="UTF-8 text file containing the query.")
    parser.add_argument("--top-k", default="1", help="Raw vector topK, or 'all'.")
    parser.add_argument("--speed-iterations", type=int, default=5)
    parser.add_argument("--output", default=str(DEFAULT_REPORT))
    parser.add_argument("--chunk-types", help="Comma-separated chunk type filters.")
    parser.add_argument("--modalities", default="text,image", help="Comma-separated modality filters.")
    parser.add_argument("--external-refs", help="Comma-separated product externalRef filters.")
    parser.add_argument("--product-ids", help="Comma-separated productId filters.")
    parser.add_argument("--catalog-spu-ids", help="Comma-separated Catalog SPU id filters.")
    parser.add_argument("--api-url", default=os.getenv("DOUBAO_MULTIMODAL_EMBEDDING_API_URL", DEFAULT_API_URL))
    parser.add_argument("--model", default=os.getenv("DOUBAO_EMBEDDING_MODEL", DEFAULT_MODEL))
    parser.add_argument("--timeout-seconds", type=int, default=60)
    args = parser.parse_args()

    query_text = read_query_text(args)
    top_k = resolve_top_k(args.top_k)
    runtime = EcommerceRecallRuntime(
        resources_root=RESOURCES_ROOT,
        dataset_root=DATASET_ROOT,
        faiss_index_path=FAISS_INDEX,
        id_map_path=ID_MAP,
        chunks_path=CHUNKS,
    )

    embed_started = time.perf_counter()
    query_embedding = embed_text_with_doubao(
        query_text=query_text,
        api_url=args.api_url,
        api_key=required_api_key(),
        model=args.model,
        timeout_seconds=args.timeout_seconds,
    )
    embedding_elapsed_ms = elapsed_ms(embed_started)

    recall_started = time.perf_counter()
    result = recall_by_embedding(
        query_embedding=query_embedding,
        top_k=top_k,
        hydrate=True,
        runtime=runtime,
        chunk_types=parse_csv(args.chunk_types),
        modalities=parse_csv(args.modalities),
        external_refs=parse_csv(args.external_refs),
        product_ids=parse_csv(args.product_ids),
        catalog_spu_ids=parse_int_csv(args.catalog_spu_ids),
    )
    recall_with_hydration_elapsed_ms = elapsed_ms(recall_started)
    final_recall_results = build_final_recall_results(result)

    speed_samples_ms: list[float] = []
    for _ in range(args.speed_iterations):
        speed_started = time.perf_counter()
        recall_by_embedding(
            query_embedding=query_embedding,
            top_k=min(top_k, 20),
            hydrate=False,
            runtime=runtime,
            chunk_types=parse_csv(args.chunk_types),
            modalities=parse_csv(args.modalities),
            external_refs=parse_csv(args.external_refs),
            product_ids=parse_csv(args.product_ids),
            catalog_spu_ids=parse_int_csv(args.catalog_spu_ids),
        )
        speed_samples_ms.append(elapsed_ms(speed_started))

    output: dict[str, Any] = {
        "products": [to_rag_product_output(item) for item in final_recall_results],
        "speedTest": {
            "queryEmbeddingMs": round(embedding_elapsed_ms, 2),
            "recallWithHydrationMs": round(recall_with_hydration_elapsed_ms, 2),
            "endToEndMs": round(embedding_elapsed_ms + recall_with_hydration_elapsed_ms, 2),
            "recallOnly": {
                "iterations": args.speed_iterations,
                "topK": min(top_k, 20),
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

    print_ascii_summary(output_path, query_text, output)


def read_query_text(args: argparse.Namespace) -> str:
    if args.query:
        query = args.query
    elif args.query_file:
        query = Path(args.query_file).read_text(encoding="utf-8-sig")
    else:
        query = os.getenv("ECOM_RECALL_QUERY_TEXT", "")
    query = " ".join(query.split())
    if not query:
        raise ValueError("query text is required; use --query, --query-file, or ECOM_RECALL_QUERY_TEXT")
    return query


def required_api_key() -> str:
    api_key = (
        os.getenv("DOUBAO_EMBEDDING_API_KEY")
        or os.getenv("ARK_EMBEDDING_API_KEY")
        or os.getenv("ARK_API_KEY")
        or os.getenv("DOUBAO_API_KEY")
    )
    if not api_key:
        raise ValueError("DOUBAO_EMBEDDING_API_KEY is required for query text embedding")
    return api_key


def embed_text_with_doubao(
    query_text: str,
    api_url: str,
    api_key: str,
    model: str,
    timeout_seconds: int,
) -> list[float]:
    payload = json.dumps(
        {
            "model": model,
            "input": [{"type": "text", "text": query_text}],
        },
        ensure_ascii=False,
    ).encode("utf-8")
    request = urllib.request.Request(
        api_url,
        data=payload,
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            body = response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Doubao embedding HTTP {error.code}: {detail}") from error
    except urllib.error.URLError as error:
        raise RuntimeError(f"Doubao embedding request failed: {error}") from error

    data = json.loads(body)
    payload_data = data.get("data")
    if isinstance(payload_data, dict) and isinstance(payload_data.get("embedding"), list):
        return payload_data["embedding"]
    if isinstance(payload_data, list) and payload_data and isinstance(payload_data[0].get("embedding"), list):
        return payload_data[0]["embedding"]
    raise RuntimeError(f"unexpected Doubao embedding response shape: keys={list(data.keys())}")


def build_final_recall_results(result: dict[str, Any]) -> list[dict[str, Any]]:
    by_product: dict[str, dict[str, Any]] = {}
    order: list[str] = []
    hit_by_chunk_id = {hit["chunk_id"]: hit for hit in result["results"]}

    for raw_rank, card in enumerate(result["cards"], start=1):
        product_id = card["product"]["productId"]
        hit = hit_by_chunk_id.get(card["evidence"]["matchedChunk"]["chunkId"], {})
        score = float(hit.get("score") or 0.0)
        if product_id not in by_product:
            order.append(product_id)
            by_product[product_id] = {
                "rank": len(order),
                "bestScore": score,
                "product": card["product"],
                "skus": card["skus"],
                "knowledge": card["knowledge"],
                "evidences": [],
                "matchedChunkTypes": [],
            }
        item = by_product[product_id]
        item["bestScore"] = max(float(item["bestScore"]), score)
        evidence = {
            "rawRank": raw_rank,
            "score": score,
            "faissId": hit.get("faiss_id"),
            "vectorId": hit.get("vector_id"),
            "chunkId": card["evidence"]["matchedChunk"]["chunkId"],
            "chunkType": card["evidence"]["matchedChunk"]["chunkType"],
            "chunkIndex": card["evidence"]["matchedChunk"]["chunkIndex"],
            "embeddingModality": card["evidence"]["matchedChunk"]["embeddingModality"],
            "parentChunkId": card["evidence"]["parentChunk"]["chunkId"],
            "sourceRef": card["evidence"]["matchedChunk"]["sourceRef"],
            "textPreview": card["evidence"]["matchedChunk"]["textPreview"],
        }
        item["evidences"].append(evidence)
        chunk_type = evidence["chunkType"]
        if chunk_type not in item["matchedChunkTypes"]:
            item["matchedChunkTypes"].append(chunk_type)

    return [by_product[product_id] for product_id in order]


def to_rag_product_output(item: dict[str, Any]) -> dict[str, Any]:
    product = item["product"]
    return {
        "rank": item["rank"],
        "score": item["bestScore"],
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
        "skus": item["skus"],
        "evidences": [
            {
                "score": evidence["score"],
                "chunkId": evidence["chunkId"],
                "chunkType": evidence["chunkType"],
                "embeddingModality": evidence["embeddingModality"],
                "textPreview": evidence["textPreview"],
            }
            for evidence in item["evidences"]
        ],
    }


def resolve_top_k(value: str) -> int:
    if value.strip().lower() == "all":
        return len(json.loads(ID_MAP.read_text(encoding="utf-8-sig")))
    return int(value)


def parse_csv(value: str | None) -> set[str] | None:
    if value is None:
        return None
    items = {item.strip() for item in value.split(",") if item.strip()}
    return items or None


def parse_int_csv(value: str | None) -> set[int] | None:
    values = parse_csv(value)
    if not values:
        return None
    return {int(item) for item in values}


def elapsed_ms(started: float) -> float:
    return (time.perf_counter() - started) * 1000


def print_ascii_summary(output_path: Path, query_text: str, output: dict[str, Any]) -> None:
    products = output["products"]
    speed = output["speedTest"]
    print("=== ecommerce rag recall output ===")
    print(f"report={output_path}")
    print(f"query={ascii_safe(query_text)}")
    print(f"product_count={len(products)}")
    print(f"query_embedding_ms={speed['queryEmbeddingMs']}")
    print(f"recall_with_hydration_ms={speed['recallWithHydrationMs']}")
    print(f"end_to_end_ms={speed['endToEndMs']}")
    print(f"recall_only_average_ms={speed['recallOnly']['averageMs']}")
    print("products=")
    for item in products[:10]:
        product = item["product"]
        print(
            f"{item['rank']}. productId={product['productId']} "
            f"score={item['score']:.6f} "
            f"evidenceCount={len(item['evidences'])} "
            f"title={ascii_safe(product['title'])}"
        )


def ascii_safe(value: object) -> str:
    return str(value).encode("ascii", errors="backslashreplace").decode("ascii")


if __name__ == "__main__":
    main()
