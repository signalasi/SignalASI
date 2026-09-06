"""Independent output workers; broker acknowledgements never release source files."""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import hashlib
import logging
import os
from pathlib import Path
import shutil
import threading
import time

from backend_instance_lock import BackendInstanceAlreadyRunning, BackendInstanceLock
from blob_artifact_contract import OFFER_TYPE, artifact_binding, validate_offer
from blob_artifact_journal import BlobArtifactJournal
from blob_client import BlobClient
from blob_crypto import STATE_FILE, StagedBlob
from blob_protocol import BlobError
from secure_state import SecureStateError

log = logging.getLogger(__name__)
_TERMINAL = {"source_changed", "invalid_source_file", "artifact_source_missing", "file_too_large",
             "artifact_blob_identity_changed", "artifact_blob_relay_changed", "artifact_blob_job_conflict",
             "artifact_blob_manifest_mismatch", "artifact_blob_binding_mismatch",
             "invalid_transfer_path", "invalid_transfer_lock", "transfer_binding_mismatch",
             "invalid_private_descriptor", "manifest_hash_mismatch", "local_chunk_missing_or_corrupt",
             "artifact_blob_revision_exhausted", "artifact_blob_checkpoint_invalid",
             "artifact_blob_source_lease_mismatch", "artifact_blob_source_manifest_mismatch",
             "artifact_blob_control_delivery_exhausted", "artifact_blob_publication_conflict",
             "artifact_blob_peer_source_invalid", "artifact_blob_peer_source_missing",
             "artifact_blob_peer_message_deleted", "artifact_blob_peer_message_conflict"}
_RECREATE = {"blob_not_found", "blob_expired"}


class BlobArtifactSender:
    def __init__(self, root: Path, *, settings, open_source, publish_offer, commit_receipt,
                 observe_failure, observe_quarantine, client_factory=BlobClient, workers: int = 4,
                 register_sources=None, publish_batch=None, observe_stored=None):
        if type(workers) is not int or not 1 <= workers <= 64:
            raise ValueError("Invalid artifact worker capacity")
        self.root = Path(root).resolve()
        self.journal = BlobArtifactJournal(self.root / "artifact-jobs.sqlite3")
        self.settings = settings
        self.open_source = open_source
        self.publish_offer = publish_offer
        self.commit_receipt = commit_receipt
        self.observe_failure = observe_failure
        self.observe_quarantine = observe_quarantine
        self.client_factory = client_factory
        self.workers = workers
        if register_sources is None:
            from blob_artifact_source import register_source_batch
            register_sources = register_source_batch
        self.register_sources = register_sources
        self.publish_batch = publish_batch
        self.observe_stored = observe_stored
        self._stop = threading.Event()
        self._wake = threading.Event()
        self._lifecycle = threading.Lock()
        self._thread = None
        self._pending_registration = None

    def enqueue(self, body: dict) -> str:
        job_id = self.journal.enqueue(body)
        self._wake.set()
        return job_id

    def enqueue_batch(self, bodies: list[dict], *, retain_on_desktop: bool = False, publication: dict | None = None) -> str:
        batch_id = self.journal.batches.enqueue(bodies, retain_on_desktop=retain_on_desktop, publication=publication)
        self._wake.set()
        return batch_id

    def accept_receipt(self, payload: dict, **identity) -> bool:
        accepted = self.journal.accept_receipt(payload, **identity)
        if accepted:
            self._wake.set()
        return accepted

    def wake(self):
        self._wake.set()

    def start(self):
        with self._lifecycle:
            if self._thread and self._thread.is_alive():
                return not self._stop.is_set()
            self._stop.clear()
            self._thread = threading.Thread(target=self._run, name="galaxyssi-blob-output", daemon=True)
            self._thread.start()
            return True

    def stop(self):
        self._stop.set()
        self._wake.set()

    def wait_stopped(self, timeout: float) -> bool:
        if self._thread:
            self._thread.join(timeout)
        return self._thread is None or not self._thread.is_alive()

    def _run(self):
        name = "GalaxySSI.BlobOutput." + hashlib.sha256(os.path.normcase(str(self.root)).encode()).hexdigest()
        owner = BackendInstanceLock(name)
        try:
            owner.acquire()
        except BackendInstanceAlreadyRunning:
            return
        try:
            while not self._stop.is_set():
                try:
                    self.journal.recover()
                    break
                except Exception:
                    self._stop.wait(2)
            if self._stop.is_set():
                return
            with ThreadPoolExecutor(max_workers=self.workers, thread_name_prefix="blob-output") as pool:
                pending = {}
                while not self._stop.is_set():
                    self._wake.clear()
                    for future in [item for item in pending if item.done()]:
                        try:
                            future.result()
                        except Exception:
                            try:
                                self.journal.defer(pending[future], error="artifact_blob_checkpoint_unavailable")
                            except Exception:
                                continue
                        pending.pop(future)
                    try:
                        for batch_id in self.journal.batches.quarantined():
                            if self.observe_quarantine(batch_id, "artifact_blob_batch_invalid") is True:
                                self.journal.batches.quarantine_observed(batch_id)
                        self._register_batches()
                        for job_id in self.journal.quarantined():
                            # No trusted scope can be recovered from corrupt data.
                            # This callback records a local incident, not a model message.
                            if self.observe_quarantine(job_id, "artifact_blob_checkpoint_invalid") is True:
                                self.journal.quarantine_observed(job_id)
                        for job in self.journal.claim_due(self.workers - len(pending),
                                exclude_ids=[work["id"] for work in pending.values()]):
                            future = pool.submit(self._process, job)
                            future.add_done_callback(lambda _: self._wake.set())
                            pending[future] = job
                        due = self.journal.next_due()
                    except Exception as error:
                        log.warning("Artifact coordinator deferred class=%s", type(error).__name__)
                        due = None
                    delay = max(.05, min(2., due - time.time())) if due is not None and len(pending) < self.workers else 2.
                    self._wake.wait(delay)
            # Executor shutdown joins workers before releasing process ownership.
        finally:
            owner.release()

    def _active(self, job: dict, *, require_settings: bool = True, require_relay: bool = True) -> dict:
        if self._stop.is_set() or not self.journal.current(job):
            raise BlobError("transfer_cancelled", 499)
        if not require_settings:
            return {}
        return self._settings(job["body"], require_relay=require_relay)

    def _settings(self, body: dict, *, require_relay: bool = True) -> dict:
        value = self.settings(body)
        if (not isinstance(value, dict) or any(value.get(key) != body[key] for key in
                ("peer_fingerprint", "local_fingerprint", "source_id"))):
            raise BlobError("artifact_blob_identity_changed", 409)
        if not require_relay:
            return value
        if not value.get("enabled"):
            raise BlobError("artifact_blob_relay_disabled", 503)
        if value.get("origin") != body["origin"]:
            raise BlobError("artifact_blob_relay_changed", 409)
        return value

    def _register_batches(self):
        batches = [self._pending_registration] if self._pending_registration else self.journal.batches.claim_due()
        for batch in batches:
            self._pending_registration = batch
            if not self.journal.batches.current(batch):
                self._pending_registration = None
                continue
            try:
                if self._stop.is_set():
                    raise BlobError("transfer_cancelled", 499)
                for body in batch["bodies"]:
                    self._settings(body)
                if self.register_sources(batch["bodies"], retain_on_desktop=batch["retain_on_desktop"]) is not True:
                    raise BlobError("artifact_blob_registration_pending", 503)
                if batch.get("publication") is not None:
                    if self.publish_batch is None or self.publish_batch(batch) is not True:
                        raise BlobError("artifact_blob_publication_pending", 503)
                self.journal.batches.finish(batch)
            except Exception as error:
                code = error.code if isinstance(error, BlobError) else "artifact_blob_registration_failed"
                if code in _TERMINAL:
                    self.journal.batches.fail(batch, code)
                else:
                    self.journal.batches.defer(batch, code)
            # If a checkpoint write raised, keep this claim for the next tick.
            self._pending_registration = None

    def _path(self, job: dict) -> Path:
        parent = self.root / "staging"
        path = parent / job["id"]
        if path.is_symlink() or path.resolve().parent != parent.resolve():
            raise BlobError("invalid_transfer_path")
        return path

    def _remove(self, path: Path):
        if path.is_symlink() or path.resolve().parent != (self.root / "staging").resolve():
            raise BlobError("invalid_transfer_path")
        if path.exists():
            shutil.rmtree(path)

    def _offer(self, job: dict, offer: dict):
        self._active(job)
        body = job["body"]
        payload = validate_offer({"type": OFFER_TYPE, "version": 1, "manifest": body["manifest"], "blob_offer": offer,
                                 "transport_revision": job["transport_revision"]},
                                 body["manifest"]["client_route_id"], body["manifest"]["desktop_id"], body["origin"])
        if self.publish_offer(body, payload) is not True:
            raise BlobError("artifact_blob_offer_not_queued", 503)

    def _process(self, job: dict):
        body = job["body"]
        try:
            if job["phase"] == "failure":
                self._observe(job)
                return
            settings = self._active(job, require_relay=job["phase"] != "cleanup")
            path = self._path(job)
            binding = artifact_binding(body["manifest"])
            if job["phase"] == "restage":
                self._remove(path)
                self.journal.finish_restage(job)
                return
            if job["phase"] == "cleanup":
                if self.journal.batches.blocks_cleanup(body["manifest"]["task_id"]):
                    raise BlobError("artifact_blob_source_registration_pending", 503)
                if self.observe_stored is not None and self.observe_stored(body) is not True:
                    raise BlobError("artifact_blob_stored_observation_pending", 503)
                # This callback must be idempotent. A stored phone copy may
                # release the source even while Relay revocation is offline.
                if self.commit_receipt(body) is not True:
                    raise BlobError("artifact_blob_receipt_commit_pending", 503)
                if (path / STATE_FILE).exists():
                    staged = StagedBlob.open(path, binding)
                    if staged.remote.get("created") and not staged.remote.get("revoked"):
                        try:
                            with self.client_factory(body["origin"]) as client:
                                client.revoke(staged)
                        except BlobError as error:
                            if error.code not in _RECREATE:
                                raise
                self._active(job, require_relay=False)
                self._remove(path)
                self.journal.finish(job)
                return
            if path.exists() and not (path / STATE_FILE).exists():
                self._remove(path)
            if (path / STATE_FILE).exists():
                if job["transport_revision"] < 1:
                    raise BlobError("artifact_blob_checkpoint_invalid", 409)
                staged = StagedBlob.open(path, binding)
            else:
                self.journal.reserve_transport(job)
                staged = StagedBlob.prepare_stream(lambda: self.open_source(body), path, binding,
                    size=body["manifest"]["size_bytes"], digest=body["manifest"]["sha256"],
                    cancel=lambda: self._cancelled(job))
            with self.client_factory(body["origin"], provisioning_token=settings["provisioning_token"]) as client:
                client.upload(staged, cancel=lambda: self._cancelled(job), progress=lambda *_: self._active(job),
                              on_offer=lambda offer: self._offer(job, offer))
            self.journal.defer(job, delay=30)
        except SecureStateError:
            if job["phase"] == "upload":
                self._failed(job, "artifact_blob_checkpoint_invalid")
            else:
                self.journal.defer(job, error="artifact_blob_cleanup_checkpoint_invalid")
        except FileNotFoundError:
            if job["phase"] == "upload":
                self._failed(job, "artifact_source_missing")
            else:
                self.journal.defer(job, error="artifact_blob_cleanup_file_unavailable")
        except BlobError as error:
            if job["phase"] in ("upload", "restage") and error.code in _TERMINAL:
                self._failed(job, error.code)
            elif job["phase"] == "upload" and error.code in _RECREATE:
                # Expired ciphertext gets a new key/nonce, never an old-context rewrite.
                self._active(job)
                if self.journal.begin_restage(job):
                    self.journal.defer(job, error=error.code, delay=1)
            else:
                self.journal.defer(job, error=error.code)
        except Exception:
            self.journal.defer(job, error="artifact_blob_transfer_failed")

    def _cancelled(self, job: dict) -> bool:
        self._active(job)
        return False

    def _failed(self, job: dict, code: str):
        if self.journal.fail(job, code):
            try:
                self._observe(job)
            except Exception:
                self.journal.defer(job, error="artifact_blob_failure_observation_pending")

    def _observe(self, job: dict):
        self._active(job, require_settings=False)
        if self.observe_failure(job["body"], job["failure"]) is not True:
            raise BlobError("artifact_blob_failure_observation_pending", 503)
        self.journal.failure_observed(job)
