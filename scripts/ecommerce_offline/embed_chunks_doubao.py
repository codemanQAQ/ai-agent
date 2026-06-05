#!/usr/bin/env python3
from __future__ import annotations

import argparse
import base64
import json
import mimetypes
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


DEFAULT_API_URL = "https://ark.cn-beijing.volces.com/api/v3/embeddings/multimodal"
DEFAULT_MODEL = "doubao-embedding-vision-251215"


def load_embedding_chunks(path: Path, modalities: set[str]) -> list[dict[str, Any]]:
    chunks: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as file:
        for line_no, line in enumerate(file, start=1):
            if not line.strip():
                continue
            row = json.loads(line)
            modality = row.get("embedding_modality")
            if row.get("embedding_required") is True and modality in modalities:
                validate_chunk(row, line_no)
                chunks.append(row)
    return chunks


def validate_chunk(chunk: dict[str, Any], line_no: int) -> None:
    modality = chunk.get("embedding_modality")
    if modality == "text":
        text = chunk.get("text_content")
        if not isinstance(text, str) or not text.strip():
            raise ValueError(f"text chunk has empty text_content at line {line_no}: {chunk.get('chunk_id')}")
    elif modality == "image":
        image_path = image_path_from_chunk(chunk)
        if not image_path:
            raise ValueError(f"image chunk has no imagePath at line {line_no}: {chunk.get('chunk_id')}")
    else:
        raise ValueError(f"unsupported embedding modality at line {line_no}: {modality}")


def image_path_from_chunk(chunk: dict[str, Any]) -> str | None:
    source_ref = chunk.get("source_ref") or {}
    metadata = chunk.get("metadata") or {}
    return source_ref.get("imagePath") or metadata.get("imagePath")


def load_completed(output: Path, model: str) -> set[tuple[str, str, str]]:
    if not output.exists():
        return set()
    completed: set[tuple[str, str, str]] = set()
    with output.open("r", encoding="utf-8") as file:
        for line in file:
            if not line.strip():
                continue
            row = json.loads(line)
            if row.get("model") == model:
                completed.add((row["chunk_id"], row["embedding_fingerprint"], row["model"]))
    return completed


def embedding_fingerprint(chunk: dict[str, Any]) -> str:
    if chunk.get("embedding_modality") == "text":
        return chunk["content_sha256"]
    return image_path_from_chunk(chunk) or chunk["chunk_id"]


def batched(items: list[dict[str, Any]], batch_size: int) -> list[list[dict[str, Any]]]:
    return [items[index:index + batch_size] for index in range(0, len(items), batch_size)]


def build_input_item(chunk: dict[str, Any], dataset_root: Path) -> dict[str, Any]:
    modality = chunk["embedding_modality"]
    if modality == "text":
        return {
            "type": "text",
            "text": chunk["text_content"],
        }
    if modality == "image":
        return {
            "type": "image_url",
            "image_url": {
                "url": resolve_image_url(image_path_from_chunk(chunk), dataset_root),
            },
        }
    raise ValueError(f"unsupported embedding modality: {modality}")


def resolve_image_url(image_path: str | None, dataset_root: Path) -> str:
    if not image_path:
        raise ValueError("image_path is required")
    if image_path.startswith("http://") or image_path.startswith("https://") or image_path.startswith("data:"):
        return image_path
    path = (dataset_root / image_path).resolve()
    if not path.is_file():
        raise FileNotFoundError(f"image file does not exist: {path}")
    mime_type = mimetypes.guess_type(path.name)[0] or "image/jpeg"
    data = base64.b64encode(path.read_bytes()).decode("ascii")
    return f"data:{mime_type};base64,{data}"


def call_doubao_embeddings(
    api_url: str,
    api_key: str,
    model: str,
    input_items: list[dict[str, Any]],
    timeout_seconds: int,
) -> list[list[float]]:
    payload = json.dumps(
        {
            "model": model,
            "input": input_items,
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
        raise RuntimeError(f"Doubao multimodal embedding HTTP {error.code}: {detail}") from error
    except urllib.error.URLError as error:
        raise RuntimeError(f"Doubao multimodal embedding request failed: {error}") from error

    data = json.loads(body)
    payload = data.get("data")
    if isinstance(payload, dict) and isinstance(payload.get("embedding"), list):
        if len(input_items) != 1:
            raise RuntimeError("Doubao multimodal embedding API returns one embedding for one multimodal input; use batch-size=1")
        return [payload["embedding"]]

    if isinstance(payload, list):
        embeddings_by_index: dict[int, list[float]] = {}
        for item in payload:
            embeddings_by_index[int(item["index"])] = item["embedding"]
        embeddings = [embeddings_by_index[index] for index in range(len(input_items))]
        if len(embeddings) != len(input_items):
            raise RuntimeError(f"Doubao returned {len(embeddings)} embeddings for {len(input_items)} inputs")
        return embeddings

    raise RuntimeError(f"unexpected Doubao embedding response shape: keys={list(data.keys())}")


def embed_with_retries(
    api_url: str,
    api_key: str,
    model: str,
    input_items: list[dict[str, Any]],
    timeout_seconds: int,
    max_retries: int,
    retry_sleep_seconds: float,
) -> list[list[float]]:
    last_error: Exception | None = None
    for attempt in range(max_retries + 1):
        try:
            return call_doubao_embeddings(api_url, api_key, model, input_items, timeout_seconds)
        except Exception as error:
            last_error = error
            if attempt >= max_retries:
                break
            sleep = retry_sleep_seconds * (2 ** attempt)
            print(f"embedding batch failed, retrying in {sleep:.1f}s: {error}", file=sys.stderr)
            time.sleep(sleep)
    raise RuntimeError(f"embedding batch failed after {max_retries + 1} attempts: {last_error}") from last_error


def build_embedding_row(chunk: dict[str, Any], model: str, embedding: list[float]) -> dict[str, Any]:
    modality = chunk["embedding_modality"]
    return {
        "chunk_id": chunk["chunk_id"],
        "product_id": chunk["product_id"],
        "parent_chunk_id": chunk.get("parent_chunk_id"),
        "chunk_type": chunk["chunk_type"],
        "chunk_index": chunk["chunk_index"],
        "embedding_modality": modality,
        "embedding_fingerprint": embedding_fingerprint(chunk),
        "content_sha256": chunk.get("content_sha256"),
        "model": model,
        "dimension": len(embedding),
        "vector_id": f"ecom-{modality}:{model}:{chunk['chunk_id']}",
        "embedding": embedding,
        "metadata": chunk.get("metadata") or {},
        "source_ref": chunk.get("source_ref") or {},
    }


def parse_modalities(value: str) -> set[str]:
    modalities = {item.strip() for item in value.split(",") if item.strip()}
    unsupported = modalities - {"text", "image"}
    if unsupported:
        raise ValueError(f"unsupported modalities: {sorted(unsupported)}")
    return modalities or {"text", "image"}


def main() -> None:
    parser = argparse.ArgumentParser(description="Embed ecommerce text and image chunks with Doubao multimodal embeddings API.")
    parser.add_argument(
        "--input",
        default="../../src/main/resources/db/ecommerce_offline/ecom-product-chunks.jsonl",
        help="Input chunk JSONL path.",
    )
    parser.add_argument(
        "--dataset-root",
        default="../../../ecommerce_agent_dataset",
        help="Dataset root used to resolve local image paths.",
    )
    parser.add_argument(
        "--output",
        default="../../src/main/resources/db/ecommerce_offline/ecom-product-embeddings.jsonl",
        help="Output embedding JSONL path.",
    )
    parser.add_argument("--api-url", default=os.getenv("DOUBAO_MULTIMODAL_EMBEDDING_API_URL", DEFAULT_API_URL))
    parser.add_argument("--model", default=os.getenv("DOUBAO_EMBEDDING_MODEL", DEFAULT_MODEL))
    parser.add_argument("--api-key-env", default="DOUBAO_EMBEDDING_API_KEY")
    parser.add_argument("--modalities", default="text,image", help="Comma-separated modalities: text,image")
    parser.add_argument("--batch-size", type=int, default=1)
    parser.add_argument("--limit", type=int, default=0, help="Limit chunks for smoke testing. 0 means no limit.")
    parser.add_argument("--timeout-seconds", type=int, default=90)
    parser.add_argument("--max-retries", type=int, default=3)
    parser.add_argument("--retry-sleep-seconds", type=float, default=2.0)
    parser.add_argument("--dry-run", action="store_true", help="Load and count chunks without calling API.")
    args = parser.parse_args()

    if args.batch_size != 1:
        raise ValueError("Doubao multimodal embedding returns one vector per multimodal input; keep --batch-size 1")

    script_dir = Path(__file__).resolve().parent
    input_path = (script_dir / args.input).resolve()
    dataset_root = (script_dir / args.dataset_root).resolve()
    output_path = (script_dir / args.output).resolve()
    modalities = parse_modalities(args.modalities)

    chunks = load_embedding_chunks(input_path, modalities)
    if args.limit > 0:
        chunks = chunks[:args.limit]

    completed = load_completed(output_path, args.model)
    pending = [
        chunk for chunk in chunks
        if (chunk["chunk_id"], embedding_fingerprint(chunk), args.model) not in completed
    ]

    by_modality: dict[str, int] = {}
    for chunk in chunks:
        modality = chunk["embedding_modality"]
        by_modality[modality] = by_modality.get(modality, 0) + 1

    summary = {
        "input": str(input_path),
        "dataset_root": str(dataset_root),
        "output": str(output_path),
        "model": args.model,
        "api_url": args.api_url,
        "selected_chunk_count": len(chunks),
        "selected_by_modality": by_modality,
        "already_embedded_count": len(chunks) - len(pending),
        "pending_count": len(pending),
        "batch_size": args.batch_size,
        "dry_run": args.dry_run,
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    if args.dry_run or not pending:
        return

    api_key = (
        os.getenv(args.api_key_env)
        or os.getenv("ARK_EMBEDDING_API_KEY")
        or os.getenv("ARK_API_KEY")
        or os.getenv("DOUBAO_API_KEY")
    )
    if not api_key:
        raise RuntimeError(f"missing API key: set {args.api_key_env}")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("a", encoding="utf-8", newline="\n") as output_file:
        embedded = 0
        for batch in batched(pending, args.batch_size):
            input_items = [build_input_item(chunk, dataset_root) for chunk in batch]
            embeddings = embed_with_retries(
                args.api_url,
                api_key,
                args.model,
                input_items,
                args.timeout_seconds,
                args.max_retries,
                args.retry_sleep_seconds,
            )
            for chunk, embedding in zip(batch, embeddings):
                output_file.write(json.dumps(build_embedding_row(chunk, args.model, embedding), ensure_ascii=False, separators=(",", ":")))
                output_file.write("\n")
            output_file.flush()
            embedded += len(batch)
            print(f"embedded {embedded}/{len(pending)}")


if __name__ == "__main__":
    main()
