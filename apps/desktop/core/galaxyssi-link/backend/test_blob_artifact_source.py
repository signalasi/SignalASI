"""Real task files and source leases, including independent HTTPS recipients."""
from dataclasses import replace
import hashlib
from pathlib import Path
import secrets
import time
import unittest
from unittest.mock import patch

import artifact_delivery
from blob_artifact_contract import artifact_binding, make_scoped_manifest, stored_receipt
from blob_artifact_sender import BlobArtifactSender
import blob_artifact_source as source
from blob_client import BlobClient
from blob_protocol import BlobError
from blob_relay import create_app
from blob_store import BlobStore
import test_artifact_delivery_ownership as ownership_fixture
from test_blob_artifact_contract import artifact_manifest
from test_blob_http import LocalRelay


class BlobArtifactSourceTests(unittest.TestCase):
    setUp = ownership_fixture.ArtifactOwnershipTests.setUp

    def job(self, artifact=None, route="a" * 22, turn="turn"):
        artifact = artifact or self.artifact
        fields = ("artifact_id", "task_id", "name", "relative_path", "mime_type", "size_bytes", "sha256",
                  "original_size_bytes", "original_sha256", "artifact_uri")
        metadata = artifact_manifest(**{key: getattr(artifact, key) for key in fields},
                                     client_route_id=route, turn_id=turn)
        metadata.pop("transfer_id")
        return {"manifest": make_scoped_manifest(metadata), "source_relative": "not-registered",
                "peer_fingerprint": "5" * 64, "local_fingerprint": "6" * 64,
                "source_id": "phone-" + route, "origin": "https://blob.test"}

    def register(self, job, artifact=None, retain=False):
        job["source_relative"] = source.register_source(artifact or self.artifact, job["manifest"], retain_on_desktop=retain)

    def test_registered_source_reopens_byte_exact_after_prepared_object_is_gone(self):
        job = self.job()
        self.register(job)
        with source.open_source(job) as stream:
            self.assertEqual(b"shared source", stream.read())
        self.assertTrue(source.commit_receipt(job))
        self.assertFalse(self.root.exists())
        self.assertTrue(source.commit_receipt(job))

    def test_unregistered_wrong_route_and_wrong_source_do_not_open_files(self):
        job = self.job()
        with self.assertRaisesRegex(BlobError, "lease_mismatch"):
            source.open_source(job)
        self.register(job)
        changed = self.job(route="b" * 22)
        changed["source_relative"] = job["source_relative"]
        for body in (changed, {**job, "source_relative": "tasks/shared-task/outputs/another.txt"}):
            with self.assertRaisesRegex(BlobError, "lease_mismatch"):
                source.open_source(body)
        self.assertFalse(source.commit_receipt(changed))
        self.assertTrue(self.source.exists())

    def test_manifest_cannot_register_different_prepared_bytes(self):
        job = self.job(replace(self.artifact, sha256="0" * 64))
        with self.assertRaisesRegex(BlobError, "source_manifest_mismatch"):
            self.register(job)
        self.assertFalse(artifact_delivery._ledger_path().exists())

    def test_compressed_bytes_are_frozen_without_recompression_or_original_replacement(self):
        raw = b"encoded"
        artifact = replace(self.artifact, transport_bytes=raw, size_bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())
        job = self.job(artifact)
        self.register(job, artifact)
        self.assertEqual(b"shared source", self.source.read_bytes())
        self.assertIn(".blob-output-", job["source_relative"])
        with source.open_source(job) as stream:
            self.assertEqual(raw, stream.read())
        self.register(job, artifact)
        self.assertEqual([], artifact_delivery.prepare_artifacts(self.artifact.task_id,
            [{"relative_path": "outputs/" + Path(job["source_relative"]).name}]))

    def test_corrupt_existing_snapshot_is_rejected_not_silently_replaced(self):
        raw = b"encoded"
        artifact = replace(self.artifact, transport_bytes=raw, size_bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())
        job = self.job(artifact)
        self.register(job, artifact)
        snapshot = artifact_delivery.workspace_root() / job["source_relative"]
        snapshot.write_bytes(b"damaged")
        with self.assertRaisesRegex(BlobError, "source_changed"):
            self.register(job, artifact)
        self.assertEqual(b"damaged", snapshot.read_bytes())

    def test_snapshot_write_failure_leaves_no_partial_file_or_false_owner(self):
        raw = b"encoded"
        artifact = replace(self.artifact, transport_bytes=raw, size_bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())
        with patch.object(source.os, "replace", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                self.register(self.job(artifact), artifact)
        self.assertEqual([self.source], list(self.source.parent.iterdir()))
        self.assertFalse(artifact_delivery._ledger_path().exists())

    def test_old_turn_receipt_keeps_new_turn_source(self):
        old, new = self.job(turn="old"), self.job(turn="new")
        self.register(old)
        self.register(new)
        self.assertTrue(source.commit_receipt(old))
        with source.open_source(new) as stream:
            self.assertEqual(b"shared source", stream.read())
        self.assertTrue(source.commit_receipt(new))
        self.assertFalse(self.root.exists())

    def test_recoverable_batch_registers_every_real_source_before_activation(self):
        second_path = self.source.parent / "second.txt"
        second_path.write_bytes(b"second source")
        second = artifact_delivery.prepare_artifacts(self.artifact.task_id, [{"relative_path": "outputs/second.txt"}])[0]
        jobs = [self.job(artifact) for artifact in (self.artifact, second)]
        for artifact, job in zip((self.artifact, second), jobs):
            job["source_relative"] = source.prepare_source(artifact, job["manifest"])
        self.assertFalse(artifact_delivery._ledger_path().exists())
        sender = BlobArtifactSender(artifact_delivery.workspace_root() / "sender-batch",
            settings=lambda body: {**body, "enabled": True, "provisioning_token": "e" * 64},
            open_source=source.open_source, commit_receipt=source.commit_receipt,
            publish_offer=lambda *_: True, observe_failure=lambda *_: True, observe_quarantine=lambda *_: True)
        sender.enqueue_batch(jobs)
        self.assertEqual({"held": 2}, sender.journal.snapshot())
        with patch.object(sender.journal.batches, "finish", side_effect=OSError("interrupted after registration")):
            sender._register_batches()
        self.assertEqual({"held": 2}, sender.journal.snapshot())
        self.assertEqual(2, len(artifact_delivery._read_ledger()))
        with patch("blob_artifact_batches.time.time", return_value=time.time() + 120):
            sender._register_batches()
        self.assertEqual({"pending": 2}, sender.journal.snapshot())
        self.assertEqual(2, len(artifact_delivery._read_ledger()))
        self.assertTrue(source.commit_receipt(jobs[0]))
        self.assertTrue(second_path.exists())
        self.assertTrue(source.commit_receipt(jobs[1]))
        self.assertFalse(self.root.exists())

    def test_missing_batch_member_does_not_register_a_partial_source_set(self):
        second_path = self.source.parent / "second.txt"
        second_path.write_bytes(b"second source")
        second = artifact_delivery.prepare_artifacts(self.artifact.task_id, [{"relative_path": "outputs/second.txt"}])[0]
        jobs = [self.job(artifact) for artifact in (self.artifact, second)]
        for artifact, job in zip((self.artifact, second), jobs):
            job["source_relative"] = source.prepare_source(artifact, job["manifest"])
        second_path.unlink()
        with self.assertRaisesRegex(BlobError, "artifact_source_missing"):
            source.register_source_batch(jobs, retain_on_desktop=False)
        self.assertFalse(artifact_delivery._ledger_path().exists())
        self.assertTrue(self.source.exists())

    def test_two_https_recipients_use_real_ownership_callbacks(self):
        base = artifact_delivery.workspace_root() / "transport-test"
        base.mkdir()
        token = secrets.token_hex(32)
        relay = LocalRelay(create_app(BlobStore(base / "relay.sqlite3"), token), base, tls=True)
        self.addCleanup(relay.close)
        jobs = [self.job(route=route) for route in ("a" * 22, "b" * 22)]
        offers, failures = {}, []
        sender = BlobArtifactSender(base / "sender",
            settings=lambda body: {**{key: body[key] for key in ("origin", "source_id", "peer_fingerprint", "local_fingerprint")},
                                   "enabled": True, "provisioning_token": token},
            open_source=source.open_source, commit_receipt=source.commit_receipt,
            publish_offer=lambda body, offer: offers.update({body["manifest"]["client_route_id"]: offer}) is None,
            observe_failure=lambda body, code: failures.append(code) is None, observe_quarantine=lambda *_: True,
            client_factory=lambda origin, **kwargs: BlobClient(origin, **kwargs, tls_context=relay.context,
                                                                trust_env=False, timeout=2))
        for job in jobs:
            self.register(job)
            job["origin"] = relay.origin
            sender.enqueue(job)
        for work in sender.journal.claim_due(2):
            sender._process(work)
        self.assertEqual(2, len(offers))
        self.assertTrue(self.source.exists())
        for index, job in enumerate(jobs):
            manifest = job["manifest"]
            with sender.client_factory(relay.origin) as client:
                staged = client.download(offers[manifest["client_route_id"]]["blob_offer"], base / f"receiver-{index}",
                                         artifact_binding(manifest))
            self.assertEqual(b"shared source", b"".join(staged.plaintext(artifact_binding(manifest))))
            self.assertTrue(sender.accept_receipt(stored_receipt(manifest), route=manifest["client_route_id"],
                source=job["source_id"], peer_fingerprint=job["peer_fingerprint"], local_fingerprint=job["local_fingerprint"]))
            cleanup = sender.journal.claim_due(1, now=time.time()).pop()
            self.assertEqual("cleanup", cleanup["phase"])
            sender._process(cleanup)
            self.assertEqual(index == 0, self.source.exists())
        self.assertEqual([], failures)
        self.assertEqual({"done": 2}, sender.journal.snapshot())


if __name__ == "__main__":
    unittest.main()
