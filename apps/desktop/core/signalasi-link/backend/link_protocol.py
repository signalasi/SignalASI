"""SignalASI Link Protocol v1 routing and envelope primitives."""
from __future__ import annotations

import re
import base64
import json
import secrets
import time
import uuid
from dataclasses import dataclass
from typing import Any

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

PROTOCOL_NAME = "signalasi-link"
PROTOCOL_VERSION = 1
TOPIC_ROOT = "signalasichat/v1"
ROUTE_ID_BYTES = 16
MAX_CLOCK_SKEW_MS = 5 * 60 * 1000
DEFAULT_MESSAGE_TTL_MS = 7 * 24 * 60 * 60 * 1000
MAX_ENVELOPE_BYTES = 512 * 1024
MAX_TEXT_BYTES = 128 * 1024
ROUTE_ID_RE = re.compile(r"^[A-Za-z0-9_-]{22}$")


def new_route_id() -> str:
    """Return an opaque 128-bit Base64URL route identifier."""
    return secrets.token_urlsafe(ROUTE_ID_BYTES)


def valid_route_id(value: object) -> bool:
    return bool(ROUTE_ID_RE.fullmatch(str(value or "")))


@dataclass(frozen=True)
class LinkTopics:
    server_route_id: str
    client_route_id: str = ""

    def __post_init__(self) -> None:
        if not valid_route_id(self.server_route_id):
            raise ValueError("invalid server route id")
        if self.client_route_id and not valid_route_id(self.client_route_id):
            raise ValueError("invalid client route id")

    @property
    def pairing(self) -> str:
        return f"{TOPIC_ROOT}/{self.server_route_id}/pair"

    @property
    def up(self) -> str:
        self._require_client()
        return f"{TOPIC_ROOT}/{self.server_route_id}/{self.client_route_id}/up"

    @property
    def down(self) -> str:
        self._require_client()
        return f"{TOPIC_ROOT}/{self.server_route_id}/{self.client_route_id}/down"

    @property
    def control(self) -> str:
        self._require_client()
        return f"{TOPIC_ROOT}/{self.server_route_id}/{self.client_route_id}/control"

    def _require_client(self) -> None:
        if not self.client_route_id:
            raise ValueError("client route id required")


def parse_topic(topic: str) -> tuple[str, str, str] | None:
    parts = str(topic or "").split("/")
    if len(parts) == 4 and parts[:2] == ["signalasichat", "v1"] and parts[3] == "pair":
        return parts[2], "", "pair"
    if len(parts) != 5 or parts[:2] != ["signalasichat", "v1"]:
        return None
    server_route_id, client_route_id, channel = parts[2], parts[3], parts[4]
    if not valid_route_id(server_route_id) or not valid_route_id(client_route_id):
        return None
    if channel not in {"up", "down", "control"}:
        return None
    return server_route_id, client_route_id, channel


def _b64url_decode(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def _b64url_encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def pairing_aad(token: str, server_route_id: str) -> bytes:
    return f"{PROTOCOL_NAME}|{PROTOCOL_VERSION}|{token}|{server_route_id}".encode("utf-8")


def encrypt_pairing_claim(claim: dict[str, Any], token: str, secret: str, server_route_id: str) -> dict[str, Any]:
    nonce = secrets.token_bytes(12)
    ciphertext = AESGCM(_b64url_decode(secret)).encrypt(
        nonce,
        json.dumps(claim, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
        pairing_aad(token, server_route_id),
    )
    return {
        "type": "signalasi_pairing_ciphertext",
        "protocol": PROTOCOL_NAME,
        "version": PROTOCOL_VERSION,
        "pairing_token": token,
        "server_route_id": server_route_id,
        "nonce": _b64url_encode(nonce),
        "ciphertext": _b64url_encode(ciphertext),
    }


def decrypt_pairing_claim(wire: dict[str, Any], secret: str) -> dict[str, Any]:
    if wire.get("type") != "signalasi_pairing_ciphertext":
        raise ValueError("invalid pairing ciphertext type")
    token = str(wire.get("pairing_token") or "")
    route_id = str(wire.get("server_route_id") or "")
    if wire.get("protocol") != PROTOCOL_NAME or wire.get("version") != PROTOCOL_VERSION:
        raise ValueError("unsupported pairing protocol")
    if not token or not valid_route_id(route_id):
        raise ValueError("invalid pairing binding")
    plaintext = AESGCM(_b64url_decode(secret)).decrypt(
        _b64url_decode(str(wire.get("nonce") or "")),
        _b64url_decode(str(wire.get("ciphertext") or "")),
        pairing_aad(token, route_id),
    )
    claim = json.loads(plaintext.decode("utf-8"))
    if not isinstance(claim, dict):
        raise ValueError("pairing claim must be an object")
    return claim


def make_envelope(
    payload: dict[str, Any],
    *,
    source_id: str,
    target_id: str,
    conversation_id: str = "",
    reply_to: str = "",
) -> dict[str, Any]:
    now = int(time.time() * 1000)
    message_id = str(payload.get("message_id") or uuid.uuid4())
    envelope = {
        "protocol": PROTOCOL_NAME,
        "version": PROTOCOL_VERSION,
        "message_id": message_id,
        "conversation_id": str(conversation_id or payload.get("conversation_id") or ""),
        "source_id": source_id,
        "target_id": target_id,
        "reply_to": str(reply_to or payload.get("reply_to") or ""),
        "sent_at": now,
        "expires_at": now + DEFAULT_MESSAGE_TTL_MS,
        "payload": payload,
    }
    return validate_envelope(envelope, now_ms=now)


def validate_envelope(envelope: object, now_ms: int | None = None) -> dict[str, Any]:
    if not isinstance(envelope, dict):
        raise ValueError("envelope must be an object")
    if envelope.get("protocol") != PROTOCOL_NAME or envelope.get("version") != PROTOCOL_VERSION:
        raise ValueError("unsupported protocol")
    try:
        uuid.UUID(str(envelope.get("message_id") or ""))
    except ValueError as exc:
        raise ValueError("invalid message id") from exc
    if not str(envelope.get("source_id") or "") or not str(envelope.get("target_id") or ""):
        raise ValueError("source and target are required")
    if not isinstance(envelope.get("payload"), dict):
        raise ValueError("payload must be an object")
    if len(json.dumps(envelope, ensure_ascii=False, separators=(",", ":")).encode("utf-8")) > MAX_ENVELOPE_BYTES:
        raise ValueError("envelope exceeds size limit")
    content = envelope["payload"].get("content")
    if isinstance(content, str) and len(content.encode("utf-8")) > MAX_TEXT_BYTES:
        raise ValueError("text exceeds size limit")
    sent_at = int(envelope.get("sent_at") or 0)
    reference = int(now_ms if now_ms is not None else time.time() * 1000)
    expires_at = int(envelope.get("expires_at") or 0)
    if sent_at <= 0 or sent_at - reference > MAX_CLOCK_SKEW_MS:
        raise ValueError("message timestamp is in the future")
    if expires_at <= sent_at or reference > expires_at:
        raise ValueError("message expired")
    return envelope
