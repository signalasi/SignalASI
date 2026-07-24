"""Bounded, integrity-checked fragmentation for encrypted SignalASI MQTT wire payloads."""

from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
import json
import threading
import time
from dataclasses import dataclass, field

from link_protocol import PROTOCOL_NAME, PROTOCOL_VERSION

SCHEME = "signal-chunk"
DIRECT_LIMIT_BYTES = 48 * 1024
CHUNK_DATA_BYTES = 32 * 1024
MAX_REASSEMBLED_BYTES = 2 * 1024 * 1024
MAX_CHUNK_COUNT = 64
MAX_PACKET_BYTES = 60 * 1024
TRANSFER_TTL_SECONDS = 120.0
MAX_ACTIVE_TRANSFERS = 16


def _sha256(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def is_chunk(payload: dict) -> bool:
    return str(payload.get("scheme") or "") == SCHEME


def encode_wire_payload(
    wire_payload: str,
    *,
    direct_limit_bytes: int = DIRECT_LIMIT_BYTES,
    chunk_data_bytes: int = CHUNK_DATA_BYTES,
) -> list[str]:
    """Return one direct wire packet or multiple independently verified chunks."""
    if direct_limit_bytes <= 0 or chunk_data_bytes <= 0:
        raise ValueError("MQTT fragmentation limits must be positive")
    payload = wire_payload.encode("utf-8")
    if len(payload) <= direct_limit_bytes:
        return [wire_payload]
    if len(payload) > MAX_REASSEMBLED_BYTES:
        raise ValueError("MQTT wire payload exceeds reassembly limit")

    chunk_count = (len(payload) + chunk_data_bytes - 1) // chunk_data_bytes
    if not 2 <= chunk_count <= MAX_CHUNK_COUNT:
        raise ValueError("MQTT wire payload requires too many chunks")
    digest = _sha256(payload)
    try:
        envelope = json.loads(wire_payload)
    except (TypeError, ValueError):
        envelope = {}

    packets: list[str] = []
    for chunk_index in range(chunk_count):
        start = chunk_index * chunk_data_bytes
        chunk = payload[start : start + chunk_data_bytes]
        packet = json.dumps(
            {
                "protocol": PROTOCOL_NAME,
                "version": PROTOCOL_VERSION,
                "scheme": SCHEME,
                "transfer_id": digest,
                "chunk_index": chunk_index,
                "chunk_count": chunk_count,
                "total_bytes": len(payload),
                "sha256": digest,
                "chunk_sha256": _sha256(chunk),
                "from": str(envelope.get("from") or ""),
                "to": str(envelope.get("to") or ""),
                "data": base64.b64encode(chunk).decode("ascii"),
            },
            ensure_ascii=False,
            separators=(",", ":"),
        )
        if len(packet.encode("utf-8")) > MAX_PACKET_BYTES:
            raise ValueError("MQTT chunk packet exceeds packet limit")
        packets.append(packet)
    return packets


@dataclass
class _PartialTransfer:
    chunk_count: int
    total_bytes: int
    sha256: str
    source: str
    target: str
    updated_at: float
    chunks: dict[int, bytes] = field(default_factory=dict)


class MqttWireChunkAssembler:
    """Reassemble encrypted payloads only after every integrity check succeeds."""

    def __init__(
        self,
        *,
        ttl_seconds: float = TRANSFER_TTL_SECONDS,
        maximum_active_transfers: int = MAX_ACTIVE_TRANSFERS,
        clock=time.monotonic,
    ) -> None:
        self._ttl_seconds = ttl_seconds
        self._maximum_active_transfers = maximum_active_transfers
        self._clock = clock
        self._transfers: dict[str, _PartialTransfer] = {}
        self._lock = threading.RLock()

    def accept(self, scope: str, wire: dict) -> str | None:
        if not is_chunk(wire):
            raise ValueError("Not a SignalASI MQTT chunk")
        now = self._clock()
        with self._lock:
            self._prune(now)
            transfer_id = str(wire.get("transfer_id") or "").lower()
            full_hash = str(wire.get("sha256") or "").lower()
            chunk_hash = str(wire.get("chunk_sha256") or "").lower()
            chunk_index = _strict_int(wire.get("chunk_index"), "chunk index")
            chunk_count = _strict_int(wire.get("chunk_count"), "chunk count")
            total_bytes = _strict_int(wire.get("total_bytes"), "transfer size")
            source = str(wire.get("from") or "")
            target = str(wire.get("to") or "")
            if len(transfer_id) != 64 or transfer_id != full_hash:
                raise ValueError("Invalid MQTT transfer identity")
            if len(chunk_hash) != 64:
                raise ValueError("Invalid MQTT chunk hash")
            if not 2 <= chunk_count <= MAX_CHUNK_COUNT:
                raise ValueError("Invalid MQTT chunk count")
            if not 0 <= chunk_index < chunk_count:
                raise ValueError("Invalid MQTT chunk index")
            if not 1 <= total_bytes <= MAX_REASSEMBLED_BYTES:
                raise ValueError("Invalid MQTT transfer size")
            try:
                chunk = base64.b64decode(str(wire.get("data") or ""), validate=True)
            except (ValueError, binascii.Error) as exc:
                raise ValueError("Invalid MQTT chunk encoding") from exc
            if not chunk or len(chunk) > CHUNK_DATA_BYTES:
                raise ValueError("Invalid MQTT chunk size")
            if not secrets_compare(_sha256(chunk), chunk_hash):
                raise ValueError("MQTT chunk integrity check failed")

            key = f"{scope}:{transfer_id}"
            partial = self._transfers.get(key)
            if partial is None:
                if len(self._transfers) >= self._maximum_active_transfers:
                    oldest = min(
                        self._transfers,
                        key=lambda candidate: self._transfers[candidate].updated_at,
                    )
                    self._transfers.pop(oldest, None)
                partial = _PartialTransfer(
                    chunk_count=chunk_count,
                    total_bytes=total_bytes,
                    sha256=full_hash,
                    source=source,
                    target=target,
                    updated_at=now,
                )
                self._transfers[key] = partial
            if (
                partial.chunk_count != chunk_count
                or partial.total_bytes != total_bytes
                or partial.sha256 != full_hash
                or partial.source != source
                or partial.target != target
            ):
                raise ValueError("MQTT chunk metadata mismatch")

            previous = partial.chunks.get(chunk_index)
            if previous is not None and previous != chunk:
                raise ValueError("Conflicting MQTT chunk duplicate")
            partial.chunks[chunk_index] = chunk
            partial.updated_at = now
            if len(partial.chunks) < partial.chunk_count:
                return None

            assembled = b"".join(partial.chunks[index] for index in range(partial.chunk_count))
            self._transfers.pop(key, None)
            if len(assembled) != partial.total_bytes:
                raise ValueError("MQTT transfer length check failed")
            if not secrets_compare(_sha256(assembled), partial.sha256):
                raise ValueError("MQTT transfer integrity check failed")
            try:
                return assembled.decode("utf-8")
            except UnicodeDecodeError as exc:
                raise ValueError("MQTT transfer is not valid UTF-8") from exc

    def clear(self) -> None:
        with self._lock:
            self._transfers.clear()

    def _prune(self, now: float) -> None:
        cutoff = now - self._ttl_seconds
        expired = [
            transfer_id
            for transfer_id, partial in self._transfers.items()
            if partial.updated_at < cutoff
        ]
        for transfer_id in expired:
            self._transfers.pop(transfer_id, None)


def _strict_int(value: object, field_name: str) -> int:
    if isinstance(value, bool):
        raise ValueError(f"Invalid MQTT {field_name}")
    try:
        parsed = int(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"Invalid MQTT {field_name}") from exc
    if str(parsed) != str(value):
        raise ValueError(f"Invalid MQTT {field_name}")
    return parsed


def secrets_compare(left: str, right: str) -> bool:
    return hmac.compare_digest(left, right)
