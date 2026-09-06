"""Device-bound Relay overrides; credentials never enter public settings responses."""
from __future__ import annotations

import hashlib
from pathlib import Path
import threading
import time

from blob_client import relay_origin
from blob_configuration import configuration
from blob_protocol import BlobError, canonical, checked_hex
from link_protocol import valid_route_id
from secure_state import read_secure_json, write_secure_json

_LOCK = threading.RLock()
_PURPOSE = "blob.pair-configuration.v1"
ARTIFACT_CAPABILITY_TYPE = "artifact_blob_capability"


def _identity(bridge, route: str) -> tuple[dict, dict]:
    if not valid_route_id(route):
        raise BlobError("invalid_client_route", 400)
    peer = bridge.get_client(route)
    if not peer:
        raise BlobError("paired_identity_unavailable", 404)
    binding = {"route": route, "source": peer["signal_name"],
               "remote": checked_hex(peer["identity_fingerprint"]),
               "local": checked_hex(peer["local_identity_fingerprint"])}
    return peer, binding


def _path(bridge, route: str) -> Path:
    return _settings_path(bridge.DATA_DIR, route)


def _settings_path(data_dir: Path, route: str) -> Path:
    digest = hashlib.sha256(route.encode("ascii")).hexdigest()
    return Path(data_dir) / "blob-pair-settings" / f"{digest}.secure.json"


def forget_settings(data_dir: Path, route: str) -> None:
    if not valid_route_id(route):
        return
    with _LOCK:
        _settings_path(data_dir, route).unlink(missing_ok=True)


def _read(bridge, route: str, binding: dict) -> dict:
    path = _path(bridge, route)
    if not path.exists():
        return {"version": 1, "binding": binding, "opted_in": False, "override": None}
    value = read_secure_json(path, purpose=_PURPOSE).value
    if value.get("version") != 1 or type(value.get("opted_in")) is not bool:
        raise BlobError("invalid_blob_pair_configuration", 500)
    if value.get("binding") != binding:
        return {"version": 1, "binding": binding, "opted_in": False, "override": None}
    return value


def _effective(bridge, doc: dict) -> dict:
    override = doc.get("override")
    if override is not None:
        if (not isinstance(override, dict) or type(override.get("revision")) is not int
                or not 1 <= override["revision"] < 2**63 or type(override.get("enabled")) is not bool):
            raise BlobError("invalid_blob_pair_configuration", 500)
        if override["enabled"]:
            if relay_origin(override.get("origin", "")) != override["origin"]:
                raise BlobError("invalid_blob_pair_configuration", 500)
            checked_hex(override.get("provisioning_token"))
        elif override.get("origin") != "" or override.get("provisioning_token") != "":
            raise BlobError("invalid_blob_pair_configuration", 500)
        return dict(override)
    return configuration(bridge.DATA_DIR / "blob-relay-configuration.secure.json")


def private_settings(bridge, route: str, *, requested: bool = False) -> dict:
    with _LOCK:
        _, binding = _identity(bridge, route)
        doc = _read(bridge, route, binding)
        if requested and not doc["opted_in"]:
            doc["opted_in"] = True
            write_secure_json(_path(bridge, route), doc, purpose=_PURPOSE)
        return _effective(bridge, doc)


def public_settings(bridge, route: str) -> dict:
    with _LOCK:
        _, binding = _identity(bridge, route)
        doc = _read(bridge, route, binding)
        value = _effective(bridge, doc)
        return {"client_route_id": route, "identity_fingerprint": binding["remote"],
                "identity_binding": hashlib.sha256(canonical(binding)).hexdigest(),
                "revision": value["revision"], "enabled": value["enabled"],
                "origin": value["origin"], "credential_present": bool(value["provisioning_token"]),
                "source": "device" if doc["override"] is not None else "environment",
                "client_opted_in": doc["opted_in"]}


def update_settings(bridge, route: str, *, identity_fingerprint: str, identity_binding: str, expected_revision: int,
                    enabled: bool, origin: str, provisioning_token: str | None) -> dict:
    with _LOCK:
        _, binding = _identity(bridge, route)
        if (identity_fingerprint != binding["remote"]
                or identity_binding != hashlib.sha256(canonical(binding)).hexdigest()):
            raise BlobError("blob_config_identity_mismatch", 409)
        doc = _read(bridge, route, binding)
        previous = _effective(bridge, doc)
        if type(expected_revision) is not int or expected_revision != previous["revision"]:
            raise BlobError("blob_config_revision_conflict", 409)
        if type(enabled) is not bool:
            raise BlobError("invalid_blob_configuration", 400)
        normalized = relay_origin(origin) if enabled else ""
        if enabled and provisioning_token is None:
            if normalized != previous["origin"]:
                raise BlobError("blob_new_origin_requires_credential", 400)
            provisioning_token = previous["provisioning_token"]
        token = checked_hex(provisioning_token) if enabled else ""
        values = {"enabled": enabled, "origin": normalized, "provisioning_token": token}
        if doc["override"] is not None and all(previous.get(k) == v for k, v in values.items()):
            return public_settings(bridge, route)
        values["revision"] = max(previous["revision"] + 1, int(time.time() * 1000))
        doc["override"] = values
        write_secure_json(_path(bridge, route), doc, purpose=_PURPOSE)
        return public_settings(bridge, route)


def can_publish(bridge, route: str) -> bool:
    with _LOCK:
        _, binding = _identity(bridge, route)
        return _read(bridge, route, binding)["opted_in"]


def _artifact_capability(value: dict) -> dict:
    if (not isinstance(value, dict) or set(value) != {"version", "revision", "enabled"}
            or type(value["version"]) is not int or value["version"] != 1
            or type(value["revision"]) is not int or not 1 <= value["revision"] <= 2**53 - 1
            or type(value["enabled"]) is not bool):
        raise BlobError("invalid_artifact_blob_capability", 400)
    return dict(value)


def record_artifact_capability(bridge, route: str, source: str, payload: dict) -> bool:
    """Persist an authenticated receiver declaration before acknowledging its message.

    Input-upload opt-in is deliberately independent from output receiver support.
    The phone increments revision when enabling/disabling its receiver; replayed
    declarations cannot undo a newer state. Pair replacement resets the binding.
    """
    if not isinstance(payload, dict) or payload.get("type") != ARTIFACT_CAPABILITY_TYPE:
        return False
    value = _artifact_capability({key: payload.get(key) for key in ("version", "revision", "enabled")})
    with _LOCK:
        _, binding = _identity(bridge, route)
        if (source != binding["source"] or payload.get("client_route_id") != route
                or payload.get("desktop_id") != bridge.desktop_id()
                or payload.get("desktop_fingerprint") != binding["local"]):
            raise BlobError("artifact_blob_capability_identity_mismatch", 409)
        doc = _read(bridge, route, binding)
        previous = doc.get("artifact_receiver")
        if previous is not None:
            previous = _artifact_capability(previous)
            if value["revision"] < previous["revision"]:
                return True
            if value["revision"] == previous["revision"]:
                if value != previous:
                    raise BlobError("artifact_blob_capability_revision_conflict", 409)
                return True
        doc["artifact_receiver"] = value
        write_secure_json(_path(bridge, route), doc, purpose=_PURPOSE)
        return True


def can_receive_artifacts(bridge, route: str) -> bool:
    with _LOCK:
        _, binding = _identity(bridge, route)
        value = _read(bridge, route, binding).get("artifact_receiver")
        return value is not None and _artifact_capability(value)["enabled"]


def origin_for_peer(bridge, route: str, source: str) -> str:
    peer, _ = _identity(bridge, route)
    if peer["signal_name"] != source:
        raise BlobError("paired_identity_unavailable", 409)
    value = private_settings(bridge, route)
    return value["origin"] if value["enabled"] else ""
