"""Bounded, ciphertext-only public metadata for the independent Blob plane."""
from __future__ import annotations

import hashlib
import json
import re
from typing import Any

VERSION = 1
CHUNK_BYTES = 1024 * 1024
TAG_BYTES = 16
MAX_FILE_BYTES = 1024 * 1024 * 1024
MAX_CHUNKS = MAX_FILE_BYTES // CHUNK_BYTES
MAX_MANIFEST_BYTES = 128 * 1024
SESSION_PATTERN = re.compile(r"[a-f0-9]{32}")
DIGEST_PATTERN = re.compile(r"[a-f0-9]{64}")


class BlobError(ValueError):
    def __init__(self, code: str, status: int = 400):
        super().__init__(code)
        self.code = code
        self.status = status


def checked_hex(value: Any, pattern: re.Pattern = DIGEST_PATTERN) -> str:
    if not isinstance(value, str) or not pattern.fullmatch(value):
        raise BlobError("invalid_identifier")
    return value


def canonical(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=True, sort_keys=True,
                      separators=(",", ":"), allow_nan=False).encode("ascii")


def sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def manifest(value: Any) -> dict:
    if not isinstance(value, dict) or set(value) != {"version", "chunks"}:
        raise BlobError("invalid_manifest")
    if type(value["version"]) is not int or value["version"] != VERSION:
        raise BlobError("unsupported_blob_version")
    chunks = value["chunks"]
    if not isinstance(chunks, list) or not 1 <= len(chunks) <= MAX_CHUNKS:
        raise BlobError("invalid_chunk_count")
    total = 0
    for index, chunk in enumerate(chunks):
        if not isinstance(chunk, dict) or set(chunk) != {"sha256", "size"}:
            raise BlobError("invalid_chunk_descriptor")
        checked_hex(chunk["sha256"])
        size = chunk["size"]
        if type(size) is not int or not TAG_BYTES <= size <= CHUNK_BYTES + TAG_BYTES:
            raise BlobError("invalid_chunk_size")
        if index < len(chunks) - 1 and size != CHUNK_BYTES + TAG_BYTES:
            raise BlobError("invalid_chunk_layout")
        if len(chunks) > 1 and index == len(chunks) - 1 and size == TAG_BYTES:
            raise BlobError("invalid_chunk_layout")
        total += size - TAG_BYTES
    if total > MAX_FILE_BYTES:
        raise BlobError("file_too_large", 413)
    # Make an owned copy: callers cannot mutate a validated manifest in flight.
    return json.loads(canonical(value))


def capability_digest(token: str) -> str:
    return sha256(bytes.fromhex(checked_hex(token)))


def missing_bitmap(indices: list[int], count: int) -> str:
    bits = bytearray((count + 7) // 8)
    for index in indices:
        bits[index // 8] |= 1 << (index % 8)
    return bits.hex()


def expand_bitmap(value: str, count: int) -> list[int]:
    if type(count) is not int or not 1 <= count <= MAX_CHUNKS:
        raise BlobError("invalid_chunk_count")
    if not isinstance(value, str) or not re.fullmatch(r"[a-f0-9]*", value):
        raise BlobError("invalid_missing_bitmap")
    if len(value) != 2 * ((count + 7) // 8):
        raise BlobError("invalid_missing_bitmap")
    bits = bytes.fromhex(value)
    if count % 8 and bits[-1] >> (count % 8):
        raise BlobError("invalid_missing_bitmap")
    return [index for index in range(count) if bits[index // 8] & (1 << (index % 8))]
