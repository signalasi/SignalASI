"""Local client for the SignalASI Link Protocol sidecar."""
from __future__ import annotations

import json
import os
import subprocess
import time
import urllib.error
import urllib.request
import socket
import threading
from pathlib import Path
from typing import Any

SIDECAR_PORT = int(os.environ.get("SIGNALASI_LINK_PORT", os.environ.get("HERMES_SIGNAL_PORT", "18766")))
SIDECAR_BASE = f"http://127.0.0.1:{SIDECAR_PORT}"
ROOT = Path(__file__).resolve().parent
SIDECAR_DIR = ROOT / "signal_sidecar"
SIDECAR_BIN_DIR = SIDECAR_DIR / "build" / "install" / "signalasi-link-sidecar" / "bin"
SIDECAR_SCRIPT = SIDECAR_BIN_DIR / ("signalasi-link-sidecar.bat" if os.name == "nt" else "signalasi-link-sidecar")

_process: subprocess.Popen | None = None
_peer_locks: dict[tuple[str, int], threading.RLock] = {}
_peer_locks_guard = threading.Lock()


def _peer_lock(remote_name: str, remote_device_id: int) -> threading.RLock:
    key = (remote_name, int(remote_device_id))
    with _peer_locks_guard:
        return _peer_locks.setdefault(key, threading.RLock())


def start_signal_sidecar() -> None:
    """Start the local JVM sidecar if it is not already responding."""
    global _process, SIDECAR_PORT, SIDECAR_BASE
    if _is_healthy():
        return
    if not SIDECAR_SCRIPT.exists():
        raise FileNotFoundError(f"Signal sidecar is not built: {SIDECAR_SCRIPT}")

    if _port_is_in_use(SIDECAR_PORT):
        SIDECAR_PORT = _available_local_port()
        SIDECAR_BASE = f"http://127.0.0.1:{SIDECAR_PORT}"

    out = open(SIDECAR_DIR / "sidecar.out.log", "ab", buffering=0)
    err = open(SIDECAR_DIR / "sidecar.err.log", "ab", buffering=0)
    popen_kwargs = {
        "cwd": str(SIDECAR_DIR),
        "stdout": out,
        "stderr": err,
        "env": {**os.environ, "SIGNALASI_LINK_PORT": str(SIDECAR_PORT)},
    }
    if os.name == "nt":
        popen_kwargs["creationflags"] = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    _process = subprocess.Popen([str(SIDECAR_SCRIPT)], **popen_kwargs)
    deadline = time.time() + 15
    while time.time() < deadline:
        if _is_healthy():
            return
        time.sleep(0.25)
    raise RuntimeError("Signal sidecar did not become healthy")


def stop_signal_sidecar() -> None:
    """Stop only the sidecar process started by this backend instance."""
    global _process
    process = _process
    _process = None
    if process is None or process.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(
            ["taskkill", "/PID", str(process.pid), "/T", "/F"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        return
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


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
    with _peer_lock(remote_name, remote_device_id):
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
    with _peer_lock(remote_name, remote_device_id):
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
    with _peer_lock(remote_name, remote_device_id):
        return _request("POST", "/replace-peer", {
            "remoteName": remote_name,
            "remoteDeviceId": remote_device_id,
            "bundle": bundle,
        })


def remove_peer_signal_session(remote_name: str, remote_device_id: int = 1) -> dict[str, Any]:
    start_signal_sidecar()
    with _peer_lock(remote_name, remote_device_id):
        return _request("POST", "/remove-peer", {
            "remoteName": remote_name,
            "remoteDeviceId": remote_device_id,
        })


def _is_healthy() -> bool:
    try:
        status = _request("GET", "/health")
        return bool(
            status.get("ok")
            and status.get("protocol") == "signalasi-link"
            and int(status.get("apiVersion") or 0) == 1
            and status.get("removePeer") is True
        )
    except Exception:
        return False


def _port_is_in_use(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.settimeout(0.25)
        return probe.connect_ex(("127.0.0.1", int(port))) == 0


def _available_local_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", 0))
        return int(probe.getsockname()[1])


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
