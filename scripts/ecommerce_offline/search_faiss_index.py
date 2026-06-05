#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path

import faiss
import numpy as np

from hydrate_product_card import chunk_index, load_jsonl, load_products, resolve_hit


def load_id_map(path: Path) -> dict[int, dict]:
    rows = json.loads(path.read_text(encoding="utf-8"))
    return {int(row["faiss_id"]): row for row in rows}


def main() -> None:
    parser = argparse.ArgumentParser(description="Inspect FAISS recall result by FAISS id.")
    parser.add_argument("--index", default="../../src/main/resources/db/ecommerce_offline/faiss/ecom-product.index")
    parser.add_argument("--id-map", default="../../src/main/resources/db/ecommerce_offline/faiss/ecom-product-id-map.json")
    parser.add_argument("--chunks", default="../../src/main/resources/db/ecommerce_offline/ecom-product-chunks.jsonl")
    parser.add_argument("--dataset-root", default="../../../ecommerce_agent_dataset")
    parser.add_argument("--embedding-json", help="JSON array query embedding, already produced by the same model.")
    parser.add_argument("--embedding-json-file", help="File containing a JSON array query embedding.")
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--hydrate", action="store_true", help="Hydrate child hits to parent/SPU product cards.")
    parser.add_argument("--preview-chars", type=int, default=240)
    parser.add_argument("--output")
    args = parser.parse_args()
    if not args.embedding_json and not args.embedding_json_file:
        raise ValueError("one of --embedding-json or --embedding-json-file is required")

    script_dir = Path(__file__).resolve().parent
    index = faiss.read_index(str((script_dir / args.index).resolve()))
    id_map = load_id_map((script_dir / args.id_map).resolve())
    embedding_json = args.embedding_json
    if args.embedding_json_file:
        embedding_json = Path(args.embedding_json_file).read_text(encoding="utf-8-sig")
    query = np.array([json.loads(embedding_json)], dtype="float32")
    faiss.normalize_L2(query)
    scores, ids = index.search(query, args.top_k)

    results = []
    for score, faiss_id in zip(scores[0], ids[0]):
        if faiss_id < 0:
            continue
        mapped = id_map[int(faiss_id)]
        results.append({"score": float(score), **mapped})
    if not args.hydrate:
        write_result(results, args.output)
        return

    chunks_by_id = chunk_index(load_jsonl((script_dir / args.chunks).resolve()))
    products_by_id = load_products((script_dir / args.dataset_root).resolve())
    cards = [
        resolve_hit(result, chunks_by_id, products_by_id, args.preview_chars)
        for result in results
    ]
    write_result({
        "results": results,
        "cards": cards,
    }, args.output)


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


if __name__ == "__main__":
    main()
