"""Prepare scoped artifact links and a recoverable logical reply before sending."""
from __future__ import annotations

import json
import uuid

from blob_artifact_contract import make_scoped_manifest
from blob_protocol import BlobError, canonical

_SCOPE = ("task_id", "turn_id", "execution_generation", "conversation_id", "client_route_id",
          "desktop_id", "contact_id", "source_message_id")


def prepare_for_route(bridge, route: str, task_id: str, output_files: list, *, compress_images=True) -> list:
    import sqlite3
    from artifact_delivery import prepare_artifacts, MAX_ARTIFACT_BYTES
    from blob_pair_configuration import can_receive_artifacts, private_settings
    from blob_protocol import MAX_FILE_BYTES
    from secure_state import SecureStateError
    if not output_files:
        return []
    try:
        enabled = bool(route and bridge.get_client(route) and can_receive_artifacts(bridge, route)
                       and private_settings(bridge, route)["enabled"])
        return prepare_artifacts(task_id, output_files, compress_images=compress_images, strict=True,
                                 maximum_bytes=MAX_FILE_BYTES if enabled else MAX_ARTIFACT_BYTES)
    except OSError:
        raise BlobError("artifact_source_unavailable", 409) from None
    except (SecureStateError, sqlite3.Error):
        raise BlobError("artifact_blob_checkpoint_unavailable", 503) from None


def validate_publication(value: dict | None, bodies: list[dict]) -> dict | None:
    if value is None:
        return None
    if not isinstance(value, dict) or not bodies:
        raise BlobError("invalid_artifact_blob_publication")
    manifest = bodies[0]["manifest"]
    if (not isinstance(value.get("content", ""), str)
            or any(value.get(key) != manifest[key] for key in _SCOPE)
            or type(value.get("execution_generation")) is not int
            or value.get("peer_chat", False) is not manifest["peer_chat"]):
        raise BlobError("artifact_blob_publication_scope_mismatch", 409)
    if manifest["peer_chat"]:
        _validate_peer(value, bodies)
    elif value.get("type") != "text" or value.get("task_status") != "completed":
        raise BlobError("artifact_blob_publication_scope_mismatch", 409)
    return json.loads(canonical(value))


def _validate_peer(value, bodies):
    manifest = bodies[0]["manifest"]
    try:
        if set(value) - set(_SCOPE) - {"type", "message_id", "desktop_name", "content", "attachments", "sender",
                                      "time", "duration_ms", "peer_chat", "_local_message"}:
            raise ValueError()
        message_id = str(uuid.UUID(value["message_id"]))
        if (value["type"] != "peer_message" or value["message_id"] != message_id
                or message_id != manifest["task_id"] or message_id != manifest["source_message_id"]
                or manifest["contact_id"] != manifest["desktop_id"]
                or manifest["conversation_id"] != f"peer:{manifest['client_route_id']}"):
            raise ValueError()
        attachments, local = value["attachments"], value["_local_message"]
        if (not isinstance(attachments, list) or len(attachments) != len(bodies)
                or set(local) != {"sender_name", "created_at_ms", "attachments"}
                or not isinstance(local["sender_name"], str) or type(local["created_at_ms"]) is not int
                or local["created_at_ms"] <= 0 or len(local["attachments"]) != len(bodies)):
            raise ValueError()
        by_id = {body["manifest"]["artifact_id"]: body["manifest"] for body in bodies}
        seen = set()
        for item, stored in zip(attachments, local["attachments"]):
            expected = by_id[item["artifact_id"]]
            if item["artifact_id"] in seen:
                raise ValueError()
            seen.add(item["artifact_id"])
            fields = ("artifact_uri", "name", "mime_type", "size_bytes", "sha256")
            if (set(item) - set(fields) - {"artifact_id", "transfer_id", "duration_ms"}
                    or any(item[key] != expected[key] or stored[key] != item[key] for key in fields)
                    or type(item["size_bytes"]) is not int or type(stored["size_bytes"]) is not int
                    or item.get("transfer_id") != expected["transfer_id"]
                    or type(item.get("duration_ms", 0)) is not int or not 0 <= item.get("duration_ms", 0) <= 3_600_000
                    or stored.get("duration_ms", 0) != item.get("duration_ms", 0)
                    or not isinstance(stored.get("local_path"), str) or not stored["local_path"]):
                raise ValueError()
    except (KeyError, TypeError, ValueError, AttributeError):
        raise BlobError("invalid_artifact_blob_peer_publication", 409) from None


def prepare_result(bridge, peer: dict, artifacts: list, payload: dict, origin: str, *, peer_chat=False) -> tuple[list[dict], dict]:
    from blob_artifact_source import prepare_source
    bodies, replacements = [], {}
    for artifact in artifacts:
        metadata = {key: getattr(artifact, key) for key in (
            "artifact_id", "artifact_uri", "task_id", "name", "relative_path", "mime_type", "size_bytes",
            "sha256", "original_size_bytes", "original_sha256")}
        if payload.get("task_id") != artifact.task_id:
            raise BlobError("artifact_blob_publication_scope_mismatch", 409)
        manifest = make_scoped_manifest({**metadata, **{key: payload.get(key) for key in _SCOPE}, "peer_chat": peer_chat})
        if (manifest["desktop_id"] != bridge.desktop_id()
                or manifest["client_route_id"] != peer["client_route_id"]):
            raise BlobError("artifact_blob_publication_scope_mismatch", 409)
        bodies.append({"manifest": manifest, "source_relative": prepare_source(artifact, manifest),
            "source_id": peer["signal_name"], "peer_fingerprint": peer["identity_fingerprint"],
            "local_fingerprint": peer["local_identity_fingerprint"], "origin": origin})
        replacements[artifact.artifact_uri] = manifest["artifact_uri"]

    def card_metadata(manifest):
        from rich_output import _human_size
        return {"transport": "encrypted-blob", "artifact_source_uri": manifest["artifact_uri"],
                "artifact_id": manifest["artifact_id"], "transfer_id": manifest["transfer_id"],
                **{f"blob_{key}": str(manifest[key]) for key in (
                    "client_route_id", "desktop_id", "conversation_id", "task_id", "turn_id", "execution_generation")},
                "size": _human_size(manifest["size_bytes"]),
                "size_bytes": str(manifest["size_bytes"]), "sha256": manifest["sha256"],
                "original_size_bytes": str(manifest["original_size_bytes"]),
                "original_sha256": manifest["original_sha256"]}

    def rewrite(value):
        if isinstance(value, dict):
            result = {key: rewrite(item) for key, item in value.items()}
            if value.get("artifact_uri") in replacements and "artifact_id" in value:
                result["transfer_id"] = next(body["manifest"]["transfer_id"] for body in bodies
                                             if body["manifest"]["artifact_uri"] == result["artifact_uri"])
            if value.get("uri") in replacements:
                manifest = next(body["manifest"] for body in bodies
                                if body["manifest"]["artifact_uri"] == result["uri"])
                protected = card_metadata(manifest)
                result["metadata"] = {**protected, **{key: item for key, item in (result.get("metadata") or {}).items()
                                                       if key not in protected}}
            if value.get("type") == "gallery":
                uris = {row[0] for row in result.get("rows", []) if isinstance(row, list)
                        and row and isinstance(row[0], str)}
                if isinstance(result.get("uri"), str):
                    uris.add(result["uri"])
                items = {"blob_item_" + body["manifest"]["artifact_uri"].rsplit("/", 1)[1]:
                         json.dumps(card_metadata(body["manifest"]), separators=(",", ":"), ensure_ascii=False)
                         for body in bodies if body["manifest"]["artifact_uri"] in uris}
                if items:
                    result["metadata"] = {**items, **{key: item for key, item in (result.get("metadata") or {}).items()
                                                     if key not in items}}
            if value.get("uri") in replacements or value.get("type") == "gallery":
                from rich_output import _bounded_metadata
                metadata = result.get("metadata")
                if isinstance(metadata, dict):
                    uris = ([result["uri"]] if result.get("uri") else []) + [
                        row[0] for row in result.get("rows", []) if isinstance(row, list) and row]
                    result["metadata"] = _bounded_metadata(metadata, uris)
            return result
        if isinstance(value, list):
            return [rewrite(item) for item in value]
        if isinstance(value, str):
            if value in replacements:
                return replacements[value]
            # Markdown may also contain a link to a prepared artifact. Only
            # rewrite exact link destinations, never arbitrary path substrings.
            for old, new in replacements.items():
                value = value.replace(f"]({old})", f"]({new})")
            return value
        return value

    reply = validate_publication(rewrite(payload), bodies)
    return bodies, reply


def enqueue_result(bridge, wire: dict, artifacts: list, payload: dict, *, retain_on_desktop: bool,
                   expected_binding: dict | None = None) -> bool:
    if not artifacts:
        return False
    from blob_pair_configuration import can_receive_artifacts, private_settings
    route = str(wire.get("_client_route_id") or "")
    peer = dict(bridge.get_client(route) or {})
    if expected_binding is not None and (not peer or any(peer.get(key) != expected_binding.get(key)
            for key in ("signal_name", "identity_fingerprint", "local_identity_fingerprint"))):
        raise BlobError("artifact_blob_identity_changed", 409)
    if peer is None or not can_receive_artifacts(bridge, route):
        return False
    settings = private_settings(bridge, route)
    if not settings["enabled"]:
        return False
    if payload.get("client_route_id") != route:
        raise BlobError("artifact_blob_publication_scope_mismatch", 409)
    bodies, reply = prepare_result(bridge, peer, artifacts, payload, settings["origin"])
    from blob_artifact_bridge import enqueue
    enqueue(bridge, bodies, retain_on_desktop=retain_on_desktop, publication=reply)
    return True


def publish_result(bridge, mqttc, wire: dict, artifacts: list, payload: dict, *, retain_on_desktop: bool,
                   expected_binding: dict | None = None):
    if enqueue_result(bridge, wire, artifacts, payload, retain_on_desktop=retain_on_desktop,
                      expected_binding=expected_binding):
        return
    from artifact_delivery import register_artifact_batch, MAX_ARTIFACT_BYTES
    if any(artifact.size_bytes > MAX_ARTIFACT_BYTES for artifact in artifacts):
        raise BlobError("artifact_blob_transport_required", 409)
    register_artifact_batch(artifacts, client_route_id=payload["client_route_id"], retain_on_desktop=retain_on_desktop)
    common = {key: payload[key] for key in ("source_message_id", "conversation_id", "turn_id", "contact_id",
                                           "agent_id", "desktop_id", "desktop_name") if key in payload}
    bridge._publish_task_artifacts(mqttc, wire, artifacts, common=common)
    bridge._publish_or_queue_task_result(mqttc, wire, payload)
