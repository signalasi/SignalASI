"""Local client for the GalaxySSI Link Protocol sidecar."""
from __future__ import annotations

import json
import os
import subprocess
import time
import base64
import urllib.error
import urllib.request
import socket
import threading
from pathlib import Path
from typing import Any

from secure_state import derived_storage_key

SIDECAR_PORT = int(os.environ.get("GALAXYSSI_LINK_PORT", os.environ.get("HERMES_SIGNAL_PORT", "18766")))
SIDECAR_BASE = f"http://127.0.0.1:{SIDECAR_PORT}"
ROOT = Path(__file__).resolve().parent
SIDECAR_DIR = ROOT / "signal_sidecar"
SIDECAR_BIN_DIR = SIDECAR_DIR / "build" / "install" / "galaxyssi-link-sidecar" / "bin"
SIDECAR_SCRIPT = SIDECAR_BIN_DIR / ("galaxyssi-link-sidecar.bat" if os.name == "nt" else "galaxyssi-link-sidecar")
DEFAULT_DATA_DIR = (
    Path(os.environ["APPDATA"]) / "galaxyssi-desktop" / "runtime"
    if os.name == "nt" and os.environ.get("APPDATA")
    else Path.home() / ".galaxyssi"
)
SIGNAL_STORE_PATH = Path(
    os.environ.get(
        "GALAXYSSI_SIGNAL_STORE_PATH",
        str(Path(os.environ.get("GALAXYSSI_DATA_DIR", DEFAULT_DATA_DIR)) / "signal_protocol_store.json"),
    )
)

_process: subprocess.Popen | None = None
_startup_lock = threading.RLock()
_peer_locks: dict[tuple[str, int], threading.RLock] = {}
_peer_locks_guard = threading.Lock()


def _peer_lock(remote_name: str, remote_device_id: int) -> threading.RLock:
    key = (remote_name, int(remote_device_id))
    with _peer_locks_guard:
        return _peer_locks.setdefault(key, threading.RLock())


def sidecar_script_candidates() -> list[Path]:
    """Return trusted sidecar runtimes in deterministic preference order."""
    script_name = "galaxyssi-link-sidecar.bat" if os.name == "nt" else "galaxyssi-link-sidecar"
    relative = Path("signal_sidecar") / "build" / "install" / "galaxyssi-link-sidecar" / "bin" / script_name
    configured = os.environ.get("GALAXYSSI_LINK_SIDECAR_SCRIPT", "").strip()
    workspace = Path(
        os.environ.get(
            "GALAXYSSI_WORKSPACE_ROOT",
            str(Path.home() / "GalaxySSI_Workspace" / "GalaxySSI"),
        )
    ).expanduser()
    candidates = []
    if configured:
        candidates.append(Path(configured).expanduser())
    candidates.append(SIDECAR_SCRIPT)
    candidates.extend(
        [
            workspace / "apps" / "desktop" / "core" / "galaxyssi-link" / "backend" / relative,
            workspace / "apps" / "desktop" / "dist" / "GalaxySSI Desktop-win-x64" / "resources" /
            "galaxyssi-link" / "backend" / relative,
        ]
    )
    if os.name == "nt" and os.environ.get("LOCALAPPDATA"):
        candidates.append(
            Path(os.environ["LOCALAPPDATA"]) / "Programs" / "GalaxySSI Desktop" / "resources" /
            "galaxyssi-link" / "backend" / relative
        )
    unique: list[Path] = []
    seen: set[str] = set()
    for candidate in candidates:
        normalized = str(candidate.resolve(strict=False)).lower() if os.name == "nt" else str(candidate.resolve(strict=False))
        if normalized in seen:
            continue
        seen.add(normalized)
        unique.append(candidate)
    return unique


def resolve_sidecar_script() -> Path | None:
    return next((candidate for candidate in sidecar_script_candidates() if candidate.exists()), None)


def start_signal_sidecar() -> None:
    """Start the local JVM sidecar if it is not already responding."""
    global _process, SIDECAR_PORT, SIDECAR_BASE
    with _startup_lock:
        if _is_healthy():
            return

        stale_process = _process
        if stale_process is not None and stale_process.poll() is None:
            _terminate_process(stale_process)
        _process = None

        sidecar_script = resolve_sidecar_script()
        if sidecar_script is None:
            checked = "; ".join(str(candidate) for candidate in sidecar_script_candidates())
            raise FileNotFoundError(f"Signal sidecar runtime was not found. Checked: {checked}")

        if _port_is_in_use(SIDECAR_PORT):
            SIDECAR_PORT = _available_local_port()
            SIDECAR_BASE = f"http://127.0.0.1:{SIDECAR_PORT}"

        with open(SIDECAR_DIR / "sidecar.out.log", "ab", buffering=0) as out, \
                open(SIDECAR_DIR / "sidecar.err.log", "ab", buffering=0) as err:
            storage_key = base64.urlsafe_b64encode(
                derived_storage_key(
                    SIGNAL_STORE_PATH,
                    "signal-protocol-store",
                )
            ).decode("ascii").rstrip("=")
            popen_kwargs = {
                "cwd": str(SIDECAR_DIR),
                "stdout": out,
                "stderr": err,
                "env": {
                    **os.environ,
                    "GALAXYSSI_LINK_PORT": str(SIDECAR_PORT),
                    "GALAXYSSI_LINK_STORE_PATH": str(SIGNAL_STORE_PATH),
                    "GALAXYSSI_LINK_STORAGE_KEY": storage_key,
                },
            }
            if os.name == "nt":
                popen_kwargs["creationflags"] = getattr(subprocess, "CREATE_NO_WINDOW", 0)
            process = subprocess.Popen([str(sidecar_script)], **popen_kwargs)
            _process = process

        deadline = time.time() + 15
        while time.time() < deadline:
            if process.poll() is not None:
                break
            if _is_healthy():
                return
            time.sleep(0.25)

        _terminate_process(process)
        if _process is process:
            _process = None
        raise RuntimeError("Signal sidecar did not become healthy")


def stop_signal_sidecar() -> None:
    """Stop only the sidecar process started by this backend instance."""
    global _process
    with _startup_lock:
        process = _process
        if process is None or process.poll() is not None:
            _process = None
            return
        _terminate_process(process)
        _process = None


def _terminate_process(process: subprocess.Popen) -> None:
    """Terminate a sidecar process and its launcher without leaving an orphan JVM."""
    if process.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(
            ["taskkill", "/PID", str(process.pid), "/T", "/F"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        # Reap the launcher too; taskkill returning does not update Popen.returncode.
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            raise RuntimeError("Signal sidecar process did not stop") from None
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


def sign_signal_identity(payload: bytes) -> dict[str, str]:
    """Sign bounded application data without exporting the Signal identity key."""
    if not payload or len(payload) > 1024 * 1024:
        raise ValueError("Signing payload must contain 1 to 1048576 bytes")
    start_signal_sidecar()
    response = _request("POST", "/sign", {
        "payload": base64.b64encode(payload).decode("ascii"),
    })
    return {
        "signer_id": str(response["signerId"]),
        "signature_key_id": str(response["signatureKeyId"]).lower(),
        "signature": str(response["signature"]),
    }


def verify_signal_identity(payload: bytes, signature: str) -> bool:
    """Verify data against this Desktop installation's public identity."""
    if not payload or len(payload) > 1024 * 1024 or not signature:
        return False
    start_signal_sidecar()
    response = _request("POST", "/verify", {
        "payload": base64.b64encode(payload).decode("ascii"),
        "signature": signature,
    })
    return bool(response.get("valid"))


def desktop_name() -> str:
    from device_identity import desktop_device_profile

    bundle = get_signal_bundle()
    return str(desktop_device_profile(str(bundle.get("identityKeySha256") or ""))["display_name"])


def desktop_id() -> str:
    bundle = get_signal_bundle()
    return f"desktop_{str(bundle.get('identityKeySha256', 'unknown'))[:16]}"


def get_signal_verification_payload() -> dict[str, Any]:
    from device_identity import desktop_device_profile

    bundle = get_signal_bundle()
    device_profile = desktop_device_profile(str(bundle.get("identityKeySha256") or ""))
    return {
        "type": "galaxyssi_verify",
        "version": 1,
        "device": "pc",
        "desktop_id": f"desktop_{str(bundle.get('identityKeySha256', 'unknown'))[:16]}",
        "desktop_name": desktop_name(),
        "desktop_display_name": device_profile["display_name"],
        "desktop_device": device_profile,
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
        status = _request("GET", "/health", timeout=0.5)
        return bool(
            status.get("ok")
            and status.get("protocol") == "galaxyssi-link"
            and int(status.get("apiVersion") or 0) == 1
            and status.get("removePeer") is True
            and status.get("identitySigning") is True
            and status.get("encryptedStorage") is True
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


def _request(method: str, path: str, payload: dict[str, Any] | None = None, *, timeout: float = 20) -> dict[str, Any]:
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        SIDECAR_BASE + path,
        data=data,
        method=method,
        headers={"Content-Type": "application/json; charset=utf-8"},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Signal sidecar HTTP {exc.code}: {body}") from exc
