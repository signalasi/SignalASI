"""Pairing tokens and persisted SignalASI Link v1 client registry."""
from __future__ import annotations

import json
import os
import secrets
import threading
import time
from pathlib import Path

from link_protocol import LinkTopics, new_route_id, valid_route_id

TTL_SECONDS = 10 * 60
DEFAULT_DATA_DIR = (
    Path(os.environ["APPDATA"]) / "signalasi-desktop" / "runtime"
    if os.name == "nt" and os.environ.get("APPDATA")
    else Path.home() / ".signalasi"
)
DATA_DIR = Path(os.environ.get("SIGNALASI_DATA_DIR", DEFAULT_DATA_DIR))
STATE_PATH = DATA_DIR / "signalasi_link_registry.json"

_tokens: dict[str, dict] = {}
_registry_lock = threading.RLock()


def _empty_state() -> dict:
    return {
        "schema": 2,
        "server_route_id": new_route_id(),
        "clients": {},
        "updated_at": time.time(),
    }


def _read_state() -> dict:
    try:
        data = json.loads(STATE_PATH.read_text(encoding="utf-8"))
        if not isinstance(data, dict):
            raise ValueError("invalid registry")
    except Exception:
        data = _empty_state()
        _write_state(data)
    if not valid_route_id(data.get("server_route_id")):
        data["server_route_id"] = new_route_id()
        _write_state(data)
    if not isinstance(data.get("clients"), dict):
        data["clients"] = {}
    return data


def _write_state(data: dict) -> None:
    STATE_PATH.parent.mkdir(parents=True, exist_ok=True)
    temp = STATE_PATH.with_suffix(".tmp")
    temp.write_text(f"{json.dumps(data, ensure_ascii=False, indent=2)}\n", encoding="utf-8")
    temp.replace(STATE_PATH)


def server_route_id() -> str:
    return str(_read_state()["server_route_id"])


def new_pairing_session() -> dict:
    with _registry_lock:
        now = time.time()
        for token, entry in list(_tokens.items()):
            if now - float(entry.get("created_at") or 0) > TTL_SECONDS:
                _tokens.pop(token, None)
        token = secrets.token_urlsafe(24)
        secret = secrets.token_urlsafe(32)
        _tokens[token] = {"created_at": now, "secret": secret}
        return {"token": token, "secret": secret, "created_at": now, "expires_at": now + TTL_SECONDS}


def new_pairing_token() -> str:
    return str(new_pairing_session()["token"])


def pairing_secret(token: str) -> str:
    with _registry_lock:
        entry = _tokens.get(str(token or "")) or {}
        if time.time() - float(entry.get("created_at") or 0) > TTL_SECONDS:
            return ""
        return str(entry.get("secret") or "")


def validate_pairing_token(token: str, consume: bool = False) -> bool:
    with _registry_lock:
        entry = _tokens.get(str(token or ""))
        created_at = float((entry or {}).get("created_at") or 0)
        if not entry or time.time() - created_at > TTL_SECONDS:
            _tokens.pop(str(token or ""), None)
            return False
        if consume:
            _tokens.pop(str(token), None)
        return True


def token_status() -> dict:
    with _registry_lock:
        now = time.time()
        active = [
            float(entry.get("created_at") or 0)
            for entry in _tokens.values()
            if now - float(entry.get("created_at") or 0) <= TTL_SECONDS
        ]
        newest = max(active, default=0.0)
        return {
            "active": bool(active),
            "active_count": len(active),
            "created_at": newest,
            "expires_at": newest + TTL_SECONDS if newest else 0,
            "expires_in": max(0, int(newest + TTL_SECONDS - now)) if newest else 0,
        }


def record_pairing_success(
    fingerprint: str,
    remote_name: str = "",
    remote_device_id: int = 1,
    *,
    client_route_id: str = "",
    display_name: str = "SignalASI Client",
    platform: str = "unknown",
) -> dict:
    if not fingerprint:
        raise ValueError("identity fingerprint required")
    route_id = client_route_id or new_route_id()
    if not valid_route_id(route_id):
        raise ValueError("invalid client route id")
    with _registry_lock:
        state = _read_state()
        previous = state["clients"].get(route_id, {})
        now = time.time()
        client = {
        "client_route_id": route_id,
        "signal_name": remote_name or previous.get("signal_name") or f"client_{route_id}",
        "signal_device_id": int(remote_device_id or 1),
        "identity_fingerprint": fingerprint,
        "display_name": display_name or previous.get("display_name") or "SignalASI Client",
        "platform": platform or previous.get("platform") or "unknown",
        "paired_at": float(previous.get("paired_at") or now),
        "updated_at": now,
        "last_seen_at": now,
        "revoked": False,
        }
        state["clients"][route_id] = client
        state["updated_at"] = now
        _write_state(state)
        return client_status(client, state["server_route_id"])


def client_status(client: dict, server_id: str | None = None) -> dict:
    route_id = str(client.get("client_route_id") or "")
    sid = server_id or server_route_id()
    topics = LinkTopics(sid, route_id)
    return {
        **client,
        "paired": not bool(client.get("revoked")),
        "identity_fingerprint_short": str(client.get("identity_fingerprint") or "")[:16],
        "topics": {"up": topics.up, "down": topics.down, "control": topics.control},
    }


def get_client(client_route_id: str, include_revoked: bool = False) -> dict | None:
    state = _read_state()
    client = state["clients"].get(client_route_id)
    if not isinstance(client, dict) or (client.get("revoked") and not include_revoked):
        return None
    return client_status(client, state["server_route_id"])


def list_clients(include_revoked: bool = False) -> list[dict]:
    state = _read_state()
    values = []
    for client in state["clients"].values():
        if not isinstance(client, dict) or (client.get("revoked") and not include_revoked):
            continue
        values.append(client_status(client, state["server_route_id"]))
    return sorted(values, key=lambda item: float(item.get("paired_at") or 0))


def clients_for_identity(
    fingerprint: str,
    remote_name: str,
    *,
    exclude_route_id: str = "",
) -> list[dict]:
    """Return active routes owned by the same cryptographic client identity."""
    clean_fingerprint = str(fingerprint or "").lower()
    clean_name = str(remote_name or "")
    return [
        client
        for client in list_clients()
        if client["client_route_id"] != exclude_route_id
        and (
            str(client.get("identity_fingerprint") or "").lower() == clean_fingerprint
            or str(client.get("signal_name") or "") == clean_name
        )
    ]


def touch_client(client_route_id: str) -> None:
    with _registry_lock:
        state = _read_state()
        client = state["clients"].get(client_route_id)
        if not isinstance(client, dict) or client.get("revoked"):
            return
        client["last_seen_at"] = time.time()
        client["updated_at"] = client["last_seen_at"]
        state["updated_at"] = client["updated_at"]
        _write_state(state)


def revoke_client(client_route_id: str, reason: str = "forgotten_by_desktop") -> dict | None:
    with _registry_lock:
        state = _read_state()
        client = state["clients"].get(client_route_id)
        if not isinstance(client, dict):
            return None
        client["revoked"] = True
        client["revoked_at"] = time.time()
        client["revoke_reason"] = reason
        client["updated_at"] = client["revoked_at"]
        state["updated_at"] = client["updated_at"]
        _write_state(state)
        return client_status(client, state["server_route_id"])


def clear_pairing_state(client_route_id: str = "") -> dict:
    state = _read_state()
    if client_route_id:
        revoke_client(client_route_id)
    else:
        for route_id in list(state["clients"]):
            revoke_client(route_id)
    return pairing_status()


def is_paired(client_route_id: str = "") -> bool:
    return bool(get_client(client_route_id)) if client_route_id else bool(list_clients())


def pairing_status() -> dict:
    clients = list_clients()
    return {
        "paired": bool(clients),
        "state": "paired" if clients else ("waiting_for_scan" if token_status()["active"] else "not_paired"),
        "server_route_id": server_route_id(),
        "pairing_topic": LinkTopics(server_route_id()).pairing,
        "client_count": len(clients),
        "clients": clients,
        "token": token_status(),
        # Transitional summary fields for the current Desktop renderer.
        "remote_name": clients[0].get("signal_name", "") if clients else "",
        "remote_device_id": clients[0].get("signal_device_id", 0) if clients else 0,
        "identity_fingerprint": clients[0].get("identity_fingerprint", "") if clients else "",
        "identity_fingerprint_short": clients[0].get("identity_fingerprint_short", "") if clients else "",
    }
