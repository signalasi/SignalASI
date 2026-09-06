"""One-time rendezvous sessions and opaque relationship registry."""
from __future__ import annotations

import logging
import os
import secrets
import threading
import time
from copy import deepcopy
from pathlib import Path

from link_protocol import (
    LinkTopics,
    new_link_secret,
    new_route_id,
    pairing_topic,
    valid_link_secret,
    valid_route_id,
)
from pairing_access import grant_for_executor, normalize_grant
from secure_state import read_secure_json, write_secure_json

TTL_SECONDS = 10 * 60
DEFAULT_DATA_DIR = (
    Path(os.environ["APPDATA"]) / "galaxyssi-desktop" / "runtime"
    if os.name == "nt" and os.environ.get("APPDATA")
    else Path.home() / ".galaxyssi"
)
DATA_DIR = Path(os.environ.get("GALAXYSSI_DATA_DIR", DEFAULT_DATA_DIR))
STATE_PATH = DATA_DIR / "opaque_link_registry_v2.json"
STATE_PURPOSE = "opaque-link-registry-v2"

_tokens: dict[str, dict] = {}
_registry_lock = threading.RLock()
_last_good_state: dict | None = None
_last_good_path = ""
logger = logging.getLogger(__name__)


def normalize_client_display_name(
    display_name: object,
    *,
    user_renamed: bool = False,
) -> str:
    """Collapse transport-generated duplicate name segments.

    A user alias is intentionally opaque. Automatic device names, however, can
    arrive as ``device · device · suffix`` when a phone profile already includes
    the device name. Keep one copy so every Desktop surface receives the same
    canonical identity.
    """
    clean = " ".join(str(display_name or "").strip().split())[:120]
    if not clean or user_renamed:
        return clean
    parts = [" ".join(part.strip().split()) for part in clean.split("·")]
    collapsed: list[str] = []
    for part in parts:
        if not part:
            continue
        if collapsed and collapsed[-1].casefold() == part.casefold():
            continue
        collapsed.append(part)
    return " · ".join(collapsed)[:120]


class PairingRegistryError(RuntimeError):
    """Raised when an existing pairing registry cannot be recovered safely."""


class PairingRegistryVersionError(ValueError):
    """Raised when a registry belongs to a deliberately unsupported schema."""


def _empty_state() -> dict:
    return {
        "schema": 4,
        "clients": {},
        "updated_at": time.time(),
    }


def _backup_path() -> Path:
    return STATE_PATH.with_name(f"{STATE_PATH.name}.bak")


def _state_path_key() -> str:
    return str(STATE_PATH.resolve())


def _validated_state(data: object) -> dict:
    if not isinstance(data, dict):
        raise ValueError("registry root must be an object")
    if int(data.get("schema") or 0) != 4:
        raise PairingRegistryVersionError("registry schema is not opaque link v2 hard cut")
    if not isinstance(data.get("clients"), dict):
        raise ValueError("registry clients must be an object")
    clean = deepcopy(data)
    for client in data["clients"].values():
        if not isinstance(client, dict):
            raise ValueError("registry client must be an object")
        if not valid_link_secret(client.get("link_secret")):
            raise ValueError("registry client has an invalid link secret")
        if not str(client.get("local_identity_fingerprint") or ""):
            raise ValueError("registry client has no local identity fingerprint")
    clean.setdefault("schema", 4)
    clean.setdefault("updated_at", time.time())
    return clean


def _load_state(path: Path) -> dict:
    document = read_secure_json(
        path,
        purpose=STATE_PURPOSE,
        allow_legacy_plaintext=False,
    )
    state = _validated_state(document.value)
    return state


def _remember_state(data: dict) -> dict:
    global _last_good_path, _last_good_state
    clean = _validated_state(data)
    _last_good_path = _state_path_key()
    _last_good_state = deepcopy(clean)
    return clean


def _cached_state() -> dict | None:
    if _last_good_path != _state_path_key() or _last_good_state is None:
        return None
    return deepcopy(_last_good_state)


def _write_state(data: dict) -> None:
    with _registry_lock:
        clean = _validated_state(data)
        # Write the recovery copy first. A crash between the two replacements
        # still leaves at least one complete registry with the newest state.
        write_secure_json(_backup_path(), clean, purpose=STATE_PURPOSE)
        write_secure_json(STATE_PATH, clean, purpose=STATE_PURPOSE)
        _remember_state(clean)


def _restore_state(reason: Exception | None = None) -> dict | None:
    try:
        recovered = _load_state(_backup_path())
        source = "backup"
    except Exception:
        recovered = _cached_state()
        source = "memory"
    if recovered is None:
        return None
    _write_state(recovered)
    logger.warning(
        "Recovered GalaxySSI Link pairing registry from %s after primary read failure: %s",
        source,
        reason or "registry missing",
    )
    return recovered


def _read_state() -> dict:
    with _registry_lock:
        if not STATE_PATH.exists():
            recovered = _restore_state()
            if recovered is not None:
                return recovered
            data = _empty_state()
            _write_state(data)
            return deepcopy(data)

        try:
            return _remember_state(_load_state(STATE_PATH))
        except PairingRegistryVersionError as error:
            logger.warning(
                "Discarding unsupported GalaxySSI Link registry; every device must pair again: %s",
                error,
            )
            STATE_PATH.unlink(missing_ok=True)
            _backup_path().unlink(missing_ok=True)
            data = _empty_state()
            _write_state(data)
            return deepcopy(data)
        except Exception as error:
            recovered = _restore_state(error)
            if recovered is not None:
                return recovered
            logger.error(
                "GalaxySSI Link pairing registry is unreadable and no recovery copy is available: %s",
                error,
            )
            raise PairingRegistryError(
                "Pairing registry is unreadable; refusing to replace the existing identity"
            ) from error


def new_pairing_session(access_grant: dict | None = None) -> dict:
    with _registry_lock:
        now = time.time()
        for token, entry in list(_tokens.items()):
            if now - float(entry.get("created_at") or 0) > TTL_SECONDS:
                _tokens.pop(token, None)
        token = secrets.token_urlsafe(24)
        secret = new_link_secret()
        grant = normalize_grant(access_grant or grant_for_executor(False))
        topic = pairing_topic(secret)
        _tokens[token] = {"created_at": now, "secret": secret, "topic": topic, "access": grant}
        return {
            "token": token,
            "secret": secret,
            "topic": topic,
            "created_at": now,
            "expires_at": now + TTL_SECONDS,
            "access": grant,
        }


def new_pairing_token() -> str:
    return str(new_pairing_session()["token"])


def pairing_secret(token: str) -> str:
    with _registry_lock:
        entry = _tokens.get(str(token or "")) or {}
        if time.time() - float(entry.get("created_at") or 0) > TTL_SECONDS:
            return ""
        return str(entry.get("secret") or "")


def pairing_session_for_topic(topic: str) -> dict | None:
    candidate = str(topic or "")
    with _registry_lock:
        for token in list(_tokens):
            entry = pairing_session(token)
            if entry and secrets.compare_digest(str(entry.get("topic") or ""), candidate):
                return {**entry, "token": token}
    return None


def active_pairing_topics() -> tuple[str, ...]:
    with _registry_lock:
        active: list[str] = []
        for token in list(_tokens):
            entry = pairing_session(token)
            if entry:
                active.append(str(entry["topic"]))
        return tuple(active)


def validate_pairing_token(token: str, consume: bool = False) -> bool:
    return consume_pairing_session(token) is not None if consume else pairing_session(token) is not None


def pairing_session(token: str) -> dict | None:
    with _registry_lock:
        entry = _tokens.get(str(token or ""))
        created_at = float((entry or {}).get("created_at") or 0)
        if not entry or time.time() - created_at > TTL_SECONDS:
            _tokens.pop(str(token or ""), None)
            return None
        return {
            **entry,
            "access": normalize_grant(entry.get("access")),
        }


def claim_pairing_session(token: str, fingerprint: str, client_route_id: str) -> dict | None:
    """Bind a short-lived pairing token to one identity and route.

    The binding remains available until token expiry so a lost confirmation can
    be replayed without allowing the token to pair another identity or route.
    """
    with _registry_lock:
        entry = pairing_session(token)
        if entry is None:
            return None
        claimed_fingerprint = str(entry.get("claimed_fingerprint") or "")
        claimed_route_id = str(entry.get("claimed_client_route_id") or "")
        if claimed_fingerprint or claimed_route_id:
            if not secrets.compare_digest(claimed_fingerprint, str(fingerprint or "")):
                return None
            if not secrets.compare_digest(claimed_route_id, str(client_route_id or "")):
                return None
            return entry
        stored = _tokens.get(str(token or ""))
        if stored is None:
            return None
        stored["claimed_fingerprint"] = str(fingerprint or "")
        stored["claimed_client_route_id"] = str(client_route_id or "")
        stored["claimed_at"] = time.time()
        return {
            **stored,
            "access": normalize_grant(stored.get("access")),
        }


def consume_pairing_session(token: str) -> dict | None:
    with _registry_lock:
        entry = pairing_session(token)
        if entry is None:
            return None
        _tokens.pop(str(token), None)
        return entry


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
    display_name: str = "GalaxySSI Client",
    platform: str = "unknown",
    device_id: str = "",
    device_name: str = "",
    device_manufacturer: str = "",
    device_model: str = "",
    platform_version: str = "",
    profile_name: str = "",
    user_renamed: bool = False,
    access_grant: dict | None = None,
    link_secret: str = "",
    local_identity_fingerprint: str = "",
) -> dict:
    if not fingerprint:
        raise ValueError("identity fingerprint required")
    route_id = client_route_id or new_route_id()
    if not valid_route_id(route_id):
        raise ValueError("invalid client route id")
    if not valid_link_secret(link_secret):
        raise ValueError("valid link secret required")
    if not local_identity_fingerprint:
        raise ValueError("local identity fingerprint required")
    with _registry_lock:
        state = _read_state()
        previous = state["clients"].get(route_id, {})
        now = time.time()
        access = normalize_grant(access_grant or grant_for_executor(False))
        retained_user_alias = bool(user_renamed or previous.get("user_renamed"))
        resolved_display_name = (
            previous.get("display_name")
            if previous.get("user_renamed")
            else display_name or previous.get("display_name") or "GalaxySSI Client"
        )
        client = {
            "client_route_id": route_id,
            "signal_name": remote_name or previous.get("signal_name") or f"client_{route_id}",
            "signal_device_id": int(remote_device_id or 1),
            "identity_fingerprint": fingerprint,
            "local_identity_fingerprint": local_identity_fingerprint,
            "link_secret": link_secret,
            "display_name": normalize_client_display_name(
                resolved_display_name,
                user_renamed=retained_user_alias,
            ),
            "platform": platform or previous.get("platform") or "unknown",
            "device_id": device_id or previous.get("device_id") or f"phone_{fingerprint[:16]}",
            "device_name": device_name or previous.get("device_name") or display_name,
            "device_manufacturer": device_manufacturer or previous.get("device_manufacturer") or "",
            "device_model": device_model or previous.get("device_model") or "",
            "platform_version": platform_version or previous.get("platform_version") or "",
            "profile_name": profile_name or previous.get("profile_name") or "",
            "user_renamed": retained_user_alias,
            "access_profile": access["profile"],
            "access_scopes": list(access["scopes"]),
            "access_granted_at": int(access["issued_at"]),
            "paired_at": float(previous.get("paired_at") or now),
            "updated_at": now,
            "last_seen_at": now,
            "revoked": False,
        }
        state["clients"][route_id] = client
        state["updated_at"] = now
        _write_state(state)
        return client_status(client)


def rename_client(client_route_id: str, display_name: str) -> dict:
    clean_name = " ".join(str(display_name or "").strip().split())[:120]
    if not clean_name:
        raise ValueError("display name required")
    with _registry_lock:
        state = _read_state()
        client = state["clients"].get(client_route_id)
        if not isinstance(client, dict) or client.get("revoked"):
            raise KeyError("paired client not found")
        now = time.time()
        client["display_name"] = clean_name
        client["user_renamed"] = True
        client["updated_at"] = now
        state["updated_at"] = now
        _write_state(state)
        return public_client_status(client_status(client))


def client_status(client: dict) -> dict:
    topics = LinkTopics(
        str(client.get("link_secret") or ""),
        str(client.get("local_identity_fingerprint") or ""),
        str(client.get("identity_fingerprint") or ""),
    )
    normalized = dict(client)
    normalized["display_name"] = normalize_client_display_name(
        client.get("display_name"),
        user_renamed=bool(client.get("user_renamed")),
    )
    return {
        **normalized,
        "access": normalize_grant({
            "profile": client.get("access_profile"),
            "scopes": client.get("access_scopes"),
            "desktop_executor": client.get("access_profile") == "desktop_executor",
            "issued_at": client.get("access_granted_at"),
        }),
        "paired": not bool(client.get("revoked")),
        "identity_fingerprint_short": str(client.get("identity_fingerprint") or "")[:16],
        "transport": {
            "receive_topic_count": len(topics.receive_window),
            "rotates_every_seconds": 6 * 60 * 60,
        },
    }


def public_client_status(client: dict) -> dict:
    value = dict(client)
    value.pop("link_secret", None)
    value.pop("local_identity_fingerprint", None)
    return value


def get_client(client_route_id: str, include_revoked: bool = False) -> dict | None:
    state = _read_state()
    client = state["clients"].get(client_route_id)
    if not isinstance(client, dict) or (client.get("revoked") and not include_revoked):
        return None
    return client_status(client)


def list_clients(include_revoked: bool = False) -> list[dict]:
    state = _read_state()
    values = []
    for client in state["clients"].values():
        if not isinstance(client, dict) or (client.get("revoked") and not include_revoked):
            continue
        values.append(client_status(client))
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
        result = public_client_status(client_status(client))
    # Settings read pairing state under their own lock; avoid taking it while holding the registry lock.
    from blob_pair_configuration import forget_settings
    forget_settings(STATE_PATH.parent, client_route_id)
    return result


def forget_client(client_route_id: str) -> bool:
    """Permanently remove one client after all transport state has been revoked."""
    route_id = str(client_route_id or "").strip()
    if not route_id:
        return False
    with _registry_lock:
        state = _read_state()
        present = route_id in state["clients"]
        if present:
            state["clients"].pop(route_id, None)
            state["updated_at"] = time.time()
            _write_state(state)
    from blob_pair_configuration import forget_settings
    forget_settings(STATE_PATH.parent, route_id)
    return present


def clear_pairing_state(client_route_id: str = "") -> dict:
    route_id = str(client_route_id or "").strip()
    if route_id:
        forget_client(route_id)
    else:
        with _registry_lock:
            state = _read_state()
            routes = list(state["clients"])
            state["clients"] = {}
            state["updated_at"] = time.time()
            _write_state(state)
        from blob_pair_configuration import forget_settings
        for route in routes:
            forget_settings(STATE_PATH.parent, route)
    return pairing_status()


def is_paired(client_route_id: str = "") -> bool:
    return bool(get_client(client_route_id)) if client_route_id else bool(list_clients())


def pairing_status() -> dict:
    clients = [public_client_status(client) for client in list_clients()]
    return {
        "paired": bool(clients),
        "state": "paired" if clients else ("waiting_for_scan" if token_status()["active"] else "not_paired"),
        "client_count": len(clients),
        "clients": clients,
        "token": token_status(),
        # Transitional summary fields for the current Desktop renderer.
        "remote_name": clients[0].get("signal_name", "") if clients else "",
        "remote_device_id": clients[0].get("signal_device_id", 0) if clients else 0,
        "identity_fingerprint": clients[0].get("identity_fingerprint", "") if clients else "",
        "identity_fingerprint_short": clients[0].get("identity_fingerprint_short", "") if clients else "",
    }
