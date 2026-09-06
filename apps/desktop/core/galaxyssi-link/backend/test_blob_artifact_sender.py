import io
from pathlib import Path
import secrets
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock

from blob_artifact_contract import artifact_binding, stored_receipt
from blob_artifact_sender import BlobArtifactSender
from blob_client import BlobClient
from blob_crypto import STATE_FILE, StagedBlob
from blob_protocol import BlobError, CHUNK_BYTES, canonical, sha256
from blob_relay import create_app
from blob_store import BlobStore
from test_blob_artifact_journal import artifact_job
from test_blob_http import LocalRelay


class BlobArtifactSenderTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="blob-output-sender-")
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.raw = b"synthetic returned file" * 100
        self.token = secrets.token_hex(32)
        self.body = artifact_job(size_bytes=len(self.raw), original_size_bytes=len(self.raw), sha256=sha256(self.raw))
        self.offers, self.receipts, self.failures, self.corrupt = [], [], [], []
        self.opened = 0
        def open_source(_):
            self.opened += 1
            return io.BytesIO(self.raw)
        self.sender = BlobArtifactSender(self.root / "sender", settings=self.settings,
            open_source=open_source, publish_offer=lambda b, p: self.offers.append(p) is None,
            commit_receipt=lambda b: self.receipts.append(b) is None,
            observe_failure=lambda b, c: self.failures.append(c) is None,
            observe_quarantine=lambda i, c: self.corrupt.append((i, c)) is None)
        self.addCleanup(self.stop_sender)

    def stop_sender(self):
        self.sender.stop()
        self.assertTrue(self.sender.wait_stopped(10))

    def settings(self, body):
        return {**{key: body[key] for key in ("origin", "source_id", "peer_fingerprint", "local_fingerprint")},
                "enabled": True, "provisioning_token": self.token}

    def claim(self):
        return self.sender.journal.claim_due(1, now=time.time() + 1000)[0]

    def receipt(self, body=None):
        body = body or self.body
        return self.sender.accept_receipt(stored_receipt(body["manifest"]),
            route=body["manifest"]["client_route_id"], source=body["source_id"],
            peer_fingerprint=body["peer_fingerprint"], local_fingerprint=body["local_fingerprint"])

    def relay(self):
        self.store = BlobStore(self.root / "relay.sqlite3")
        relay = LocalRelay(create_app(self.store, self.token), self.root, tls=True)
        self.addCleanup(relay.close)
        self.body["origin"] = relay.origin
        self.sender.client_factory = lambda origin, **kw: BlobClient(origin, **kw,
            tls_context=relay.context, trust_env=False, timeout=2)
        return relay

    def test_real_https_return_waits_for_recipient_storage_before_releasing_source(self):
        self.relay()
        self.sender.enqueue(self.body)
        upload = self.claim()
        self.sender._process(upload)
        self.assertEqual([], self.receipts)
        self.assertEqual([], self.failures)
        self.assertEqual(1, len(self.offers))
        self.assertLess(len(canonical(self.offers[0])), 32768)
        self.assertNotIn(b"synthetic returned file", canonical(self.offers[0]))
        self.assertNotIn("data_b64", self.offers[0])
        with self.sender.client_factory(self.body["origin"]) as client:
            received = client.download(self.offers[0]["blob_offer"], self.root / "receiver",
                                       artifact_binding(self.body["manifest"]))
        self.assertEqual(self.raw, b"".join(received.plaintext(artifact_binding(self.body["manifest"]))))
        self.assertTrue(self.receipt())
        self.sender._process(self.claim())
        self.assertEqual([self.body], self.receipts)
        self.assertEqual({"done": 1}, self.sender.journal.snapshot())
        self.assertFalse(self.sender._path(upload).exists())
        with self.assertRaises(BlobError):
            self.store.get_manifest(received.private["blob_id"], self.offers[0]["blob_offer"]["read_token"])

    def test_relay_outage_reuses_ciphertext_without_reopening_source(self):
        relay = self.relay()
        good_factory = self.sender.client_factory
        self.sender.client_factory = Mock(side_effect=BlobError("relay_connection_failed", 503))
        self.sender.enqueue(self.body)
        job = self.claim()
        self.sender._process(job)
        before = StagedBlob.open(self.sender._path(job), artifact_binding(self.body["manifest"]))
        self.assertEqual(1, self.opened)
        self.sender.client_factory = good_factory
        self.sender._process(self.claim())
        after = StagedBlob.open(self.sender._path(job), artifact_binding(self.body["manifest"]))
        self.assertEqual(before.private, after.private)
        self.assertEqual(1, self.opened)
        self.assertEqual([], self.receipts)
        self.assertEqual(relay.origin, self.offers[0]["blob_offer"]["relay"])

    def test_expired_relay_session_restarts_with_new_keys_and_same_content(self):
        self.relay()
        self.sender.enqueue(self.body)
        job = self.claim()
        self.sender._process(job)
        old = StagedBlob.open(self.sender._path(job), artifact_binding(self.body["manifest"]))
        with self.sender.client_factory(self.body["origin"]) as client:
            client.revoke(old)
        self.sender._process(self.claim())
        self.assertTrue(self.sender._path(job).exists())
        self.sender._process(self.claim())
        self.assertFalse(self.sender._path(job).exists())
        self.sender._process(self.claim())
        fresh = StagedBlob.open(self.sender._path(job), artifact_binding(self.body["manifest"]))
        self.assertNotEqual(old.private["key"], fresh.private["key"])
        self.assertNotEqual(old.private["nonce_prefix"], fresh.private["nonce_prefix"])
        self.assertEqual(old.private["sha256"], fresh.private["sha256"])
        self.assertEqual([], self.receipts)
        self.assertEqual([1, 2], [offer["transport_revision"] for offer in self.offers])
        self.assertEqual(self.offers[0]["manifest"], self.offers[1]["manifest"])

    def test_missing_staging_checkpoint_allocates_new_revision_before_rekeying(self):
        self.relay()
        self.sender.enqueue(self.body)
        job = self.claim()
        self.sender._process(job)
        first = self.offers[0]
        (self.sender._path(job) / STATE_FILE).unlink()
        self.sender._process(self.claim())
        self.assertEqual(2, self.offers[-1]["transport_revision"])
        self.assertNotEqual(first["blob_offer"]["private"]["key"], self.offers[-1]["blob_offer"]["private"]["key"])

    def test_source_change_is_observed_and_never_authorizes_cleanup(self):
        self.sender.open_source = lambda _: io.BytesIO(b"different source")
        self.sender.enqueue(self.body)
        job = self.claim()
        self.sender._process(job)
        self.assertEqual(["source_changed"], self.failures)
        self.assertEqual({"failed": 1}, self.sender.journal.snapshot())
        self.assertFalse((self.sender._path(job) / STATE_FILE).exists())
        self.assertEqual([], self.offers)
        self.assertEqual([], self.receipts)

    def test_corrupt_encrypted_checkpoint_is_observed_without_reset_or_source_cleanup(self):
        self.sender.client_factory = Mock(side_effect=BlobError("relay_connection_failed", 503))
        self.sender.enqueue(self.body)
        job = self.claim()
        self.sender._process(job)
        checkpoint = self.sender._path(job) / STATE_FILE
        checkpoint.write_bytes(b"not an authenticated checkpoint")
        self.sender._process(self.claim())
        self.assertEqual(["artifact_blob_checkpoint_invalid"], self.failures)
        self.assertEqual({"failed": 1}, self.sender.journal.snapshot())
        self.assertEqual(b"not an authenticated checkpoint", checkpoint.read_bytes())
        self.assertEqual([], self.receipts)
        self.assertEqual(1, self.opened)

    def test_corrupt_local_ciphertext_is_observed_without_repeating_upload_forever(self):
        self.relay()
        self.sender.client_factory = Mock(side_effect=BlobError("relay_connection_failed", 503))
        self.sender.enqueue(self.body)
        job = self.claim()
        self.sender._process(job)
        chunk = self.sender._path(job) / "00000000.blob"
        chunk.write_bytes(b"broken ciphertext")
        # The upload worker discovers corruption when preparing the missing Relay chunk.
        def client(*args, **kwargs):
            instance = Mock()
            instance.__enter__ = Mock(return_value=instance)
            instance.__exit__ = Mock(return_value=False)
            instance.upload.side_effect = lambda staged, **options: staged.read_chunk(0)
            return instance
        self.sender.client_factory = client
        self.sender._process(self.claim())
        self.assertEqual(["local_chunk_missing_or_corrupt"], self.failures)
        self.assertEqual({"failed": 1}, self.sender.journal.snapshot())
        self.assertEqual([], self.receipts)
        self.assertEqual(1, self.opened)
        self.assertTrue((self.sender._path(job) / STATE_FILE).exists())
        self.assertEqual([], self.offers)

    def test_failure_observation_is_durable_and_retried_without_source_reexecution(self):
        self.sender.open_source = Mock(side_effect=FileNotFoundError("private name"))
        self.sender.observe_failure = lambda *_: False
        self.sender.enqueue(self.body)
        self.sender._process(self.claim())
        self.assertEqual({"pending": 1}, self.sender.journal.snapshot())
        recovered = self.claim()
        self.assertEqual("failure", recovered["phase"])
        self.assertEqual("artifact_source_missing", recovered["failure"])
        self.sender.observe_failure = lambda b, c: self.failures.append(c) is None
        self.sender._process(recovered)
        self.sender.open_source.assert_called_once()
        self.assertEqual(["artifact_source_missing"], self.failures)
        self.assertEqual({"failed": 1}, self.sender.journal.snapshot())

    def test_identity_change_before_upload_is_observed_without_opening_source(self):
        self.sender.settings = lambda body: {**self.settings(body), "peer_fingerprint": "f" * 64}
        self.sender.enqueue(self.body)
        self.sender._process(self.claim())
        self.assertEqual(0, self.opened)
        self.assertEqual(["artifact_blob_identity_changed"], self.failures)

    def test_receipt_cleanup_can_release_source_after_operator_disables_relay(self):
        self.relay()
        self.sender.enqueue(self.body)
        self.sender._process(self.claim())
        self.assertTrue(self.receipt())
        self.sender.settings = lambda body: {**self.settings(body), "enabled": False, "origin": ""}
        self.sender._process(self.claim())
        self.assertEqual([self.body], self.receipts)
        self.assertEqual({"done": 1}, self.sender.journal.snapshot())

    def test_cleanup_waits_for_the_old_upload_worker_to_release_its_staging(self):
        started, release, finished = threading.Event(), threading.Event(), threading.Event()
        sender = self.sender
        body = self.body
        class BlockedClient:
            def __init__(self, *_args, **_kwargs):
                pass
            def __enter__(self):
                return self
            def __exit__(self, *_args):
                pass
            def upload(self, staged, **_kwargs):
                started.set()
                if not release.wait(5):
                    raise RuntimeError("Test upload barrier expired")
                finished.set()
        sender.client_factory = BlockedClient
        def commit(value):
            self.assertTrue(finished.is_set())
            self.receipts.append(value)
            return True
        sender.commit_receipt = commit
        sender.enqueue(body)
        sender.start()
        try:
            self.assertTrue(started.wait(5))
            self.assertTrue(self.receipt())
            time.sleep(.15)
            self.assertEqual([], self.receipts)
            self.assertTrue(sender.journal.snapshot().get("pending"))
            release.set()
            deadline = time.monotonic() + 5
            while not self.receipts and time.monotonic() < deadline:
                time.sleep(.02)
            self.assertEqual([body], self.receipts)
        finally:
            release.set()
            self.stop_sender()

    def test_cleanup_callback_failure_does_not_leave_a_running_claim(self):
        self.relay()
        self.sender.enqueue(self.body)
        self.sender._process(self.claim())
        self.assertTrue(self.receipt())
        self.sender.commit_receipt = Mock(side_effect=[FileNotFoundError("interrupted source cleanup"), True])
        self.sender._process(self.claim())
        self.assertEqual({"pending": 1}, self.sender.journal.snapshot())
        self.sender._process(self.claim())
        self.assertEqual({"done": 1}, self.sender.journal.snapshot())
        self.assertEqual(2, self.sender.commit_receipt.call_count)

    def test_uncommitted_receipt_preserves_relay_and_staging_until_retry(self):
        self.relay()
        self.sender.enqueue(self.body)
        self.sender._process(self.claim())
        self.assertTrue(self.receipt())
        self.sender.commit_receipt = Mock(side_effect=[None, False, True])
        for _ in range(2):
            job = self.claim()
            self.sender._process(job)
            self.assertEqual({"pending": 1}, self.sender.journal.snapshot())
            self.assertTrue(self.sender._path(job).exists())
            offer = self.offers[0]["blob_offer"]
            staged = StagedBlob.open(self.sender._path(job), artifact_binding(self.body["manifest"]))
            self.store.get_manifest(staged.private["blob_id"], offer["read_token"])
        self.sender._process(self.claim())
        self.assertEqual({"done": 1}, self.sender.journal.snapshot())
        self.assertFalse(self.sender._path(job).exists())
