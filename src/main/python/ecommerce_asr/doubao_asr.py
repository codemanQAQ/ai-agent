#!/usr/bin/env python3
from __future__ import annotations

import argparse
import gzip
import json
import mimetypes
import os
import time
import uuid
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any


DEFAULT_ENDPOINT = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel"
DEFAULT_RESOURCE_ID = "volc.bigasr.sauc.duration"
DEFAULT_AUDIO_FORMAT = "auto"
DEFAULT_SAMPLE_RATE = 16000
DEFAULT_CHUNK_BYTES = 32 * 1024

PROTOCOL_VERSION = 0x1
HEADER_SIZE = 0x1
SERIALIZATION_RAW = 0x0
SERIALIZATION_JSON = 0x1
COMPRESSION_NONE = 0x0
COMPRESSION_GZIP = 0x1

FULL_CLIENT_REQUEST = 0x1
AUDIO_ONLY_REQUEST = 0x2
FULL_SERVER_RESPONSE = 0x9
SERVER_ACK = 0xB
ERROR_INFORMATION = 0xF

FLAG_NO_SEQUENCE = 0x0
FLAG_POS_SEQUENCE = 0x1
FLAG_NEG_SEQUENCE = 0x2
FLAG_NEG_SEQUENCE_1 = 0x3


@dataclass(frozen=True)
class DoubaoAsrConfig:
    endpoint: str = DEFAULT_ENDPOINT
    resource_id: str = DEFAULT_RESOURCE_ID
    app_key: str | None = None
    access_key: str | None = None
    connect_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    request_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    audio_format: str = DEFAULT_AUDIO_FORMAT
    sample_rate: int = DEFAULT_SAMPLE_RATE
    model_name: str = "bigmodel"
    enable_itn: bool = True
    enable_punc: bool = True
    chunk_bytes: int = DEFAULT_CHUNK_BYTES
    timeout_seconds: int = 60

    @classmethod
    def from_env(cls) -> "DoubaoAsrConfig":
        env_file_values = load_project_env_file()
        return cls(
            endpoint=env_value(env_file_values, "DOUBAO_ASR_ENDPOINT") or DEFAULT_ENDPOINT,
            resource_id=env_value(env_file_values, "DOUBAO_ASR_RESOURCE_ID") or DEFAULT_RESOURCE_ID,
            app_key=env_value(env_file_values, "DOUBAO_ASR_APP_KEY", "VOLCENGINE_ASR_APP_KEY"),
            access_key=(
                env_value(
                    env_file_values,
                    "DOUBAO_ASR_ACCESS_KEY",
                    "DOUBAO_ASR_ACCESS_KEY.",
                    "VOLCENGINE_ASR_ACCESS_KEY",
                    "DOUBAO_ASR_ACCESS_TOKEN",
                    "VOLCENGINE_ASR_ACCESS_TOKEN",
                )
            ),
            audio_format=env_value(env_file_values, "DOUBAO_ASR_AUDIO_FORMAT") or DEFAULT_AUDIO_FORMAT,
            sample_rate=int(env_value(env_file_values, "DOUBAO_ASR_SAMPLE_RATE") or str(DEFAULT_SAMPLE_RATE)),
            model_name=env_value(env_file_values, "DOUBAO_ASR_MODEL_NAME") or "bigmodel",
            chunk_bytes=int(env_value(env_file_values, "DOUBAO_ASR_CHUNK_BYTES") or str(DEFAULT_CHUNK_BYTES)),
            timeout_seconds=int(env_value(env_file_values, "DOUBAO_ASR_TIMEOUT_SECONDS") or "60"),
        )

    def headers(self) -> list[str]:
        headers = [
            f"X-Api-Resource-Id: {self.resource_id}",
            f"X-Api-Connect-Id: {self.connect_id}",
            f"X-Api-Request-Id: {self.request_id}",
            "X-Api-Sequence: -1",
        ]
        if self.app_key and self.access_key:
            headers.append(f"X-Api-App-Key: {self.app_key}")
            headers.append(f"X-Api-Access-Key: {self.access_key}")
            return headers
        raise ValueError("Doubao ASR auth is required. Set DOUBAO_ASR_APP_KEY and DOUBAO_ASR_ACCESS_KEY.")


@dataclass(frozen=True)
class DoubaoAsrSegment:
    text: str
    is_final: bool | None = None
    start_time_ms: int | None = None
    end_time_ms: int | None = None
    raw: dict[str, Any] | None = None


@dataclass(frozen=True)
class DoubaoAsrResult:
    text: str
    segments: list[DoubaoAsrSegment]
    elapsed_ms: float
    endpoint: str
    resource_id: str
    request_id: str
    connect_id: str
    audio: dict[str, Any]

    def public_view(self) -> dict[str, Any]:
        return asdict(self)


def transcribe_audio_file(
    audio_path: str | Path,
    config: DoubaoAsrConfig | None = None,
) -> DoubaoAsrResult:
    config = config or DoubaoAsrConfig.from_env()
    path = Path(audio_path)
    if not path.is_file():
        raise FileNotFoundError(f"audio file does not exist: {path}")
    audio_format = normalize_audio_format(config.audio_format, path)
    started = time.perf_counter()
    segments: list[DoubaoAsrSegment] = []

    websocket = import_websocket_client()
    ws = websocket.create_connection(
        config.endpoint,
        header=config.headers(),
        timeout=config.timeout_seconds,
    )
    try:
        request = {
            "user": {"uid": "ecommerce-agent"},
            "audio": {
                "format": audio_format,
                "rate": config.sample_rate,
            },
            "request": {
                "model_name": config.model_name,
                "enable_itn": config.enable_itn,
                "enable_punc": config.enable_punc,
                "result_type": "full",
            },
        }
        ws.send_binary(build_full_client_request(1, request))
        segments.extend(read_available_responses(ws))

        sequence = 2
        with path.open("rb") as file:
            chunk = file.read(config.chunk_bytes)
            if not chunk:
                ws.send_binary(build_audio_request(-sequence, b"", final=True))
                segments.extend(read_available_responses(ws))
            while chunk:
                next_chunk = file.read(config.chunk_bytes)
                final = not next_chunk
                frame_sequence = -sequence if final else sequence
                ws.send_binary(build_audio_request(frame_sequence, chunk, final=final))
                segments.extend(read_available_responses(ws))
                chunk = next_chunk
                sequence += 1
        segments.extend(read_until_final(ws))
    finally:
        ws.close()

    text = choose_final_text(segments)
    if not text:
        raise RuntimeError("Doubao ASR returned no transcript before closing the websocket connection.")
    elapsed_ms = (time.perf_counter() - started) * 1000
    return DoubaoAsrResult(
        text=text,
        segments=segments,
        elapsed_ms=round(elapsed_ms, 3),
        endpoint=config.endpoint,
        resource_id=config.resource_id,
        request_id=config.request_id,
        connect_id=config.connect_id,
        audio={
            "path": str(path.resolve()),
            "format": audio_format,
            "sampleRate": config.sample_rate,
            "sizeBytes": path.stat().st_size,
            "chunkBytes": config.chunk_bytes,
        },
    )


def transcribe_audio_text(
    audio_path: str | Path,
    config: DoubaoAsrConfig | None = None,
) -> str:
    return transcribe_audio_file(audio_path, config).text


def env_value(env_file_values: dict[str, str], *names: str) -> str | None:
    for name in names:
        value = os.getenv(name)
        if value is not None and value.strip():
            return value.strip()
        value = env_file_values.get(name)
        if value is not None and value.strip():
            return value.strip()
    return None


def load_project_env_file() -> dict[str, str]:
    module_path = Path(__file__).resolve()
    project_root = module_path.parents[4]
    candidates = [
        Path.cwd() / ".env",
        project_root / ".env",
    ]
    for path in candidates:
        if path.is_file():
            return parse_env_file(path)
    return {}


def parse_env_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8-sig").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip("\"'")
        if key:
            values[key] = value
    return values


def import_websocket_client() -> Any:
    try:
        import websocket  # type: ignore
    except ImportError as exc:
        raise RuntimeError("Missing dependency: install websocket-client in the rag conda env.") from exc
    return websocket


def build_full_client_request(sequence: int, request: dict[str, Any]) -> bytes:
    payload = gzip.compress(json.dumps(request, ensure_ascii=False).encode("utf-8"))
    return (
        generate_header(FULL_CLIENT_REQUEST, FLAG_POS_SEQUENCE, SERIALIZATION_JSON, COMPRESSION_GZIP)
        + sequence.to_bytes(4, "big", signed=True)
        + len(payload).to_bytes(4, "big", signed=False)
        + payload
    )


def build_audio_request(sequence: int, audio: bytes, final: bool) -> bytes:
    flags = FLAG_NEG_SEQUENCE_1 if final else FLAG_POS_SEQUENCE
    payload = gzip.compress(audio)
    return (
        generate_header(AUDIO_ONLY_REQUEST, flags, SERIALIZATION_RAW, COMPRESSION_GZIP)
        + sequence.to_bytes(4, "big", signed=True)
        + len(payload).to_bytes(4, "big", signed=False)
        + payload
    )


def generate_header(message_type: int, flags: int, serialization: int, compression: int) -> bytes:
    return bytes([
        (PROTOCOL_VERSION << 4) | HEADER_SIZE,
        (message_type << 4) | flags,
        (serialization << 4) | compression,
        0x00,
    ])


def read_available_responses(ws: Any) -> list[DoubaoAsrSegment]:
    ws.settimeout(0.05)
    segments: list[DoubaoAsrSegment] = []
    while True:
        try:
            message = ws.recv()
        except Exception:
            break
        if isinstance(message, str):
            continue
        parsed = parse_response(message)
        segment = segment_from_payload(parsed)
        if segment is not None:
            segments.append(segment)
    return segments


def read_until_final(ws: Any) -> list[DoubaoAsrSegment]:
    ws.settimeout(None)
    segments: list[DoubaoAsrSegment] = []
    while True:
        try:
            message = ws.recv()
        except Exception:
            return segments
        if isinstance(message, str):
            continue
        parsed = parse_response(message)
        segment = segment_from_payload(parsed)
        if segment is not None:
            segments.append(segment)
        if parsed.get("messageType") == "error":
            raise RuntimeError(f"Doubao ASR error: {parsed}")
        if parsed.get("sequence", 0) < 0 or (segment and segment.is_final):
            break
    return segments


def parse_response(message: bytes) -> dict[str, Any]:
    if len(message) < 8:
        raise RuntimeError(f"invalid ASR response frame length: {len(message)}")
    header_size = (message[0] & 0x0F) * 4
    message_type = message[1] >> 4
    flags = message[1] & 0x0F
    serialization = message[2] >> 4
    compression = message[2] & 0x0F
    sequence: int | None = None

    payload_area = message[header_size:]
    if message_type == FULL_SERVER_RESPONSE:
        offset = 0
        if flags & 0x01:
            sequence = int.from_bytes(payload_area[offset:offset + 4], "big", signed=True)
            offset += 4
        payload_size = int.from_bytes(payload_area[offset:offset + 4], "big", signed=False)
        offset += 4
        payload = payload_area[offset:offset + payload_size]
    elif message_type == SERVER_ACK:
        if len(payload_area) < 4:
            return {"messageType": "ack", "sequence": None, "payload": None}
        sequence = int.from_bytes(payload_area[:4], "big", signed=True)
        if len(payload_area) < 8:
            return {"messageType": "ack", "sequence": sequence, "payload": None}
        payload_size = int.from_bytes(payload_area[4:8], "big", signed=False)
        payload = payload_area[8:8 + payload_size]
    elif message_type == ERROR_INFORMATION:
        error_code = int.from_bytes(payload_area[:4], "big", signed=True) if len(payload_area) >= 4 else None
        payload_size = int.from_bytes(payload_area[4:8], "big", signed=False) if len(payload_area) >= 8 else 0
        payload = payload_area[8:8 + payload_size]
    else:
        offset = 0
        if flags & 0x01:
            sequence = int.from_bytes(payload_area[offset:offset + 4], "big", signed=True)
            offset += 4
        payload_size = int.from_bytes(payload_area[offset:offset + 4], "big", signed=False) if len(payload_area) >= offset + 4 else 0
        offset += 4
        payload = payload_area[offset:offset + payload_size]

    if compression == COMPRESSION_GZIP:
        payload = gzip.decompress(payload)

    body: Any
    if serialization == SERIALIZATION_JSON and payload:
        body = json.loads(payload.decode("utf-8"))
    elif payload:
        body = payload.decode("utf-8", errors="replace")
    else:
        body = None

    if message_type == ERROR_INFORMATION:
        return {"messageType": "error", "sequence": sequence, "errorCode": error_code, "payload": body}
    if message_type == SERVER_ACK:
        return {"messageType": "ack", "sequence": sequence, "payload": body}
    if message_type == FULL_SERVER_RESPONSE:
        return {"messageType": "response", "sequence": sequence, "payload": body}
    return {"messageType": f"unknown:{message_type}", "sequence": sequence, "payload": body}


def segment_from_payload(parsed: dict[str, Any]) -> DoubaoAsrSegment | None:
    payload = parsed.get("payload")
    if not isinstance(payload, dict):
        return None
    result = payload.get("result") if isinstance(payload.get("result"), dict) else payload
    text = first_text_value(result)
    if not text:
        return None
    is_final = first_bool_value(result, ["is_final", "final", "definite"])
    start_ms = first_int_value(result, ["start_time", "start_time_ms", "start"])
    end_ms = first_int_value(result, ["end_time", "end_time_ms", "end"])
    if parsed.get("sequence", 0) < 0:
        is_final = True
    return DoubaoAsrSegment(
        text=text,
        is_final=is_final,
        start_time_ms=start_ms,
        end_time_ms=end_ms,
        raw=payload,
    )


def first_text_value(value: Any) -> str | None:
    if isinstance(value, dict):
        for key in ("text", "utterance", "transcript"):
            raw = value.get(key)
            if isinstance(raw, str) and raw.strip():
                return raw.strip()
        for key in ("result", "results", "utterances"):
            nested = first_text_value(value.get(key))
            if nested:
                return nested
    if isinstance(value, list):
        parts = [first_text_value(item) for item in value]
        text = "".join(part for part in parts if part)
        return text or None
    return None


def first_bool_value(value: Any, keys: list[str]) -> bool | None:
    if not isinstance(value, dict):
        return None
    for key in keys:
        raw = value.get(key)
        if isinstance(raw, bool):
            return raw
    return None


def first_int_value(value: Any, keys: list[str]) -> int | None:
    if not isinstance(value, dict):
        return None
    for key in keys:
        raw = value.get(key)
        if isinstance(raw, int):
            return raw
    return None


def choose_final_text(segments: list[DoubaoAsrSegment]) -> str:
    final_segments = [segment.text for segment in segments if segment.is_final]
    if final_segments:
        return final_segments[-1]
    if not segments:
        return ""
    return segments[-1].text


def normalize_audio_format(configured: str, path: Path) -> str:
    if configured and configured != "auto":
        return configured.lower()
    guessed = mimetypes.guess_type(path.name)[0]
    if guessed == "audio/wav" or path.suffix.lower() == ".wav":
        return "wav"
    if guessed == "audio/mpeg" or path.suffix.lower() == ".mp3":
        return "mp3"
    if path.suffix.lower() in {".ogg", ".opus"}:
        return "ogg"
    if path.suffix.lower() == ".pcm":
        return "pcm"
    return DEFAULT_AUDIO_FORMAT


def main() -> None:
    env_file_values = load_project_env_file()
    parser = argparse.ArgumentParser(description="Transcribe audio to text with Doubao ASR.")
    parser.add_argument("--audio", required=True, help="Local audio file path.")
    parser.add_argument("--json", action="store_true", help="Print structured ASR result instead of plain text.")
    parser.add_argument("--endpoint", default=env_value(env_file_values, "DOUBAO_ASR_ENDPOINT") or DEFAULT_ENDPOINT)
    parser.add_argument("--resource-id", default=env_value(env_file_values, "DOUBAO_ASR_RESOURCE_ID") or DEFAULT_RESOURCE_ID)
    parser.add_argument("--app-key", default=env_value(env_file_values, "DOUBAO_ASR_APP_KEY", "VOLCENGINE_ASR_APP_KEY"))
    parser.add_argument(
        "--access-key",
        default=(
            env_value(
                env_file_values,
                "DOUBAO_ASR_ACCESS_KEY",
                "DOUBAO_ASR_ACCESS_KEY.",
                "VOLCENGINE_ASR_ACCESS_KEY",
                "DOUBAO_ASR_ACCESS_TOKEN",
                "VOLCENGINE_ASR_ACCESS_TOKEN",
            )
        ),
    )
    parser.add_argument("--audio-format", default=env_value(env_file_values, "DOUBAO_ASR_AUDIO_FORMAT") or "auto")
    parser.add_argument("--sample-rate", type=int, default=int(env_value(env_file_values, "DOUBAO_ASR_SAMPLE_RATE") or str(DEFAULT_SAMPLE_RATE)))
    parser.add_argument("--chunk-bytes", type=int, default=int(env_value(env_file_values, "DOUBAO_ASR_CHUNK_BYTES") or str(DEFAULT_CHUNK_BYTES)))
    parser.add_argument("--timeout-seconds", type=int, default=int(env_value(env_file_values, "DOUBAO_ASR_TIMEOUT_SECONDS") or "60"))
    args = parser.parse_args()

    config = DoubaoAsrConfig(
        endpoint=args.endpoint,
        resource_id=args.resource_id,
        app_key=args.app_key,
        access_key=args.access_key,
        audio_format=args.audio_format,
        sample_rate=args.sample_rate,
        chunk_bytes=args.chunk_bytes,
        timeout_seconds=args.timeout_seconds,
    )
    result = transcribe_audio_file(args.audio, config)
    if args.json:
        print(json.dumps(result.public_view(), ensure_ascii=False, indent=2))
    else:
        print(result.text)


if __name__ == "__main__":
    main()
