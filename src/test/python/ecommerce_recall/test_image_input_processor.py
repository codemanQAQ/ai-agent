#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[4]
MAIN_PYTHON = PROJECT_ROOT / "src" / "main" / "python" / "ecommerce_recall"
sys.path.insert(0, str(MAIN_PYTHON))

from image_input_processor import (  # noqa: E402
    caption_image_with_doubao,
    call_doubao_multimodal_embedding,
    preprocess_image_ref,
    required_caption_api_key,
    required_embedding_api_key,
)


def main() -> None:
    parser = argparse.ArgumentParser(description="Test process_image_input with caption and image embedding.")
    parser.add_argument("--image-ref", required=True, help="Local image path, dataset-relative path, or data URL.")
    parser.add_argument("--include-embedding", action="store_true")
    parser.add_argument("--caption-api-key")
    parser.add_argument("--embedding-api-key")
    args = parser.parse_args()

    print("[1/3] preprocess image", flush=True)
    processed = preprocess_image_ref(args.image_ref)

    print("[2/3] generate caption model=ep-20260514111645-lmgt2", flush=True)
    caption = caption_image_with_doubao(
        processed=processed,
        api_key=args.caption_api_key or required_caption_api_key(),
    )

    print("[3/3] generate image embedding", flush=True)
    embedding = call_doubao_multimodal_embedding(
        api_url="https://ark.cn-beijing.volces.com/api/v3/embeddings/multimodal",
        api_key=args.embedding_api_key or required_embedding_api_key(),
        model="doubao-embedding-vision-251215",
        input_items=[{"type": "image_url", "image_url": {"url": processed.data_url}}],
        timeout_seconds=90,
        max_retries=3,
        retry_sleep_seconds=2.0,
    )

    output = {
        "image": processed.public_view(),
        "caption": caption,
        "captionModel": "ep-20260514111645-lmgt2",
        "embeddingModel": "doubao-embedding-vision-251215",
        "imageEmbeddingDimension": len(embedding),
    }
    if args.include_embedding:
        output["imageEmbedding"] = True if embedding else False
    print(json.dumps(output, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
