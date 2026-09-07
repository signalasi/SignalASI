import copy
import json
from pathlib import Path
import unittest

from blob_artifact_contract import (OFFER_TYPE, artifact_binding, make_manifest, make_scoped_manifest, receipt_matches,
                                    stored_receipt, validate_manifest, validate_offer)
from blob_crypto import binding_hash
from blob_protocol import BlobError, MAX_FILE_BYTES


def artifact_manifest(**changes):
    value = {"client_route_id": "a" * 22, "conversation_id": "conversation", "task_id": "task",
             "turn_id": "turn", "contact_id": "contact", "source_message_id": "message",
             "desktop_id": "desktop", "artifact_id": "b" * 64,
             "artifact_uri": "galaxyssi-artifact://task/homework.png", "name": "homework.png",
             "relative_path": "output/homework.png", "mime_type": "image/png", "sha256": "c" * 64,
             "original_sha256": "d" * 64, "size_bytes": 1234, "original_size_bytes": 2345,
             "execution_generation": 1, "peer_chat": False, **changes}
    return make_manifest(value)


def artifact_offer(manifest=None):
    manifest = manifest or artifact_manifest()
    return {"type": OFFER_TYPE, "version": 1, "manifest": manifest, "transport_revision": 1,
            "blob_offer": {"version": 1, "relay": "https://blob.test", "read_token": "e" * 64,
                "private": {"version": 1, "blob_id": "f" * 32, "key": "1" * 64,
                    "nonce_prefix": "2" * 16, "size": manifest["size_bytes"], "sha256": manifest["sha256"],
                    "binding_sha256": binding_hash(artifact_binding(manifest)), "manifest_sha256": "3" * 64}}}


class BlobArtifactContractTest(unittest.TestCase):
    def test_transport_revision_is_explicit_and_does_not_change_artifact_identity(self):
        original = artifact_offer()
        for revision in (1, 2, 2**53 - 1):
            result = validate_offer({**original, "transport_revision": revision}, "a" * 22, "desktop", "https://blob.test")
            self.assertEqual(revision, result["transport_revision"])
            self.assertEqual(original["manifest"], result["manifest"])
        for revision in (None, True, 1.0, "1", 0, -1, 2**53):
            with self.subTest(revision=revision), self.assertRaises(BlobError):
                validate_offer({**original, "transport_revision": revision}, "a" * 22, "desktop", "https://blob.test")

    def test_scoped_uri_is_stable_on_retry_and_unique_across_recipients_and_versions(self):
        original = artifact_manifest()
        original.pop("transfer_id")
        first = make_scoped_manifest(original)
        self.assertEqual(first, make_scoped_manifest(original))
        self.assertEqual(first, validate_manifest(first))
        self.assertEqual("galaxyssi-artifact://task/homework.png", original["artifact_uri"])
        for key, value in {"client_route_id": "z" * 22, "conversation_id": "other",
                           "task_id": "other", "turn_id": "other", "desktop_id": "other",
                           "execution_generation": 2, "sha256": "e" * 64,
                           "name": "updated.png", "artifact_uri": "galaxyssi-artifact://task/renamed.png"}.items():
            with self.subTest(field=key):
                changed = make_scoped_manifest({**original, key: value})
                self.assertNotEqual(first["artifact_uri"], changed["artifact_uri"])
                self.assertNotEqual(first["transfer_id"], changed["transfer_id"])
                self.assertFalse(receipt_matches(changed, stored_receipt(first)))

    def test_shared_android_artifact_vector(self):
        path = Path(__file__).resolve().parents[5] / "core/protocol/fixtures/blob-artifact-v1.json"
        fixture = json.loads(path.read_text())
        manifest = validate_manifest(fixture["manifest"])
        self.assertEqual(fixture["binding_sha256"], binding_hash(artifact_binding(manifest)))
        self.assertEqual(fixture["receipt"], stored_receipt(manifest))

    def test_valid_offer_retains_only_the_transport_contract(self):
        payload = artifact_offer()
        payload["untrusted_model_instructions"] = "not part of an artifact"
        result = validate_offer(payload, "a" * 22, "desktop", "https://blob.test")
        self.assertNotIn("untrusted_model_instructions", result)
        self.assertEqual(artifact_manifest(), result["manifest"])

    def test_all_semantic_metadata_is_bound_to_transfer_identity(self):
        original = artifact_manifest()
        replacements = {"client_route_id": "b" * 22, "conversation_id": "another", "task_id": "another",
                        "turn_id": "another", "contact_id": "another", "source_message_id": "another",
                        "desktop_id": "another", "artifact_id": "e" * 64, "name": "other.png",
                        "artifact_uri": "galaxyssi-artifact://task/other.png", "relative_path": "other.png",
                        "mime_type": "application/octet-stream", "sha256": "e" * 64,
                        "original_sha256": "e" * 64, "size_bytes": 2345, "original_size_bytes": 9999,
                        "execution_generation": 2, "peer_chat": True}
        for key, value in replacements.items():
            with self.subTest(field=key):
                changed = {**original, key: value}
                with self.assertRaises(BlobError):
                    validate_manifest(changed)
                changed.pop("transfer_id")
                changed = make_manifest(changed)
                self.assertNotEqual(original["transfer_id"], changed["transfer_id"])
                self.assertFalse(receipt_matches(changed, stored_receipt(original)))

    def test_non_integer_lengths_versions_and_generations_are_rejected(self):
        for field in ("size_bytes", "execution_generation", "original_size_bytes"):
            for value in (True, 1.0, "1", None, 0, -1, 2**53):
                with self.subTest(field=field, value=value), self.assertRaises(BlobError):
                    artifact_manifest(**{field: value})
        for value in (True, "1", 1.0):
            payload = artifact_offer()
            payload["version"] = value
            with self.assertRaises(BlobError):
                validate_offer(payload, "a" * 22, "desktop", "https://blob.test")

    def test_large_artifacts_do_not_inherit_legacy_64_mib_limit(self):
        for size in (152 * 1024**2, MAX_FILE_BYTES):
            value = artifact_manifest(size_bytes=size, original_size_bytes=size)
            self.assertEqual(value, validate_manifest(value))
        with self.assertRaises(BlobError):
            artifact_manifest(size_bytes=MAX_FILE_BYTES + 1, original_size_bytes=MAX_FILE_BYTES + 1)

    def test_route_origin_size_hash_and_aead_binding_cannot_be_retargeted(self):
        payload = artifact_offer()
        for route, desktop, origin in (("b" * 22, "desktop", "https://blob.test"),
                                      ("a" * 22, "other", "https://blob.test"),
                                      ("a" * 22, "desktop", "https://other.test")):
            with self.subTest(route=route, desktop=desktop, origin=origin), self.assertRaises(BlobError):
                validate_offer(payload, route, desktop, origin)
        for field, value in (("size", 1), ("sha256", "4" * 64), ("binding_sha256", "5" * 64)):
            changed = copy.deepcopy(payload)
            changed["blob_offer"]["private"][field] = value
            with self.subTest(field=field), self.assertRaises(BlobError):
                validate_offer(changed, "a" * 22, "desktop", "https://blob.test")

    def test_receipt_means_recipient_stored_not_relay_uploaded_or_broker_ack(self):
        manifest = artifact_manifest()
        receipt = stored_receipt(manifest)
        self.assertTrue(receipt_matches(manifest, receipt))
        for field, value in (("status", "uploaded"), ("status", "published"), ("type", "ack"),
                             ("version", True), ("size_bytes", 1234.0), ("transfer_id", "f" * 64)):
            with self.subTest(field=field, value=value):
                self.assertFalse(receipt_matches(manifest, {**receipt, field: value}))

    def test_bounded_fields_prevent_large_control_envelopes(self):
        for field, value in (("name", "x" * 256), ("task_id", "x" * 257), ("name", "a\nb"),
                             ("artifact_uri", "file:///private/file"), ("artifact_uri", "galaxyssi-artifact://t/a?secret=x")):
            with self.subTest(field=field), self.assertRaises(BlobError):
                artifact_manifest(**{field: value})
        payload = artifact_offer()
        payload["padding"] = "x" * 32768
        with self.assertRaises(BlobError):
            validate_offer(payload, "a" * 22, "desktop", "https://blob.test")
