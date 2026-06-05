#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path

from product_chunking import build_all_chunks, summarize, write_jsonl


def main() -> None:
    parser = argparse.ArgumentParser(description="Build parent-child chunks for ecommerce_agent_dataset.")
    parser.add_argument("--dataset-root", default="../../../ecommerce_agent_dataset")
    parser.add_argument(
        "--output",
        default="../../src/main/resources/db/ecommerce_offline/ecom-product-chunks.jsonl",
        help="JSONL output path. Defaults to src/main/resources/db/ecommerce_offline/ecom-product-chunks.jsonl",
    )
    parser.add_argument(
        "--summary-output",
        default="../../src/main/resources/db/ecommerce_offline/ecom-product-chunks-summary.json",
        help="Summary JSON output path.",
    )
    args = parser.parse_args()

    script_dir = Path(__file__).resolve().parent
    dataset_root = (script_dir / args.dataset_root).resolve()
    output = (script_dir / args.output).resolve()
    summary_output = (script_dir / args.summary_output).resolve()

    chunks = build_all_chunks(dataset_root)
    summary = summarize(chunks)
    summary["dataset_root"] = str(dataset_root)
    summary["output"] = str(output)

    write_jsonl(chunks, output)
    summary_output.parent.mkdir(parents=True, exist_ok=True)
    summary_output.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
