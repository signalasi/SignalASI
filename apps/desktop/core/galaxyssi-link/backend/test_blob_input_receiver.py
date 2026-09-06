"""Real HTTPS relay to scoped Desktop input, without a live user pairing/model."""
import hashlib
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from blob_client import BlobClient
from blob_crypto import STATE_FILE, StagedBlob
from blob_input_contract import OFFER_TYPE, input_binding
from blob_input_journal import BlobInputJournal
from blob_input_receiver import BlobInputReceiver
from blob_protocol import BlobError, CHUNK_BYTES
from blob_relay import create_app
from blob_store import BlobStore
from input_attachment_transfer import ingest_verified_stream, resolved_attachment_path
from test_blob_http import LocalRelay
from test_blob_input_journal import input_manifest


class BlobInputReceiverTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        env = patch.dict(os.environ, {"GALAXYSSI_WORKSPACE_ROOT": str(self.root / "workspace")})
        env.start()
        self.addCleanup(env.stop)
        self.token = secrets.token_hex(32)
        self.store = BlobStore(self.root / "relay.sqlite3")
        app = create_app(self.store, self.token)
        self.gets = []
        @app.middleware("http")
        async def record(request, call_next):
            response = await call_next(request)
            if request.method == "GET" and "/chunks/" in request.url.path and response.status_code == 200:
                self.gets.append(request.url.path)
            return response
        self.relay = LocalRelay(app, self.root, tls=True)
        self.addCleanup(self.relay.close)
        self.content = b"\x00\xffprivate photo" * (CHUNK_BYTES // 14 + 1)
        self.receipts = []
        self.paired = True
        self.fingerprint = "a" * 64
        self.publish_ok = True
        self.download_attempts = 0
        self.receiver = self.make_receiver()
        self.payload = self.upload(self.content)

    def client(self, origin):
        return BlobClient(origin, provisioning_token=self.token, tls_context=self.relay.context, trust_env=False)

    def make_receiver(self, factory=None, workers=2):
        def publish(_route, _source, _fingerprint, receipt):
            self.receipts.append(receipt)
            return self.publish_ok
        result = BlobInputReceiver(self.root / "input", configured_origin=lambda: self.relay.origin,
                                   peer_identity=lambda *_: self.fingerprint if self.paired else None, publish_receipt=publish,
                                   client_factory=factory or self.client, workers=workers)
        def cleanup():
            result.stop()
            self.assertTrue(result.wait_stopped(10), "Worker must release its isolated relay/checkpoints")
        self.addCleanup(cleanup)
        return result

    def upload(self, content, suffix="1", recovery_request_id="", base_manifest=None):
        manifest = input_manifest(content, suffix=suffix)
        if base_manifest is not None:
            manifest.update(base_manifest)
            manifest.pop("blob_offer", None)
        if recovery_request_id:
            from input_attachment_transfer import transfer_id_for
            manifest["attachment_request_id"] = recovery_request_id
            manifest["transfer_id"] = transfer_id_for(*(manifest[key] for key in (
                "client_route_id", "conversation_id", "task_id", "turn_id", "attachment_id", "sha256")),
                attachment_request_id=recovery_request_id)
        source = self.root / ("source-" + suffix)
        source.write_bytes(content)
        staged = StagedBlob.prepare(source, self.root / ("sender-" + suffix), input_binding(manifest))
        with self.client(self.relay.origin) as client:
            offer = client.upload(staged)
        return {**manifest, "type": OFFER_TYPE, "blob_offer": offer}

    def enqueue(self, payload=None):
        payload = payload or self.payload
        return self.receiver.enqueue(payload, payload["client_route_id"], "paired-phone")

    def resolve(self, payload=None):
        payload = payload or self.payload
        return resolved_attachment_path(payload, **{key: payload[key] for key in (
            "client_route_id", "conversation_id", "task_id", "turn_id")})

    def claim(self):
        return self.receiver.journal.claim_due(1, now=time.time() + 1000)[0]

    def test_real_https_commits_stream_before_stored_receipt_and_removes_staging(self):
        job_id = self.enqueue()
        self.assertIsNone(self.resolve())
        self.assertEqual([], self.receipts)
        self.receiver._process(self.claim())
        self.assertEqual(self.content, self.resolve().read_bytes())
        self.assertEqual("stored", self.receipts[0]["status"])
        self.assertEqual(len(self.content), self.receipts[0]["received_bytes"])
        self.assertEqual({"done": 1}, self.receiver.journal.snapshot())
        self.assertFalse((self.receiver.root / "staging" / job_id).exists())
        self.assertEqual([], list((self.root / "workspace").rglob("*.chunk")))
        self.assertEqual([], list((self.root / "workspace").rglob("*.part")))

    def test_download_interruption_reopens_checkpoint_and_fetches_missing_only(self):
        self.enqueue()
        original = BlobClient._request
        def fail_second(client, method, path, *args, **kwargs):
            if method == "GET" and path.endswith("/chunks/1"):
                raise BlobError("relay_connection_failed", 503)
            return original(client, method, path, *args, **kwargs)
        with patch.object(BlobClient, "_request", fail_second):
            self.receiver._process(self.claim())
        self.assertIsNone(self.resolve())
        self.assertEqual([], self.receipts)
        self.assertEqual(1, len(self.gets))
        reopened = BlobInputJournal(self.receiver.journal.path)
        reopened.recover()
        self.receiver.journal = reopened
        self.receiver._process(self.claim())
        self.assertEqual(self.content, self.resolve().read_bytes())
        self.assertEqual(1, sum(path.endswith("/chunks/0") for path in self.gets))
        self.assertEqual(1, sum(path.endswith("/chunks/1") for path in self.gets))

    def test_receipt_queue_failure_recovers_without_download(self):
        job_id = self.enqueue()
        self.publish_ok = False
        self.receiver._process(self.claim())
        self.assertEqual(self.content, self.resolve().read_bytes())
        self.assertEqual({"pending": 1}, self.receiver.journal.snapshot())
        self.assertTrue((self.receiver.root / "staging" / job_id / STATE_FILE).exists())
        self.publish_ok = True
        self.receiver.client_factory = lambda _: self.fail("Receipt recovery must not download again")
        self.receiver.journal = BlobInputJournal(self.receiver.journal.path)
        self.receiver.journal.recover()
        self.receiver._process(self.claim())
        self.assertEqual(2, len(self.receipts))
        self.assertEqual({"done": 1}, self.receiver.journal.snapshot())

    def test_lost_delivery_ack_offer_retries_only_persisted_receipt(self):
        self.enqueue()
        self.receiver._process(self.claim())
        before = list(self.gets)
        self.enqueue()
        self.receiver._process(self.claim())
        self.assertEqual(before, self.gets)
        self.assertEqual(2, len(self.receipts))

    def test_unpaired_receiver_does_not_download_or_publish(self):
        self.enqueue()
        self.paired = False
        self.receiver._process(self.claim())
        self.assertEqual([], self.gets)
        self.assertEqual([], self.receipts)
        self.assertIsNone(self.resolve())
        self.assertEqual({"pending": 1}, self.receiver.journal.snapshot())

    def test_wrong_aead_key_never_publishes_stored_receipt(self):
        self.payload["blob_offer"]["private"]["key"] = secrets.token_hex(32)
        self.enqueue()
        self.receiver._process(self.claim())
        self.assertEqual("failed", self.receipts[0]["status"])
        self.assertEqual("chunk_authentication_failed", self.receipts[0]["error_code"])
        self.assertIsNone(self.resolve())
        self.assertEqual({"done": 1}, self.receiver.journal.snapshot())
        self.assertEqual([], list((self.root / "workspace").rglob("*.part")))

    def test_real_expiry_persists_failure_then_retries_receipt_after_restart(self):
        job_id = self.enqueue()
        self.store.clock = lambda: time.time() + 8 * 86400
        self.publish_ok = False
        self.receiver._process(self.claim())
        receipt = self.receipts[0]
        self.assertEqual("failed", receipt["status"])
        self.assertEqual("blob_expired", receipt["error_code"])
        self.assertEqual(self.payload["client_message_id"], receipt["source_message_id"])
        for field in (*input_binding(self.payload), "sha256", "size_bytes"):
            self.assertEqual(self.payload[field], receipt[field], field)
        self.assertNotIn("blob_offer", receipt)
        self.assertNotIn("read_token", str(receipt))
        self.assertIsNone(self.resolve())
        self.assertEqual({"pending": 1}, self.receiver.journal.snapshot())
        self.receiver.journal = BlobInputJournal(self.receiver.journal.path)
        self.receiver.journal.recover()
        self.receiver.client_factory = lambda _: self.fail("Failed receipt must not retry download")
        self.publish_ok = True
        self.receiver._process(self.claim())
        self.assertEqual(receipt, self.receipts[1])
        self.assertEqual({"done": 1}, self.receiver.journal.snapshot())
        self.assertFalse((self.receiver.root / "staging" / job_id).exists())
        self.enqueue()
        self.receiver._process(self.claim())
        self.assertEqual(receipt, self.receipts[2])

    def test_failed_receipt_does_not_cross_a_changed_pairing_after_restart(self):
        self.enqueue()
        self.store.clock = lambda: time.time() + 8 * 86400
        self.publish_ok = False
        self.receiver._process(self.claim())
        self.receiver.journal.recover()
        self.fingerprint = "b" * 64
        self.publish_ok = True
        self.receiver._process(self.claim())
        self.assertEqual(1, len(self.receipts))
        self.assertEqual({"pending": 1}, self.receiver.journal.snapshot())

    def test_fresh_request_recovers_expired_blob_without_reviving_old_attempt(self):
        old_id = self.enqueue()
        self.store.clock = lambda: time.time() + 8 * 86400
        self.receiver._process(self.claim())
        self.assertEqual("blob_expired", self.receipts[-1]["error_code"])
        self.store.clock = time.time
        fresh = self.upload(self.content, suffix="fresh", recovery_request_id="f" * 32,
                            base_manifest=self.payload)
        self.assertNotEqual(self.payload["transfer_id"], fresh["transfer_id"])
        self.assertNotEqual(self.payload["blob_offer"]["private"]["blob_id"],
                            fresh["blob_offer"]["private"]["blob_id"])
        self.assertNotEqual(old_id, self.enqueue(fresh))
        self.receiver._process(self.claim())
        self.assertEqual("stored", self.receipts[-1]["status"])
        self.assertEqual("f" * 32, self.receipts[-1]["attachment_request_id"])
        self.assertEqual(self.content, self.resolve(fresh).read_bytes())
        self.assertIsNone(self.resolve())
        before = list(self.gets)
        self.enqueue()
        self.receiver._process(self.claim())
        self.assertEqual("blob_expired", self.receipts[-1]["error_code"])
        self.assertEqual(before, self.gets)
        self.assertEqual(self.content, self.resolve(fresh).read_bytes())

    def test_failure_checkpoint_write_error_does_not_publish_or_lose_claim(self):
        self.enqueue()
        self.store.clock = lambda: time.time() + 8 * 86400
        with patch.object(self.receiver.journal, "receipt_ready", side_effect=OSError("isolated disk failure")):
            with self.assertRaises(OSError):
                self.receiver._process(self.claim())
        self.assertEqual([], self.receipts)
        self.receiver.journal.recover()
        self.receiver._process(self.claim())
        self.assertEqual("blob_expired", self.receipts[0]["error_code"])

    def test_not_yet_uploaded_chunk_keeps_resume_state_without_terminal_failure(self):
        self.enqueue()
        original = BlobClient._request
        def not_ready(client, method, path, *args, **kwargs):
            if method == "GET" and path.endswith("/chunks/1"):
                raise BlobError("chunk_not_ready", 404)
            return original(client, method, path, *args, **kwargs)
        with patch.object(BlobClient, "_request", not_ready):
            self.receiver._process(self.claim())
        self.assertEqual([], self.receipts)
        self.assertEqual({"pending": 1}, self.receiver.journal.snapshot())
        self.receiver._process(self.claim())
        self.assertEqual("stored", self.receipts[0]["status"])
        self.assertEqual(1, sum(path.endswith("/chunks/0") for path in self.gets))

    def test_real_expiry_reaches_model_follow_up_through_production_bridge_adapter(self):
        import blob_input_bridge
        from attachment_request_broker import AttachmentRequestBroker
        from attachment_recovery_observation import observe_attachment_recovery
        from model_recovery import ModelRecoveryAction, ModelRecoveryDecision

        broker = AttachmentRequestBroker()
        queued, observations = [], []
        peer = {"signal_name": "paired-phone", "identity_fingerprint": self.fingerprint,
                "local_identity_fingerprint": "b" * 64}
        bridge = SimpleNamespace(DATA_DIR=self.root / "bridge", client=object(),
                                 get_client=lambda _route: peer, attachment_request_broker=broker)
        def queue(_client, recipient, receipt, lane, *, durable):
            self.assertIs(recipient, peer)
            self.assertEqual("control", lane)
            self.assertTrue(durable)
            queued.append(receipt)
        bridge._publish_to_registered_client = queue
        self.store.clock = lambda: time.time() + 8 * 86400
        with patch.object(blob_input_bridge, "_receiver", None), \
                patch.dict(os.environ, {"GALAXYSSI_BLOB_RELAY_URL": self.relay.origin,
                                        "GALAXYSSI_BLOB_PROVISION_TOKEN": self.token}):
            receiver = blob_input_bridge._get_receiver(bridge)
            receiver.client_factory = self.client
            def request(payload):
                # Prepare a fresh request-bound transfer, then expire it on the real relay.
                self.store.clock = time.time
                offer = self.upload(self.content, suffix="recovery", recovery_request_id=payload["request_id"],
                                    base_manifest=self.payload)
                self.store.clock = lambda: time.time() + 8 * 86400
                receiver.enqueue(offer, offer["client_route_id"], "paired-phone")
                receiver._process(receiver.journal.claim_due(1)[0])
                return True
            observation = observe_attachment_recovery(lambda: broker.request(
                **{key: self.payload[key] for key in (
                    "client_route_id", "conversation_id", "task_id", "turn_id", "contact_id")},
                source_message_id=self.payload["client_message_id"],
                attachment_ids=[self.payload["attachment_id"]], reason="Inspect image", publish=request,
                timeout_seconds=2), on_failure=observations.append)
        self.assertEqual(["blob_expired"], observations)
        self.assertEqual(1, len(queued))
        self.assertEqual("failed", queued[0]["status"])
        self.assertEqual("blob_expired", queued[0]["error_code"])
        self.assertEqual({"done": 1}, receiver.journal.snapshot())
        self.assertIsNone(self.resolve())
        prompt = observation.follow_up(ModelRecoveryDecision(ModelRecoveryAction.REQUEST_ATTACHMENT))
        self.assertIn("No verified attachment was delivered", prompt)
        self.assertNotIn("restored the attachment", prompt)

    def test_repaired_route_with_different_fingerprint_cannot_resume_old_transfer(self):
        self.enqueue()
        self.fingerprint = "b" * 64
        self.receiver._process(self.claim())
        self.assertEqual([], self.gets)
        self.assertEqual([], self.receipts)
        self.assertIsNone(self.resolve())

    def test_slow_transfer_does_not_block_another_worker_or_enqueue(self):
        entered, release = threading.Event(), threading.Event()
        self.addCleanup(release.set)
        first_blob = self.payload["blob_offer"]["private"]["blob_id"]
        make_client = self.client
        def factory(origin):
            client = make_client(origin)
            download = client.download
            def delayed(offer, *args, **kwargs):
                if offer["private"]["blob_id"] == first_blob:
                    entered.set()
                    if not release.wait(8):
                        raise TimeoutError("Test download gate timed out")
                return download(offer, *args, **kwargs)
            client.download = delayed
            return client
        self.receiver.client_factory = factory
        self.enqueue()
        self.receiver.start()
        self.assertTrue(entered.wait(3))
        second = self.upload(b"second independent attachment", suffix="2")
        start = time.monotonic()
        self.enqueue(second)
        self.assertLess(time.monotonic() - start, 1)
        deadline = time.monotonic() + 5
        while self.resolve(second) is None and time.monotonic() < deadline:
            time.sleep(.02)
        self.assertIsNotNone(self.resolve(second))
        self.assertIsNone(self.resolve())
        release.set()

    def test_stream_failure_does_not_leave_plaintext_partial_or_global_lock(self):
        def broken():
            yield self.content[:10]
            raise OSError("isolated test failure")
        with self.assertRaises(OSError):
            ingest_verified_stream(self.payload, broken(), client_route_id=self.payload["client_route_id"])
        self.assertIsNone(self.resolve())
        self.assertEqual([], list((self.root / "workspace").rglob("*.part")))
        receipt = ingest_verified_stream(self.payload, [self.content], client_route_id=self.payload["client_route_id"])
        self.assertEqual("stored", receipt.status)

    def test_corrupt_same_length_stream_never_becomes_visible(self):
        with self.assertRaisesRegex(ValueError, "integrity"):
            ingest_verified_stream(self.payload, [b"x" * len(self.content)],
                                   client_route_id=self.payload["client_route_id"])
        self.assertIsNone(self.resolve())
        self.assertEqual([], list((self.root / "workspace").rglob("*.part")))

    def test_real_receiver_process_death_releases_owner_and_resumes_missing_chunks(self):
        self.enqueue()
        script = """
import os, ssl, sys
from pathlib import Path
from blob_client import BlobClient
from blob_input_receiver import BlobInputReceiver
from blob_protocol import CHUNK_BYTES
root, origin, certificate = sys.argv[1:]
context = ssl.create_default_context(cafile=certificate)
class DyingClient(BlobClient):
    def download(self, *args, **kwargs):
        observe = kwargs['progress']
        def die(done, total):
            observe(done, total)
            if done >= CHUNK_BYTES:
                os._exit(73)
        return super().download(*args, **{**kwargs, 'progress': die})
worker = BlobInputReceiver(Path(root), configured_origin=lambda: origin,
    peer_identity=lambda *_: 'a' * 64, publish_receipt=lambda *_: False,
    client_factory=lambda url: DyingClient(url, tls_context=context, trust_env=False))
worker.start()
worker.wait_stopped(20)
os._exit(74)
"""
        result = subprocess.run([sys.executable, "-c", script, str(self.receiver.root),
                                 self.relay.origin, str(self.root / "test-cert.pem")],
                                cwd=Path(__file__).resolve().parent, capture_output=True, timeout=30)
        self.assertEqual(73, result.returncode, result.stderr.decode(errors="replace"))
        self.assertEqual({"running": 1}, self.receiver.journal.snapshot())
        self.assertEqual(1, sum(path.endswith("/chunks/0") for path in self.gets))
        start = time.monotonic()
        self.receiver.start()
        deadline = start + 5
        while self.receiver.journal.snapshot() != {"done": 1} and time.monotonic() < deadline:
            time.sleep(.02)
        self.assertEqual({"done": 1}, self.receiver.journal.snapshot())
        self.assertEqual(self.content, self.resolve().read_bytes())
        self.assertEqual(1, sum(path.endswith("/chunks/0") for path in self.gets))
        self.assertLess(time.monotonic() - start, 5)

    def test_transient_startup_journal_error_recovers_without_another_mqtt_message(self):
        self.enqueue()
        recover = self.receiver.journal.recover
        attempts = []
        def temporarily_unavailable():
            attempts.append(1)
            if len(attempts) == 1:
                raise OSError("transient isolated journal fixture")
            recover()
        with patch.object(self.receiver.journal, "recover", side_effect=temporarily_unavailable):
            self.receiver.start()
            deadline = time.monotonic() + 5
            while self.receiver.journal.snapshot() != {"done": 1} and time.monotonic() < deadline:
                time.sleep(.02)
            self.assertEqual({"done": 1}, self.receiver.journal.snapshot())
        self.assertEqual(2, len(attempts))
