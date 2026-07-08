"""Local client for the SignalASI Link Protocol sidecar."""
from __future__ import annotations

import json
import os
import subprocess
import time
import urllib.error
import urllib.request
import socket
from pathlib import Path
from typing import Any

SIDECAR_PORT = int(os.environ.get("SIGNALASI_LINK_PORT", os.environ.get("HERMES_SIGNAL_PORT", "18766")))
SIDECAR_BASE = f"http://127.0.0.1:{SIDECAR_PORT}"
ROOT = Path(__file__).resolve().parent
SIDECAR_DIR = ROOT / "signal_sidecar"
SIDECAR_SCRIPT = SIDECAR_DIR / "build" / "install" / "signalasi-link-sidecar" / "bin" / "signalasi-link-sidecar.bat"

_process: subprocess.Popen | None = None


def start_signal_sidecar() -> None:
    """Start the local JVM sidecar if it is not already responding."""
    global _process
    if _is_healthy():
        return
    if not SIDECAR_SCRIPT.exists():
        raise FileNotFoundError(f"Signal sidecar is not built: {SIDECAR_SCRIPT}")

    out = open(SIDECAR_DIR / "sidecar.out.log", "ab", buffering=0)
    err = open(SIDECAR_DIR / "sidecar.err.log", "ab", buffering=0)
    _process = subprocess.Popen(
        [str(SIDECAR_SCRIPT)],
        cwd=str(SIDECAR_DIR),
        stdout=out,
        stderr=err,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )
    deadline = time.time() + 15
    while time.time() < deadline:
        if _is_healthy():
            return
        time.sleep(0.25)
    raise RuntimeError("Signal sidecar did not become healthy")


def get_signal_bundle() -> dict[str, Any]:
    start_signal_sidecar()
    return _request("GET", "/bundle")


def desktop_name() -> str:
    return os.environ.get("SIGNALASI_DESKTOP_NAME") or socket.gethostname() or "SignalASI Desktop"


def desktop_id() -> str:
    bundle = get_signal_bundle()
    return f"desktop_{str(bundle.get('identityKeySha256', 'unknown'))[:16]}"


def get_signal_verification_payload() -> dict[str, Any]:
    bundle = get_signal_bundle()
    return {
        "type": "signalasi_verify",
        "version": 1,
        "device": "pc",
        "desktop_id": f"desktop_{str(bundle.get('identityKeySha256', 'unknown'))[:16]}",
        "desktop_name": desktop_name(),
        "device_id": bundle.get("deviceId", 1),
        "identity_key": bundle["identityKey"],
        "identity_key_sha256": bundle["identityKeySha256"],
        "created_at": int(time.time()),
    }


def decrypt_signal_envelope(envelope: dict[str, Any], remote_name: str = "android", remote_device_id: int = 1) -> dict[str, Any]:
    start_signal_sidecar()
    response = _request("POST", "/decrypt", {
        "remoteName": remote_name,
        "remoteDeviceId": remote_device_id,
        "type": envelope.get("signal_type") or envelope.get("type") or "prekey",
        "messageType": envelope.get("message_type", envelope.get("messageType", -1)),
        "body": envelope["body"],
    })
    plaintext = response["plaintext"]
    return json.loads(plaintext)


def encrypt_signal_payload(payload: dict[str, Any], remote_name: str = "android", remote_device_id: int = 1) -> dict[str, Any]:
    start_signal_sidecar()
    response = _request("POST", "/encrypt", {
        "remoteName": remote_name,
        "remoteDeviceId": remote_device_id,
        "plaintext": json.dumps(payload, ensure_ascii=False),
    })
    return {
        "version": 1,
        "scheme": "signal",
        "from": desktop_id(),
        "to": remote_name,
        "signal_type": response["type"],
        "message_type": response["messageType"],
        "body": response["body"],
        "time": time.time(),
    }


def replace_peer_signal_bundle(bundle: dict[str, Any], remote_name: str = "android", remote_device_id: int = 1) -> dict[str, Any]:
    start_signal_sidecar()
    return _request("POST", "/replace-peer", {
        "remoteName": remote_name,
        "remoteDeviceId": remote_device_id,
        "bundle": bundle,
    })


def _is_healthy() -> bool:
    try:
        return bool(_request("GET", "/health").get("ok"))
    except Exception:
        return False


def _request(method: str, path: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        SIDECAR_BASE + path,
        data=data,
        method=method,
        headers={"Content-Type": "application/json; charset=utf-8"},
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Signal sidecar HTTP {exc.code}: {body}") from exc
