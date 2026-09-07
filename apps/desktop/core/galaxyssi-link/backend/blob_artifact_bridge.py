"""Lazy production output lifecycle and durable, strictly scoped control callbacks."""
from __future__ import annotations

import logging
import json
from pathlib import Path
import re
import threading
import time
import uuid

from blob_artifact_journal import validate_job
from blob_protocol import BlobError, canonical, checked_hex, sha256
from secure_state import write_secure_json

log = logging.getLogger(__name__)
_lock = threading.RLock()
_runtimes = {}
_creation_locks = {}
_starters = {}
_INCIDENT_PURPOSE = "blob.artifact-incident.v1"


def _root(bridge) -> Path:
    return Path(bridge.DATA_DIR).resolve() / "blob-output"


def _code(value: str) -> str:
    return value if isinstance(value, str) and re.fullmatch(r"[a-z][a-z0-9_]{0,95}", value) else "artifact_blob_transfer_failed"


def _control_id(identity: str) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "galaxyssi:artifact-control:" + identity))


def current_peer(bridge, body: dict) -> dict:
    manifest = body["manifest"]
    peer = bridge.get_client(manifest["client_route_id"])
    if (not peer or peer.get("revoked_at") or peer.get("revoked")
            or bridge.desktop_id() != manifest["desktop_id"]
            or peer.get("client_route_id") != manifest["client_route_id"]
            or peer.get("signal_name") != body["source_id"]
            or peer.get("identity_fingerprint") != body["peer_fingerprint"]
            or peer.get("local_identity_fingerprint") != body["local_fingerprint"]):
        raise BlobError("artifact_blob_identity_changed", 409)
    return peer


class BlobArtifactRuntime:
    def __init__(self, bridge):
        from blob_artifact_sender import BlobArtifactSender
        from blob_artifact_source import open_source, commit_receipt
        self.bridge = bridge
        self.root = _root(bridge)
        self.sender = BlobArtifactSender(self.root, settings=self.settings, open_source=open_source,
            publish_offer=self.publish_offer, commit_receipt=commit_receipt,
            observe_failure=self.observe_failure, observe_quarantine=self.observe_quarantine, publish_batch=self.publish_batch,
            observe_stored=self.observe_stored)

    def peer(self, body: dict) -> dict:
        return current_peer(self.bridge, body)

    def settings(self, body: dict) -> dict:
        from blob_pair_configuration import private_settings, can_receive_artifacts
        peer = self.peer(body)
        route = body["manifest"]["client_route_id"]
        settings = private_settings(self.bridge, route)
        return {**settings, "enabled": settings["enabled"] and can_receive_artifacts(self.bridge, route),
                "source_id": peer["signal_name"], "peer_fingerprint": peer["identity_fingerprint"],
                "local_fingerprint": peer["local_identity_fingerprint"]}

    def _publish(self, body: dict, payload: dict, identity: str, *, message_id: str | None = None) -> bool:
        # UUIDs remain stable across process restart and unchanged offer retries.
        # New transport revisions deliberately use a distinct envelope identity.
        message_id = str(uuid.UUID(message_id)) if message_id is not None else _control_id(identity)
        manifest = body["manifest"]
        payload = {**payload, "message_id": message_id,
                   **{key: manifest[key] for key in ("conversation_id", "source_message_id", "client_route_id",
                       "task_id", "turn_id", "execution_generation", "desktop_id", "contact_id", "peer_chat")}}
        with self.bridge.phone_publish_lock:
            peer = self.peer(body)
            if self.bridge.outbound_status(manifest["client_route_id"], message_id) == "failed":
                raise BlobError("artifact_blob_control_delivery_exhausted", 503)
            # The existing implementation persists Signal ciphertext before
            # attempting MQTT publication, even when no broker is connected.
            self.bridge._publish_to_registered_client(self.bridge.client, peer, payload, "control", durable=True)
        return True

    def publish_offer(self, body: dict, payload: dict) -> bool:
        from blob_artifact_contract import validate_offer
        body = validate_job(body)
        manifest = body["manifest"]
        offer = validate_offer(payload, manifest["client_route_id"], manifest["desktop_id"], body["origin"])
        if offer["manifest"] != manifest:
            raise BlobError("artifact_blob_manifest_mismatch", 409)
        return self._publish(body, offer, f"offer:{manifest['transfer_id']}:{offer['transport_revision']}")

    def publish_batch(self, batch: dict) -> bool:
        from agent_task_result_archive import archive
        from blob_artifact_publication import validate_publication
        payload = validate_publication(batch["publication"], batch["bodies"])
        if payload.get("peer_chat"):
            from blob_artifact_peer import publish_batch
            return publish_batch(self, batch)
        identity = f"result:{batch['id']}"
        payload["message_id"] = _control_id(identity)
        payload["peer_chat"] = False
        payload.pop("result_recovery", None)
        digest = sha256(json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
        receipt = archive.put(payload)
        if receipt is None or receipt.get("sha256") != digest:
            raise BlobError("artifact_blob_publication_conflict", 409)
        payload["result_recovery"] = receipt
        return self._publish(batch["bodies"][0], payload, identity)

    def observe_failure(self, body: dict, code: str) -> bool:
        body, code = validate_job(body), _code(code)
        manifest = body["manifest"]
        payload = {"type": "artifact_download_failed", "blob_publication": True, "error_code": code,
                   **{key: manifest[key] for key in ("artifact_id", "artifact_uri", "name", "mime_type",
                       "size_bytes", "sha256", "transfer_id")}}
        incident_id = sha256(canonical({"transfer_id": manifest["transfer_id"], "code": code}))
        self._incident(incident_id, {"type": "artifact_delivery_failed", "code": code, "manifest": manifest})
        if manifest["peer_chat"]:
            from blob_artifact_peer import observe_failure
            if not observe_failure(self, body):
                return True
        try:
            self.peer(body)
        except BlobError:
            # A revoked/replaced identity must not receive an old task's error.
            # The encrypted local incident and failed job remain inspectable.
            return True
        return self._publish(body, payload, f"failure:{manifest['transfer_id']}:{code}")

    def observe_stored(self, body: dict) -> bool:
        body = validate_job(body)
        if body["manifest"]["peer_chat"]:
            from blob_artifact_peer import observe_stored
            return observe_stored(self, body)
        return True

    def observe_quarantine(self, incident_id: str, code: str) -> bool:
        self._incident(checked_hex(incident_id), {"type": "artifact_checkpoint_quarantined", "code": _code(code)})
        log.warning("Artifact output checkpoint quarantined code=%s", _code(code))
        return True

    def _incident(self, incident_id: str, payload: dict):
        path = self.root / "incidents" / f"{incident_id}.secure.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        write_secure_json(path, {"version": 1, "id": incident_id, **payload}, purpose=_INCIDENT_PURPOSE)


def _get(bridge) -> BlobArtifactRuntime:
    root = _root(bridge)
    with _lock:
        creation_lock = _creation_locks.setdefault(root, threading.Lock())
    # Database initialization must not hold the lock used by MQTT receipt wakeups.
    with creation_lock:
        with _lock:
            runtime = _runtimes.get(root)
        if runtime is None:
            runtime = BlobArtifactRuntime(bridge)
            with _lock:
                _runtimes[root] = runtime
        return runtime


def enqueue(bridge, bodies: list[dict], *, retain_on_desktop: bool = False, publication: dict | None = None) -> str:
    from blob_artifact_batches import validate_batch_bodies
    bodies = validate_batch_bodies(bodies)
    runtime = _get(bridge)
    for body in bodies:
        runtime.sender._settings(body)
    batch = runtime.sender.enqueue_batch(bodies, retain_on_desktop=retain_on_desktop, publication=publication)
    if not runtime.sender.start():
        start(bridge)
    return batch


def start(bridge):
    root = _root(bridge)
    if not (root / "artifact-jobs.sqlite3").is_file():
        return
    with _lock:
        previous = _starters.get(root)
        if previous and previous[0].is_alive() and not previous[1].is_set():
            return
        stopped = threading.Event()

        def initialize():
            while not stopped.is_set():
                try:
                    runtime = _get(bridge)
                    with _lock:
                        if stopped.is_set():
                            return
                        if runtime.sender.start():
                            return
                    # A preceding stop may still be joining an HTTP worker.
                    # Never replace it or recover its live SQLite claims.
                    stopped.wait(.2)
                except Exception as error:
                    log.warning("Artifact output initialization deferred class=%s", type(error).__name__)
                    stopped.wait(2)

        thread = threading.Thread(target=initialize, name="galaxyssi-blob-output-init", daemon=True)
        _starters[root] = (thread, stopped)
        thread.start()


def wake(bridge):
    # Receipts do not create stores or bulk workers. Startup owns recovery.
    with _lock:
        runtime = _runtimes.get(_root(bridge))
        if runtime is not None:
            runtime.sender.wake()


def stop(bridge):
    with _lock:
        root = _root(bridge)
        starter = _starters.get(root)
        if starter is not None:
            starter[1].set()
        runtime = _runtimes.get(root)
        if runtime is not None:
            runtime.sender.stop()


def wait_stopped(bridge, timeout: float) -> bool:
    deadline = time.monotonic() + max(0, timeout)
    with _lock:
        starter = _starters.get(_root(bridge))
    if starter is not None:
        starter[0].join(max(0, deadline - time.monotonic()))
        if starter[0].is_alive():
            return False
    with _lock:
        runtime = _runtimes.get(_root(bridge))
    return runtime is None or runtime.sender.wait_stopped(max(0, deadline - time.monotonic()))
