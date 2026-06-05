from __future__ import annotations

import base64
import json
import mimetypes
import struct
import time
import urllib.error
import urllib.request
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

from .product_vector_recall import (
    EcommerceRecallRuntime,
    hydrate_hit_refs,
    load_jsonl,
    recall_by_embedding,
    required_path,
)


DEFAULT_API_URL = "https://ark.cn-beijing.volces.com/api/v3/embeddings/multimodal"
DEFAULT_MODEL = "doubao-embedding-vision-251215"
MAX_IMAGE_BYTES = 5 * 1024 * 1024
RRF_K = 60


@dataclass(frozen=True)
class ImageQuality:
    width: int | None
    height: int | None
    size_bytes: int
    content_type: str
    warnings: list[str]


@dataclass(frozen=True)
class ProcessedImageRef:
    image_ref: str
    resolved_ref: str
    content_type: str
    size_bytes: int
    width: int | None
    height: int | None
    data_url: str
    warnings: list[str]

    def public_view(self) -> dict[str, Any]:
        payload = asdict(self)
        payload.pop("data_url", None)
        return payload


def preprocess_image_ref(
    image_ref: str,
    runtime: EcommerceRecallRuntime | None = None,
    max_bytes: int = MAX_IMAGE_BYTES,
) -> ProcessedImageRef:
    runtime = runtime or EcommerceRecallRuntime.defaults()
    if not image_ref or not image_ref.strip():
        raise ValueError("imageRef is required")
    image_ref = image_ref.strip()
    if image_ref.startswith("data:"):
        content_type, raw = decode_data_url(image_ref)
        resolved_ref = "data-url"
    elif image_ref.startswith("http://") or image_ref.startswith("https://"):
        raise ValueError("remote imageRef is not supported by the local FAISS runtime; use a local path or data URL")
    else:
        path = resolve_local_image_ref(image_ref, runtime)
        raw = path.read_bytes()
        content_type = sniff_content_type(raw, path)
        resolved_ref = str(path)

    if len(raw) > max_bytes:
        raise ValueError(f"imageRef exceeds max size: {len(raw)} bytes > {max_bytes} bytes")
    if not raw:
        raise ValueError("imageRef is empty")

    width, height = image_dimensions(raw, content_type)
    warnings = image_quality_warnings(width, height, len(raw))
    data_url = "data:" + content_type + ";base64," + base64.b64encode(raw).decode("ascii")
    return ProcessedImageRef(
        image_ref=image_ref,
        resolved_ref=resolved_ref,
        content_type=content_type,
        size_bytes=len(raw),
        width=width,
        height=height,
        data_url=data_url,
        warnings=warnings,
    )


def embed_image_with_doubao(
    image_ref: str,
    api_key: str,
    api_url: str = DEFAULT_API_URL,
    model: str = DEFAULT_MODEL,
    runtime: EcommerceRecallRuntime | None = None,
    timeout_seconds: int = 90,
    max_retries: int = 3,
    retry_sleep_seconds: float = 2.0,
) -> tuple[list[float], ProcessedImageRef]:
    processed = preprocess_image_ref(image_ref, runtime)
    input_items = [
        {
            "type": "image_url",
            "image_url": {
                "url": processed.data_url,
            },
        }
    ]
    embedding = call_doubao_multimodal_embedding(
        api_url=api_url,
        api_key=api_key,
        model=model,
        input_items=input_items,
        timeout_seconds=timeout_seconds,
        max_retries=max_retries,
        retry_sleep_seconds=retry_sleep_seconds,
    )
    return embedding, processed


def embed_text_with_doubao(
    text: str,
    api_key: str,
    api_url: str = DEFAULT_API_URL,
    model: str = DEFAULT_MODEL,
    timeout_seconds: int = 60,
    max_retries: int = 3,
    retry_sleep_seconds: float = 2.0,
) -> list[float]:
    if not text or not text.strip():
        raise ValueError("text query is required")
    return call_doubao_multimodal_embedding(
        api_url=api_url,
        api_key=api_key,
        model=model,
        input_items=[{"type": "text", "text": " ".join(text.split())}],
        timeout_seconds=timeout_seconds,
        max_retries=max_retries,
        retry_sleep_seconds=retry_sleep_seconds,
    )


def photo_search_by_embedding(
    image_embedding: list[float],
    top_k: int = 5,
    text_embedding: list[float] | None = None,
    runtime: EcommerceRecallRuntime | None = None,
    external_refs: set[str] | None = None,
    product_ids: set[str] | None = None,
    catalog_spu_ids: set[int] | None = None,
    preview_chars: int = 240,
    image_weight: float = 1.2,
    text_weight: float = 1.0,
    candidate_multiplier: int = 10,
) -> dict[str, Any]:
    runtime = runtime or EcommerceRecallRuntime.defaults()
    candidate_k = max(top_k * candidate_multiplier, top_k)
    image_recall = recall_by_embedding(
        query_embedding=image_embedding,
        top_k=candidate_k,
        hydrate=False,
        runtime=runtime,
        modalities={"image"},
        chunk_types={"image_embedding"},
        external_refs=external_refs,
        product_ids=product_ids,
        catalog_spu_ids=catalog_spu_ids,
        preview_chars=preview_chars,
    )
    text_recall = None
    if text_embedding is not None:
        text_recall = recall_by_embedding(
            query_embedding=text_embedding,
            top_k=candidate_k,
            hydrate=False,
            runtime=runtime,
            modalities={"text"},
            chunk_types={"product_profile"},
            external_refs=external_refs,
            product_ids=product_ids,
            catalog_spu_ids=catalog_spu_ids,
            preview_chars=preview_chars,
        )

    fused_refs = fuse_hits_by_product(
        image_hits=image_recall["results"],
        text_hits=[] if text_recall is None else text_recall["results"],
        top_k=top_k,
        image_weight=image_weight,
        text_weight=text_weight,
    )
    cards = hydrate_hit_refs(
        fused_refs,
        runtime,
        preview_chars,
        chunks_by_id={row["chunk_id"]: row for row in load_jsonl(required_path(runtime.chunks_path, "chunks_path"))},
    )
    return {
        "topK": top_k,
        "resultCount": len(fused_refs),
        "fusion": {
            "strategy": "weighted_rrf",
            "rrfK": RRF_K,
            "imageWeight": image_weight,
            "textWeight": text_weight if text_embedding is not None else 0.0,
            "imageHitCount": len(image_recall["results"]),
            "textHitCount": 0 if text_recall is None else len(text_recall["results"]),
        },
        "results": fused_refs,
        "cards": cards,
    }


def photo_search_by_image_ref(
    image_ref: str,
    api_key: str,
    top_k: int = 5,
    query_text: str | None = None,
    api_url: str = DEFAULT_API_URL,
    model: str = DEFAULT_MODEL,
    runtime: EcommerceRecallRuntime | None = None,
    timeout_seconds: int = 90,
    max_retries: int = 3,
    retry_sleep_seconds: float = 2.0,
    preview_chars: int = 240,
) -> dict[str, Any]:
    image_embedding, processed = embed_image_with_doubao(
        image_ref=image_ref,
        api_key=api_key,
        api_url=api_url,
        model=model,
        runtime=runtime,
        timeout_seconds=timeout_seconds,
        max_retries=max_retries,
        retry_sleep_seconds=retry_sleep_seconds,
    )
    text_embedding = None
    if query_text and query_text.strip():
        text_embedding = embed_text_with_doubao(
            text=query_text,
            api_key=api_key,
            api_url=api_url,
            model=model,
            timeout_seconds=timeout_seconds,
            max_retries=max_retries,
            retry_sleep_seconds=retry_sleep_seconds,
        )
    result = photo_search_by_embedding(
        image_embedding=image_embedding,
        text_embedding=text_embedding,
        top_k=top_k,
        runtime=runtime,
        preview_chars=preview_chars,
    )
    result["image"] = processed.public_view()
    result["model"] = model
    result["visionSignals"] = vision_signals(result, processed)
    return result


def fuse_hits_by_product(
    image_hits: list[dict[str, Any]],
    text_hits: list[dict[str, Any]],
    top_k: int,
    image_weight: float,
    text_weight: float,
) -> list[dict[str, Any]]:
    by_product: dict[str, dict[str, Any]] = {}
    add_ranked_hits(by_product, image_hits, "image", image_weight)
    add_ranked_hits(by_product, text_hits, "text", text_weight)
    ranked = sorted(
        by_product.values(),
        key=lambda row: (-float(row["fusion_score"]), str(row["product_id"])),
    )
    return ranked[:top_k]


def add_ranked_hits(
    by_product: dict[str, dict[str, Any]],
    hits: list[dict[str, Any]],
    channel: str,
    weight: float,
) -> None:
    for rank, hit in enumerate(hits, start=1):
        product_id = hit.get("product_id")
        if product_id is None:
            continue
        contribution = weight / (RRF_K + rank)
        item = by_product.setdefault(
            str(product_id),
            {
                **hit,
                "fusion_score": 0.0,
                "fusion_channels": [],
                "channel_scores": {},
            },
        )
        item["fusion_score"] = float(item["fusion_score"]) + contribution
        item["channel_scores"][channel] = {
            "rank": rank,
            "score": hit.get("score"),
            "rrfContribution": contribution,
            "chunkId": hit.get("chunk_id"),
            "chunkType": hit.get("chunk_type"),
            "embeddingModality": hit.get("embedding_modality"),
        }
        if channel not in item["fusion_channels"]:
            item["fusion_channels"].append(channel)
        if channel == "image" or item.get("embedding_modality") != "image":
            item.update(hit)
        item["score"] = item["fusion_score"]


def vision_signals(result: dict[str, Any], image: ProcessedImageRef) -> dict[str, Any]:
    best_score = 0.0
    if result.get("results"):
        best_score = float(result["results"][0].get("channel_scores", {}).get("image", {}).get("score") or 0.0)
    confidence = "HIGH" if best_score >= 0.55 else "MEDIUM" if best_score >= 0.4 else "LOW"
    warnings = list(image.warnings)
    if confidence == "LOW":
        warnings.append("LOW_VISUAL_CONFIDENCE")
    return {
        "bestVisualScore": best_score,
        "confidence": confidence,
        "matchType": "SIMILAR_STYLE",
        "warnings": warnings,
    }


def call_doubao_multimodal_embedding(
    api_url: str,
    api_key: str,
    model: str,
    input_items: list[dict[str, Any]],
    timeout_seconds: int,
    max_retries: int,
    retry_sleep_seconds: float,
) -> list[float]:
    last_error: Exception | None = None
    for attempt in range(max_retries + 1):
        try:
            return call_doubao_once(api_url, api_key, model, input_items, timeout_seconds)
        except Exception as error:
            last_error = error
            if attempt >= max_retries:
                break
            time.sleep(retry_sleep_seconds * (2 ** attempt))
    raise RuntimeError(f"Doubao embedding failed after {max_retries + 1} attempts: {last_error}") from last_error


def call_doubao_once(
    api_url: str,
    api_key: str,
    model: str,
    input_items: list[dict[str, Any]],
    timeout_seconds: int,
) -> list[float]:
    payload = json.dumps({"model": model, "input": input_items}, ensure_ascii=False).encode("utf-8")
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


def resolve_local_image_ref(image_ref: str, runtime: EcommerceRecallRuntime) -> Path:
    path = Path(image_ref)
    candidates: list[Path] = []
    if path.is_absolute():
        candidates.append(path)
    else:
        dataset_root = required_path(runtime.dataset_root, "dataset_root")
        resources_root = required_path(runtime.resources_root, "resources_root")
        candidates.extend([
            (Path.cwd() / path).resolve(),
            (dataset_root / path).resolve(),
            (resources_root / path).resolve(),
        ])
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise FileNotFoundError(f"imageRef does not resolve to a local file: {image_ref}")


def sniff_content_type(raw: bytes, path: Path | None = None) -> str:
    if raw.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if raw.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if raw.startswith(b"RIFF") and raw[8:12] == b"WEBP":
        return "image/webp"
    guessed = mimetypes.guess_type(path.name if path else "")[0]
    if guessed in {"image/jpeg", "image/png", "image/webp"}:
        return guessed
    raise ValueError("unsupported image type; only jpeg/png/webp are accepted")


def decode_data_url(value: str) -> tuple[str, bytes]:
    header, _, payload = value.partition(",")
    if not payload or ";base64" not in header:
        raise ValueError("image data URL must be base64 encoded")
    content_type = header.removeprefix("data:").split(";")[0]
    if content_type not in {"image/jpeg", "image/png", "image/webp"}:
        raise ValueError("unsupported image data URL content type")
    return content_type, base64.b64decode(payload)


def image_dimensions(raw: bytes, content_type: str) -> tuple[int | None, int | None]:
    try:
        if content_type == "image/png" and len(raw) >= 24:
            return struct.unpack(">II", raw[16:24])
        if content_type == "image/jpeg":
            return jpeg_dimensions(raw)
        if content_type == "image/webp":
            return webp_dimensions(raw)
    except Exception:
        return None, None
    return None, None


def jpeg_dimensions(raw: bytes) -> tuple[int | None, int | None]:
    index = 2
    while index + 9 < len(raw):
        if raw[index] != 0xFF:
            index += 1
            continue
        marker = raw[index + 1]
        index += 2
        if marker in {0xD8, 0xD9}:
            continue
        if index + 2 > len(raw):
            break
        segment_length = struct.unpack(">H", raw[index:index + 2])[0]
        if marker in range(0xC0, 0xC4):
            height = struct.unpack(">H", raw[index + 3:index + 5])[0]
            width = struct.unpack(">H", raw[index + 5:index + 7])[0]
            return width, height
        index += segment_length
    return None, None


def webp_dimensions(raw: bytes) -> tuple[int | None, int | None]:
    if len(raw) < 30 or not (raw.startswith(b"RIFF") and raw[8:12] == b"WEBP"):
        return None, None
    chunk = raw[12:16]
    if chunk == b"VP8X" and len(raw) >= 30:
        width = 1 + int.from_bytes(raw[24:27], "little")
        height = 1 + int.from_bytes(raw[27:30], "little")
        return width, height
    return None, None


def image_quality_warnings(width: int | None, height: int | None, size_bytes: int) -> list[str]:
    warnings: list[str] = []
    if width is None or height is None:
        warnings.append("IMAGE_DIMENSION_UNKNOWN")
        return warnings
    if max(width, height) > 1024:
        warnings.append("IMAGE_LONG_EDGE_GT_1024")
    if min(width, height) < 160:
        warnings.append("IMAGE_TOO_SMALL")
    if size_bytes < 8 * 1024:
        warnings.append("IMAGE_FILE_VERY_SMALL")
    ratio = max(width, height) / max(min(width, height), 1)
    if ratio > 3.0:
        warnings.append("IMAGE_EXTREME_ASPECT_RATIO")
    return warnings
