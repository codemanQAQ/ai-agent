from __future__ import annotations

import argparse
import base64
import concurrent.futures
import json
import mimetypes
import os
import struct
import time
import urllib.error
import urllib.request
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any


# caption 走 chat/completions；调用方直接 POST 到该 URL（不再额外拼接 path），故必须是完整端点。
DEFAULT_CHAT_API_URL = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
DEFAULT_CAPTION_MODEL = "ep-20260514111645-lmgt2"
DEFAULT_EMBEDDING_API_URL = "https://ark.cn-beijing.volces.com/api/v3/embeddings/multimodal"
DEFAULT_EMBEDDING_MODEL = "doubao-embedding-vision-251215"
MAX_IMAGE_BYTES = 5 * 1024 * 1024
DEFAULT_CAPTION_PROMPT = (
    "你是电商导购图片理解助手。请用中文简洁描述图片中的商品主体，"
    "重点提取品类、颜色、材质、外观风格、可见品牌或文字、使用场景。"
    "不要编造无法从图片确认的信息，输出一段 80 字以内的 caption。"
)


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


@dataclass(frozen=True)
class ImageInputResult:
    image: dict[str, Any]
    caption: str
    image_embedding: list[float] | None
    caption_model: str
    embedding_model: str | None
    caption_error: str | None
    embedding_error: str | None
    elapsed_ms: float

    def public_view(self, include_embedding: bool = False) -> dict[str, Any]:
        payload = asdict(self)
        if not include_embedding:
            embedding = payload.pop("image_embedding", None)
            payload["imageEmbeddingDimension"] = None if embedding is None else len(embedding)
        return payload


def process_image_input(
    image_ref: str,
    caption_api_key: str | None = None,
    embedding_api_key: str | None = None,
    generate_caption: bool = True,
    generate_embedding: bool = True,
    caption_api_url: str = DEFAULT_CHAT_API_URL,
    caption_model: str = DEFAULT_CAPTION_MODEL,
    caption_prompt: str = DEFAULT_CAPTION_PROMPT,
    embedding_api_url: str = DEFAULT_EMBEDDING_API_URL,
    embedding_model: str = DEFAULT_EMBEDDING_MODEL,
    timeout_seconds: int = 90,
    max_retries: int = 3,
    retry_sleep_seconds: float = 2.0,
) -> ImageInputResult:
    started = time.perf_counter()
    caption_api_key = caption_api_key or (required_caption_api_key() if generate_caption else None)
    embedding_api_key = embedding_api_key or (required_embedding_api_key() if generate_embedding else None)
    processed = preprocess_image_ref(image_ref)

    image_embedding = None
    embedding_error = None
    caption = ""
    caption_error = None

    def _run_embedding() -> list[float]:
        return call_doubao_multimodal_embedding(
            api_url=embedding_api_url,
            api_key=embedding_api_key or "",
            model=embedding_model,
            input_items=[{"type": "image_url", "image_url": {"url": processed.data_url}}],
            timeout_seconds=timeout_seconds,
            max_retries=max_retries,
            retry_sleep_seconds=retry_sleep_seconds,
        )

    def _run_caption() -> str:
        return caption_image_with_doubao(
            processed=processed,
            api_key=caption_api_key or "",
            api_url=caption_api_url,
            model=caption_model,
            prompt=caption_prompt,
            timeout_seconds=timeout_seconds,
            max_retries=max_retries,
            retry_sleep_seconds=retry_sleep_seconds,
        )

    # caption 与 embedding 是两个独立的 Doubao HTTP 调用，并行执行以把图像处理耗时从
    # (caption + embedding) 降到 max(caption, embedding)。两者都是 I/O 阻塞，线程即可并行。
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        embedding_future = pool.submit(_run_embedding) if generate_embedding else None
        caption_future = pool.submit(_run_caption) if generate_caption else None
        if embedding_future is not None:
            try:
                image_embedding = embedding_future.result()
            except Exception as exception:
                embedding_error = str(exception)
        if caption_future is not None:
            try:
                caption = caption_future.result()
            except Exception as exception:
                caption_error = str(exception)

    # 失败语义与原串行实现一致：只有当 caption 和 embedding 都不可用时才硬失败。
    if generate_caption and caption_error and not image_embedding:
        raise RuntimeError(caption_error)
    if generate_embedding and image_embedding is None and not caption:
        raise RuntimeError(embedding_error or "Doubao image embedding failed")

    return ImageInputResult(
        image=processed.public_view(),
        caption=caption,
        image_embedding=image_embedding,
        caption_model=caption_model,
        embedding_model=embedding_model if generate_embedding else None,
        caption_error=caption_error,
        embedding_error=embedding_error,
        elapsed_ms=round((time.perf_counter() - started) * 1000, 3),
    )


def caption_image_with_doubao(
    processed: ProcessedImageRef,
    api_key: str,
    api_url: str = DEFAULT_CHAT_API_URL,
    model: str = DEFAULT_CAPTION_MODEL,
    prompt: str = DEFAULT_CAPTION_PROMPT,
    timeout_seconds: int = 90,
    max_retries: int = 3,
    retry_sleep_seconds: float = 2.0,
) -> str:
    last_error: Exception | None = None
    for attempt in range(max_retries + 1):
        try:
            return call_doubao_caption_once(processed, api_key, api_url, model, prompt, timeout_seconds)
        except Exception as error:
            last_error = error
            if attempt >= max_retries:
                break
            time.sleep(retry_sleep_seconds * (2 ** attempt))
    raise RuntimeError(f"Doubao image caption failed after {max_retries + 1} attempts: {last_error}") from last_error


def call_doubao_caption_once(
    processed: ProcessedImageRef,
    api_key: str,
    api_url: str,
    model: str,
    prompt: str,
    timeout_seconds: int,
) -> str:
    payload = {
        "model": model,
        "messages": [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": prompt},
                    {"type": "image_url", "image_url": {"url": processed.data_url}},
                ],
            }
        ],
        "temperature": 0.1,
        "max_tokens": 256,
        # 关闭思考链：caption 模型默认会先思考再作答，实测耗时 ~13s；关闭后 ~2.5s。
        "thinking": {"type": "disabled"},
    }
    request = urllib.request.Request(
        api_url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
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
        raise RuntimeError(f"Doubao caption HTTP {error.code}: {detail}") from error
    except urllib.error.URLError as error:
        raise RuntimeError(f"Doubao caption request failed: {error}") from error

    data = json.loads(body)
    choices = data.get("choices")
    if isinstance(choices, list) and choices:
        message = choices[0].get("message") if isinstance(choices[0], dict) else None
        content = message.get("content") if isinstance(message, dict) else None
        if isinstance(content, str) and content.strip():
            return " ".join(content.split())
    raise RuntimeError(f"unexpected Doubao caption response shape: keys={list(data.keys())}")


def required_caption_api_key() -> str:
    api_key = os.getenv("DOUBAO_CAPTION_API_KEY")
    if not api_key:
        raise ValueError("DOUBAO_CAPTION_API_KEY is required for image caption")
    return api_key


def required_embedding_api_key() -> str:
    api_key = (
        os.getenv("DOUBAO_EMBEDDING_API_KEY")
    )
    if not api_key:
        raise ValueError("DOUBAO_EMBEDDING_API_KEY is required for image embedding")
    return api_key


def preprocess_image_ref(
    image_ref: str,
    max_bytes: int = MAX_IMAGE_BYTES,
) -> ProcessedImageRef:
    if not image_ref or not image_ref.strip():
        raise ValueError("imageRef is required")
    image_ref = image_ref.strip()
    if image_ref.startswith("data:"):
        content_type, raw = decode_data_url(image_ref)
        resolved_ref = "data-url"
    elif image_ref.startswith("http://") or image_ref.startswith("https://"):
        raise ValueError("remote imageRef is not supported; use a local path or data URL")
    else:
        path = resolve_local_image_ref(image_ref)
        raw = path.read_bytes()
        content_type = sniff_content_type(raw, path)
        resolved_ref = str(path)

    if len(raw) > max_bytes:
        raise ValueError(f"imageRef exceeds max size: {len(raw)} bytes > {max_bytes} bytes")
    if not raw:
        raise ValueError("imageRef is empty")

    width, height = image_dimensions(raw, content_type)
    data_url = "data:" + content_type + ";base64," + base64.b64encode(raw).decode("ascii")
    return ProcessedImageRef(
        image_ref=image_ref,
        resolved_ref=resolved_ref,
        content_type=content_type,
        size_bytes=len(raw),
        width=width,
        height=height,
        data_url=data_url,
        warnings=image_quality_warnings(width, height, len(raw)),
    )


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
            return call_doubao_embedding_once(api_url, api_key, model, input_items, timeout_seconds)
        except Exception as error:
            last_error = error
            if attempt >= max_retries:
                break
            time.sleep(retry_sleep_seconds * (2 ** attempt))
    raise RuntimeError(f"Doubao embedding failed after {max_retries + 1} attempts: {last_error}") from last_error


def call_doubao_embedding_once(
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
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
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


def resolve_local_image_ref(image_ref: str) -> Path:
    path = Path(image_ref)
    candidates: list[Path] = []
    if path.is_absolute():
        candidates.append(path)
    else:
        project_root = Path(__file__).resolve().parents[4]
        candidates.extend([
            (Path.cwd() / path).resolve(),
            (project_root.parent / "ecommerce_agent_dataset" / path).resolve(),
            (project_root / "src" / "main" / "resources" / path).resolve(),
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


def write_result(result: ImageInputResult, output: str | None, include_embedding: bool) -> None:
    payload = json.dumps(result.public_view(include_embedding=include_embedding), ensure_ascii=False, indent=2)
    if output:
        with open(output, "w", encoding="utf-8") as file:
            file.write(payload + "\n")
        print(json.dumps({"output": output}, ensure_ascii=False))
    else:
        print(payload)


def main() -> None:
    parser = argparse.ArgumentParser(description="Process ecommerce image input with Doubao caption and embedding.")
    parser.add_argument("--image-ref", required=True, help="Local image path, dataset-relative path, or data URL.")
    parser.add_argument("--caption-only", action="store_true")
    parser.add_argument("--embedding-only", action="store_true")
    parser.add_argument("--include-embedding", action="store_true")
    parser.add_argument("--caption-api-url", default=os.getenv("DOUBAO_CHAT_API_URL", DEFAULT_CHAT_API_URL))
    parser.add_argument("--caption-model", default=os.getenv("DOUBAO_CAPTION_MODEL", DEFAULT_CAPTION_MODEL))
    parser.add_argument("--embedding-api-url", default=os.getenv("DOUBAO_MULTIMODAL_EMBEDDING_API_URL", DEFAULT_EMBEDDING_API_URL))
    parser.add_argument("--embedding-model", default=os.getenv("DOUBAO_EMBEDDING_MODEL", DEFAULT_EMBEDDING_MODEL))
    parser.add_argument("--caption-api-key", default=os.getenv("DOUBAO_CAPTION_API_KEY"))
    parser.add_argument(
        "--embedding-api-key",
        default=(
            os.getenv("DOUBAO_EMBEDDING_API_KEY")
        ),
    )
    parser.add_argument("--output")
    args = parser.parse_args()

    result = process_image_input(
        image_ref=args.image_ref,
        caption_api_key=args.caption_api_key,
        embedding_api_key=args.embedding_api_key,
        generate_caption=not args.embedding_only,
        generate_embedding=not args.caption_only,
        caption_api_url=args.caption_api_url,
        caption_model=args.caption_model,
        embedding_api_url=args.embedding_api_url,
        embedding_model=args.embedding_model,
    )
    write_result(result, args.output, args.include_embedding)


if __name__ == "__main__":
    main()
