"""Independent, restartable Blob input workers; MQTT handles small offers only."""
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
from blob_client import BlobClient, _RELAY_ERRORS
from blob_crypto import STATE_FILE
from blob_failures import TERMINAL_BLOB_ERRORS, failed_input_receipt
from blob_input_contract import validate_input_offer
from blob_input_journal import BlobInputJournal
from blob_protocol import BlobError, checked_hex
from input_attachment_transfer import AttachmentIntegrityError, ingest_verified_stream

log = logging.getLogger(__name__)
_ERROR_CODES = _RELAY_ERRORS | {
    "relay_timeout", "relay_connection_failed", "relay_tls_verification_failed",
    "relay_redirect_rejected", "relay_response_encoding_rejected", "relay_response_too_large",
    "transfer_cancelled", "transfer_busy", "input_blob_claim_lost", "paired_identity_unavailable",
    "chunk_authentication_failed", "ciphertext_hash_mismatch", "manifest_hash_mismatch",
    "relay_checkpoint_mismatch", "transfer_binding_mismatch", "invalid_relay_response",
    "input_blob_receipt_not_queued", "blob_relay_not_configured", "input_blob_relay_mismatch",
}


class BlobInputReceiver:
    def __init__(self, root: Path, *, configured_origin, peer_identity, publish_receipt,
                 client_factory=BlobClient, workers: int = 4, allow_loopback_http: bool = False):
        if not 1 <= workers <= 64:
            raise ValueError("Invalid Blob worker capacity")
        self.root = Path(root).resolve()
        self.journal = BlobInputJournal(self.root / "input-jobs.sqlite3")
        self.origin = configured_origin
        self.peer_identity = peer_identity
        self.publish_receipt = publish_receipt
        self.client_factory = client_factory
        self.workers = workers
        self.allow_loopback_http = allow_loopback_http
        self._stop = threading.Event()
        self._wake = threading.Event()
        self._lifecycle = threading.Lock()
        self._thread = None

    def enqueue(self, payload: dict, route: str, source: str) -> str:
        fingerprint = self.peer_identity(route, source)
        if not fingerprint:
            raise BlobError("paired_identity_unavailable", 409)
        body = validate_input_offer(payload, route, source, self.origin(),
                                    allow_loopback_http=self.allow_loopback_http)
        body["peer_fingerprint"] = checked_hex(fingerprint)
        job_id = self.journal.enqueue(body)
        self._wake.set()
        return job_id

    def start(self):
        with self._lifecycle:
            if self._thread is not None and self._thread.is_alive():
                return
            self._stop.clear()
            self._thread = threading.Thread(target=self._run, name="galaxyssi-blob-input", daemon=True)
            self._thread.start()

    def stop(self):
        self._stop.set()
        self._wake.set()

    def wait_stopped(self, timeout: float) -> bool:
        thread = self._thread
        if thread:
            thread.join(timeout)
        return thread is None or not thread.is_alive()

    def _run(self):
        owner_name = "GalaxySSI.BlobInput." + hashlib.sha256(os.path.normcase(str(self.root)).encode()).hexdigest()
        owner = BackendInstanceLock(owner_name)
        try:
            owner.acquire()
        except BackendInstanceAlreadyRunning:
            return
        try:
            while not self._stop.is_set():
                try:
                    self.journal.recover()
                    break
                except Exception as error:
                    log.warning("Blob recovery deferred class=%s", type(error).__name__)
                    self._stop.wait(2)
            if self._stop.is_set():
                return
            with ThreadPoolExecutor(max_workers=self.workers, thread_name_prefix="blob-input") as pool:
                pending = {}
                while not self._stop.is_set():
                    self._wake.clear()
                    completed = [future for future in pending if future.done()]
                    for future in completed:
                        try:
                            future.result()
                        except Exception as error:
                            # Never log exception text: provider errors can contain secrets.
                            log.warning("Blob worker failed class=%s", type(error).__name__)
                            try:
                                self.journal.retry(pending[future], "input_blob_worker_failed")
                            except Exception:
                                continue
                        pending.pop(future)
                    capacity = self.workers - len(pending)
                    if capacity:
                        try:
                            for job in self.journal.claim_due(capacity):
                                future = pool.submit(self._process, job)
                                future.add_done_callback(lambda _future: self._wake.set())
                                pending[future] = job
                        except Exception as error:
                            log.warning("Blob job scan failed class=%s", type(error).__name__)
                    try:
                        due = self.journal.next_due()
                    except Exception:
                        due = None
                    delay = max(0.05, min(2.0, due - time.time())) if due is not None and capacity else 2.0
                    self._wake.wait(delay)
            # Keep process ownership until all workers have released their checkpoints.
        finally:
            owner.release()

    def _check_active(self, body: dict):
        if self._stop.is_set():
            raise BlobError("transfer_cancelled", 499)
        if self.peer_identity(body["route"], body["source"]) != body["peer_fingerprint"]:
            raise BlobError("paired_identity_unavailable", 409)

    def _staging_path(self, job_id: str) -> Path:
        parent = self.root / "staging"
        target = parent / job_id
        if target.is_symlink() or target.resolve().parent != parent.resolve():
            raise BlobError("invalid_transfer_path")
        return target

    def _cleanup(self, path: Path):
        if path.exists():
            if path.is_symlink() or path.resolve().parent != (self.root / "staging").resolve():
                raise BlobError("invalid_transfer_path")
            shutil.rmtree(path)

    def _process(self, job: dict):
        body = job["body"]
        try:
            self._check_active(body)
            staging = self._staging_path(job["id"])
            if job["phase"] == "download":
                # Configuration is local/paired authority, not a URL supplied by an offer.
                validate_input_offer({**body["manifest"], "type": "input_attachment_blob_offer",
                                      "blob_offer": body["offer"]}, body["route"], body["source"],
                                     self.origin(), allow_loopback_http=self.allow_loopback_http)
                if staging.exists() and not (staging / STATE_FILE).exists():
                    self._cleanup(staging)
                with self.client_factory(body["offer"]["relay"]) as client:
                    staged = client.download(body["offer"], staging, body["binding"],
                                             cancel=self._stop.is_set,
                                             progress=lambda *_: self._check_active(body))
                self._check_active(body)
                receipt = ingest_verified_stream(
                    body["manifest"], staged.plaintext(body["binding"]), client_route_id=body["route"],
                    check_active=lambda: self._check_active(body))
                self.journal.receipt_ready(job, receipt.payload())
                body = job["body"]
            self._deliver_receipt(job, staging)
        except Exception as error:
            code = error.code if isinstance(error, BlobError) and error.code in _ERROR_CODES else "input_blob_worker_failed"
            if (job["phase"] == "download" and isinstance(error, (BlobError, AttachmentIntegrityError))
                    and error.code in TERMINAL_BLOB_ERRORS):
                # Persist before publishing. A restart retries this receipt, not the failed download.
                self.journal.receipt_ready(job, failed_input_receipt(body["manifest"], error.code))
                try:
                    self._deliver_receipt(job, self._staging_path(job["id"]))
                    return
                except Exception as delivery_error:
                    code = (delivery_error.code if isinstance(delivery_error, BlobError)
                            and delivery_error.code in _ERROR_CODES else "input_blob_worker_failed")
            self.journal.retry(job, code)

    def _deliver_receipt(self, job: dict, staging: Path):
        body = job["body"]
        self._check_active(body)
        if self.publish_receipt(body["route"], body["source"], body["peer_fingerprint"], body["receipt"]) is not True:
            raise BlobError("input_blob_receipt_not_queued", 503)
        self._cleanup(staging)
        self.journal.finish(job)
