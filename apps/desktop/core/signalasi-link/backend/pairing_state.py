"""Pairing token and persisted phone pairing state."""
from __future__ import annotations

import json
import os
import secrets
import shutil
import time
from pathlib import Path

_token = ""
_created_at = 0.0
TTL_SECONDS = 10 * 60
LEGACY_STATE_PATH = Path(__file__).with_name("signalasi_pairing_state.json")
DEFAULT_DATA_DIR = (
    Path(os.environ["APPDATA"]) / "signalasi-desktop" / "runtime"
    if os.name == "nt" and os.environ.get("APPDATA")
    else Path.home() / ".signalasi"
)
DATA_DIR = Path(os.environ.get("SIGNALASI_DATA_DIR", DEFAULT_DATA_DIR))
STATE_PATH = DATA_DIR / "signalasi_pairing_state.json"


def _migrate_legacy_state() -> None:
    if STATE_PATH.exists() or not LEGACY_STATE_PATH.exists() or STATE_PATH == LEGACY_STATE_PATH:
        return
    try:
        STATE_PATH.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(LEGACY_STATE_PATH, STATE_PATH)
    except OSError:
        pass


def new_pairing_token() -> str:
    global _token, _created_at
    if _token and time.time() - _created_at <= TTL_SECONDS:
        return _token
    _token = secrets.token_urlsafe(24)
    _created_at = time.time()
    return _token


def validate_pairing_token(token: str) -> bool:
    if not token or not _token:
        return False
    if time.time() - _created_at > TTL_SECONDS:
        return False
    return secrets.compare_digest(token, _token)


def token_status() -> dict:
    if not _token:
        return {"active": False, "created_at": 0, "expires_at": 0, "expires_in": 0}
    expires_at = _created_at + TTL_SECONDS
    expires_in = max(0, int(expires_at - time.time()))
    return {
        "active": expires_in > 0,
        "created_at": _created_at,
        "expires_at": expires_at,
        "expires_in": expires_in,
    }


def _read_state() -> dict:
    _migrate_legacy_state()
    try:
        data = json.loads(STATE_PATH.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except FileNotFoundError:
        return {}
    except Exception:
        return {}


def _write_state(data: dict) -> None:
    STATE_PATH.parent.mkdir(parents=True, exist_ok=True)
    STATE_PATH.write_text(f"{json.dumps(data, ensure_ascii=False, indent=2)}\n", encoding="utf-8")


def record_pairing_success(fingerprint: str, remote_name: str = "android", remote_device_id: int = 1) -> dict:
    previous = _read_state()
    now = time.time()
    previous_fingerprint = str(previous.get("identity_fingerprint", ""))
    identity_changed = bool(previous.get("paired") and previous_fingerprint and previous_fingerprint != fingerprint)
    data = {
        "paired": True,
        "paired_at": now,
        "updated_at": now,
        "remote_name": remote_name or "android",
        "remote_device_id": remote_device_id,
        "identity_fingerprint": fingerprint or "",
        "previous_identity_fingerprint": previous_fingerprint if identity_changed else previous.get("previous_identity_fingerprint", ""),
        "replacement_count": int(previous.get("replacement_count", 0)) + (1 if identity_changed else 0),
    }
    _write_state(data)
    return data


def clear_pairing_state() -> dict:
    data = {
        "paired": False,
        "paired_at": 0,
        "updated_at": time.time(),
        "remote_name": "",
        "remote_device_id": 0,
        "identity_fingerprint": "",
        "previous_identity_fingerprint": "",
        "replacement_count": 0,
    }
    _write_state(data)
    return data


def is_paired() -> bool:
    return bool(_read_state().get("paired"))


def pairing_status() -> dict:
    state = _read_state()
    token = token_status()
    paired = bool(state.get("paired"))
    return {
        "paired": paired,
        "state": "paired" if paired else ("waiting_for_scan" if token["active"] else "not_paired"),
        "paired_at": state.get("paired_at", 0),
        "updated_at": state.get("updated_at", 0),
        "remote_name": state.get("remote_name", ""),
        "remote_device_id": state.get("remote_device_id", 0),
        "identity_fingerprint": state.get("identity_fingerprint", ""),
        "identity_fingerprint_short": str(state.get("identity_fingerprint", ""))[:16],
        "previous_identity_fingerprint": state.get("previous_identity_fingerprint", ""),
        "replacement_count": int(state.get("replacement_count", 0)),
        "token": token,
    }
