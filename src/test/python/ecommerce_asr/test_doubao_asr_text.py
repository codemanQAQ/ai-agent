#!/usr/bin/env python3
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[4]
MAIN_PYTHON = PROJECT_ROOT / "src" / "main" / "python"
sys.path.insert(0, str(MAIN_PYTHON))

from ecommerce_asr import transcribe_audio_text  # noqa: E402


def main() -> None:
    parser = argparse.ArgumentParser(description="Test Doubao ASR text transcription.")
    parser.add_argument("--audio", required=True, help="Local audio file path.")
    args = parser.parse_args()

    started = time.perf_counter()
    text = transcribe_audio_text(args.audio)
    elapsed_ms = (time.perf_counter() - started) * 1000

    print(text)
    print(f"\nelapsedMs={elapsed_ms:.3f}")


if __name__ == "__main__":
    main()
