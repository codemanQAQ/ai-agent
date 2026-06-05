#!/usr/bin/env python3
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


DEFAULT_API_URL = "https://ark.cn-beijing.volces.com/api/v3/embeddings/multimodal"
DEFAULT_MODEL = "doubao-embedding-vision-251215"


def load_text_chunks(path: Path) -> list[dict[str, Any]]:
    chunks: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as file:
        for line_no, line in enumerate(file, start=1):
            if not line.strip():
                continue
            row = json.loads(line)
            if row.get("embedding_required") is True and row.get("embedding_modality") == "text":
                text = row.get("text_content")
                if not isinstance(text, str) or not text.strip():
                    raise ValueError(f"text chunk has empty text_content at line {line_no}: {row.get('chunk_id')}")
                chunks.append(row)
    return chunks


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
                completed.add((row["chunk_id"], row["content_sha256"], row["model"]))
    return completed


def batched(items: list[dict[str, Any]], batch_size: int) -> list[list[dict[str, Any]]]:
    return [items[index:index + batch_size] for index in range(0, len(items), batch_size)]


def call_doubao_embeddings(
    api_url: str,
    api_key: str,
    model: str,
    texts: list[str],
    timeout_seconds: int,
) -> list[list[float]]:
    payload = json.dumps(
        {
            "model": model,
            "input": [{"type": "text", "text": text} for text in texts],
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
        if len(texts) != 1:
            raise RuntimeError("Doubao multimodal embedding API returns one embedding for one multimodal input; use batch-size=1")
        return [payload_data["embedding"]]

    embeddings_by_index: dict[int, list[float]] = {}
    for item in payload_data or []:
        embeddings_by_index[int(item["index"])] = item["embedding"]
    embeddings = [embeddings_by_index[index] for index in range(len(texts))]
    if len(embeddings) != len(texts):
        raise RuntimeError(f"Doubao returned {len(embeddings)} embeddings for {len(texts)} inputs")
    return embeddings


def embed_with_retries(
    api_url: str,
    api_key: str,
    model: str,
    texts: list[str],
    timeout_seconds: int,
    max_retries: int,
    retry_sleep_seconds: float,
) -> list[list[float]]:
    last_error: Exception | None = None
    for attempt in range(max_retries + 1):
        try:
            return call_doubao_embeddings(api_url, api_key, model, texts, timeout_seconds)
        except Exception as error:
            last_error = error
            if attempt >= max_retries:
                break
            sleep = retry_sleep_seconds * (2 ** attempt)
            print(f"embedding batch failed, retrying in {sleep:.1f}s: {error}", file=sys.stderr)
            time.sleep(sleep)
    raise RuntimeError(f"embedding batch failed after {max_retries + 1} attempts: {last_error}") from last_error


def build_embedding_row(chunk: dict[str, Any], model: str, embedding: list[float]) -> dict[str, Any]:
    return {
        "chunk_id": chunk["chunk_id"],
        "product_id": chunk["product_id"],
        "parent_chunk_id": chunk.get("parent_chunk_id"),
        "chunk_type": chunk["chunk_type"],
        "chunk_index": chunk["chunk_index"],
        "content_sha256": chunk["content_sha256"],
        "model": model,
        "dimension": len(embedding),
        "vector_id": f"ecom-text:{model}:{chunk['chunk_id']}",
        "embedding": embedding,
        "metadata": chunk.get("metadata") or {},
        "source_ref": chunk.get("source_ref") or {},
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Embed ecommerce text chunks with Doubao multimodal embeddings API.")
    parser.add_argument(
        "--input",
        default="../../src/main/resources/db/ecommerce_offline/ecom-product-chunks.jsonl",
        help="Input chunk JSONL path.",
    )
    parser.add_argument(
        "--output",
        default="../../src/main/resources/db/ecommerce_offline/ecom-product-text-embeddings.jsonl",
        help="Output embedding JSONL path.",
    )
    parser.add_argument("--api-url", default=os.getenv("DOUBAO_MULTIMODAL_EMBEDDING_API_URL", DEFAULT_API_URL))
    parser.add_argument("--model", default=os.getenv("DOUBAO_EMBEDDING_MODEL", DEFAULT_MODEL))
    parser.add_argument("--api-key-env", default="DOUBAO_EMBEDDING_API_KEY")
    parser.add_argument("--batch-size", type=int, default=1)
    parser.add_argument("--limit", type=int, default=0, help="Limit chunks for smoke testing. 0 means no limit.")
    parser.add_argument("--timeout-seconds", type=int, default=60)
    parser.add_argument("--max-retries", type=int, default=3)
    parser.add_argument("--retry-sleep-seconds", type=float, default=2.0)
    parser.add_argument("--dry-run", action="store_true", help="Load and count chunks without calling API.")
    args = parser.parse_args()

    if args.batch_size <= 0:
        raise ValueError("--batch-size must be positive")
    if args.batch_size != 1:
        raise ValueError("Doubao multimodal embedding returns one vector per multimodal input; keep --batch-size 1")

    script_dir = Path(__file__).resolve().parent
    input_path = (script_dir / args.input).resolve()
    output_path = (script_dir / args.output).resolve()
    chunks = load_text_chunks(input_path)
    if args.limit > 0:
        chunks = chunks[:args.limit]

    completed = load_completed(output_path, args.model)
    pending = [
        chunk for chunk in chunks
        if (chunk["chunk_id"], chunk["content_sha256"], args.model) not in completed
    ]

    summary = {
        "input": str(input_path),
        "output": str(output_path),
        "model": args.model,
        "api_url": args.api_url,
        "text_chunk_count": len(chunks),
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
            texts = [chunk["text_content"] for chunk in batch]
            embeddings = embed_with_retries(
                args.api_url,
                api_key,
                args.model,
                texts,
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
