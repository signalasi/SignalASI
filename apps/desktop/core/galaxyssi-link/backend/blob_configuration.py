"""Versioned relay settings sent only to a paired, explicitly Blob-aware client."""
from __future__ import annotations

import os
from pathlib import Path
import threading
import time

from blob_client import relay_origin
from blob_protocol import checked_hex
from secure_state import read_secure_json, write_secure_json

_lock = threading.Lock()
_PURPOSE = "blob.relay-configuration.v1"


def configuration(path: Path, *, environ=None) -> dict:
    environment = os.environ if environ is None else environ
    origin = environment.get("GALAXYSSI_BLOB_RELAY_URL", "")
    token = environment.get("GALAXYSSI_BLOB_PROVISION_TOKEN", "")
    enabled = bool(origin and token)
    value = {"enabled": enabled, "origin": relay_origin(origin) if enabled else "",
             "provisioning_token": checked_hex(token) if enabled else ""}
    with _lock:
        previous = read_secure_json(path, purpose=_PURPOSE).value if path.exists() else {}
        if previous and all(previous.get(key) == item for key, item in value.items()):
            return previous
        value["revision"] = max(int(previous.get("revision", 0)) + 1, int(time.time() * 1000))
        write_secure_json(path, value, purpose=_PURPOSE)
        return value


def publish_configuration(bridge, mqttc, route: str) -> bool:
    peer = bridge.get_client(route)
    if not peer:
        return False
    settings = configuration(bridge.DATA_DIR / "blob-relay-configuration.secure.json")
    payload = {"type": "blob_relay_config", "version": 1, **settings,
               "desktop_id": bridge.desktop_id(), "client_route_id": route,
               "desktop_fingerprint": peer["local_identity_fingerprint"]}
    bridge._publish_to_registered_client(mqttc, peer, payload, "control", durable=True)
    return True
