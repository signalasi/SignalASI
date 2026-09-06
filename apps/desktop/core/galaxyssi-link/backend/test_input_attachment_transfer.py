import base64
import hashlib
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from input_attachment_transfer import (
    ATTACHMENT_CHUNK_BYTES,
    ATTACHMENT_REQUEST_WINDOW_CHUNKS,
    MAX_ATTACHMENTS_PER_TASK,
    ingest_chunk,
    ingest_manifest,
    prune_expired_transfers,
    resolved_attachment_path,
    resume_after_rejection,
    transfer_id_for,
)
from link_protocol import new_route_id


class InputAttachmentTransferTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.environment = patch.dict(
            os.environ,
            {"GALAXYSSI_WORKSPACE_ROOT": self.temporary.name},
        )
        self.environment.start()
        self.route_id = new_route_id()

    def tearDown(self):
        self.environment.stop()
        self.temporary.cleanup()

    def test_resume_manifest_compacts_all_missing_chunks(self):
        content = b"a" * (ATTACHMENT_CHUNK_BYTES * 2 + 17)
        payload = self._manifest(content, resume=True)

        receipt = ingest_manifest(payload, client_route_id=self.route_id)

        self.assertEqual("missing", receipt.status)
        self.assertEqual(((0, 2),), receipt.missing_ranges)

    def test_large_transfer_requests_bounded_windows_and_reports_real_progress(self):
        chunk_count = ATTACHMENT_REQUEST_WINDOW_CHUNKS + 2
        content = b"w" * (ATTACHMENT_CHUNK_BYTES * chunk_count)
        manifest = self._manifest(content)

        first = ingest_manifest(manifest, client_route_id=self.route_id)
        self.assertEqual(((0, ATTACHMENT_REQUEST_WINDOW_CHUNKS - 1),), first.missing_ranges)

        next_receipt = None
        for index in range(ATTACHMENT_REQUEST_WINDOW_CHUNKS):
            next_receipt = ingest_chunk(
                self._chunk(manifest, content, index),
                client_route_id=self.route_id,
            )
        self.assertEqual(
            ((ATTACHMENT_REQUEST_WINDOW_CHUNKS, chunk_count - 1),),
            next_receipt.missing_ranges,
        )
        self.assertEqual(
            ATTACHMENT_REQUEST_WINDOW_CHUNKS * ATTACHMENT_CHUNK_BYTES,
            next_receipt.received_bytes,
        )

        self.assertIsNone(ingest_chunk(
            self._chunk(manifest, content, ATTACHMENT_REQUEST_WINDOW_CHUNKS),
            client_route_id=self.route_id,
        ))
        stored = ingest_chunk(
            self._chunk(manifest, content, chunk_count - 1),
            client_route_id=self.route_id,
        )
        self.assertEqual("stored", stored.status)
        self.assertEqual(100, stored.progress)

    def test_out_of_order_chunks_resume_and_complete_after_restart(self):
        content = (
            b"a" * ATTACHMENT_CHUNK_BYTES
            + b"b" * ATTACHMENT_CHUNK_BYTES
            + b"tail"
        )
        manifest = self._manifest(content)
        ingest_manifest(manifest, client_route_id=self.route_id)

        self.assertIsNone(ingest_chunk(
            self._chunk(manifest, content, 2),
            client_route_id=self.route_id,
        ))
        resume = ingest_manifest(
            {**manifest, "resume": True},
            client_route_id=self.route_id,
        )
        self.assertEqual(((0, 1),), resume.missing_ranges)

        self.assertIsNone(ingest_chunk(
            self._chunk(manifest, content, 0),
            client_route_id=self.route_id,
        ))
        stored = ingest_chunk(
            self._chunk(manifest, content, 1),
            client_route_id=self.route_id,
        )

        self.assertEqual("stored", stored.status)
        path = resolved_attachment_path(
            self._descriptor(manifest),
            client_route_id=self.route_id,
            conversation_id=manifest["conversation_id"],
            task_id=manifest["task_id"],
            turn_id=manifest["turn_id"],
        )
        self.assertIsNotNone(path)
        self.assertEqual(content, path.read_bytes())

    def test_identical_chunk_duplicate_is_idempotent(self):
        content = b"duplicate"
        manifest = self._manifest(content)
        ingest_manifest(manifest, client_route_id=self.route_id)
        first = self._chunk(manifest, content, 0)

        stored = ingest_chunk(first, client_route_id=self.route_id)
        repeated = ingest_chunk(first, client_route_id=self.route_id)

        self.assertEqual("stored", stored.status)
        self.assertEqual("stored", repeated.status)

    def test_recovery_receipt_preserves_attachment_request_identity(self):
        content = b"restored"
        manifest = self._manifest(content)
        manifest["attachment_request_id"] = "c" * 32
        manifest["transfer_id"] = transfer_id_for(*(manifest[key] for key in (
            "client_route_id", "conversation_id", "task_id", "turn_id", "attachment_id", "sha256")),
            attachment_request_id=manifest["attachment_request_id"])
        ingest_manifest(manifest, client_route_id=self.route_id)

        stored = ingest_chunk(
            self._chunk(manifest, content, 0),
            client_route_id=self.route_id,
        )

        self.assertEqual("c" * 32, stored.attachment_request_id)
        self.assertEqual("attachment-one", stored.attachment_id)
        self.assertEqual("c" * 32, stored.payload()["attachment_request_id"])

    def test_fresh_recovery_attempts_do_not_consume_distinct_attachment_slots(self):
        content = b"recovery"
        manifest = self._manifest(content)
        ids = set()
        for attempt in range(MAX_ATTACHMENTS_PER_TASK + 3):
            manifest["attachment_request_id"] = f"{attempt:032x}"
            manifest["transfer_id"] = transfer_id_for(*(manifest[key] for key in (
                "client_route_id", "conversation_id", "task_id", "turn_id", "attachment_id", "sha256")),
                attachment_request_id=manifest["attachment_request_id"])
            receipt = ingest_manifest(manifest, client_route_id=self.route_id)
            ids.add(receipt.transfer_id)
        self.assertEqual(MAX_ATTACHMENTS_PER_TASK + 3, len(ids))
        for number in range(1, MAX_ATTACHMENTS_PER_TASK + 1):
            manifest["attachment_id"] = f"distinct-{number}"
            manifest["transfer_id"] = transfer_id_for(*(manifest[key] for key in (
                "client_route_id", "conversation_id", "task_id", "turn_id", "attachment_id", "sha256")),
                attachment_request_id=manifest["attachment_request_id"])
            if number < MAX_ATTACHMENTS_PER_TASK:
                ingest_manifest(manifest, client_route_id=self.route_id)
            else:
                with self.assertRaisesRegex(ValueError, "task limit"):
                    ingest_manifest(manifest, client_route_id=self.route_id)

    def test_tampered_chunk_is_rejected_and_requested_again(self):
        content = b"trusted"
        manifest = self._manifest(content)
        ingest_manifest(manifest, client_route_id=self.route_id)
        chunk = self._chunk(manifest, content, 0)
        chunk["data_b64"] = base64.b64encode(b"tamperd").decode("ascii")

        with self.assertRaisesRegex(ValueError, "integrity"):
            ingest_chunk(chunk, client_route_id=self.route_id)
        resume = resume_after_rejection(chunk, client_route_id=self.route_id)

        self.assertEqual("missing", resume.status)
        self.assertEqual(((0, 0),), resume.missing_ranges)

    def test_conflicting_duplicate_is_rejected(self):
        content = b"a" * (ATTACHMENT_CHUNK_BYTES + 1)
        manifest = self._manifest(content)
        ingest_manifest(manifest, client_route_id=self.route_id)
        first = self._chunk(manifest, content, 0)
        self.assertIsNone(ingest_chunk(first, client_route_id=self.route_id))
        conflict = dict(first)
        replacement = b"b" * ATTACHMENT_CHUNK_BYTES
        conflict["data_b64"] = base64.b64encode(replacement).decode("ascii")
        conflict["chunk_sha256"] = hashlib.sha256(replacement).hexdigest()

        with self.assertRaisesRegex(ValueError, "conflicts"):
            ingest_chunk(conflict, client_route_id=self.route_id)

    def test_full_hash_failure_discards_chunks_for_clean_resume(self):
        content = b"actual content"
        manifest = self._manifest(content)
        wrong_digest = hashlib.sha256(b"different content").hexdigest()
        manifest["sha256"] = wrong_digest
        manifest["transfer_id"] = transfer_id_for(
            self.route_id,
            manifest["conversation_id"],
            manifest["task_id"],
            manifest["turn_id"],
            manifest["attachment_id"],
            wrong_digest,
        )
        ingest_manifest(manifest, client_route_id=self.route_id)

        with self.assertRaisesRegex(ValueError, "integrity"):
            ingest_chunk(
                self._chunk(manifest, content, 0),
                client_route_id=self.route_id,
            )
        resume = resume_after_rejection(manifest, client_route_id=self.route_id)

        self.assertEqual(((0, 0),), resume.missing_ranges)

    def test_completed_attachment_cannot_cross_task_scope(self):
        content = b"scoped"
        manifest = self._manifest(content)
        ingest_manifest(manifest, client_route_id=self.route_id)
        ingest_chunk(self._chunk(manifest, content, 0), client_route_id=self.route_id)

        resolved = resolved_attachment_path(
            self._descriptor(manifest),
            client_route_id=self.route_id,
            conversation_id=manifest["conversation_id"],
            task_id=manifest["task_id"],
            turn_id="another-turn",
        )

        self.assertIsNone(resolved)

    def test_same_name_and_ordinal_cannot_overwrite_another_transfer(self):
        first_content = b"first"
        second_content = b"second"
        first = self._manifest(first_content)
        second = self._manifest(second_content)
        second["attachment_id"] = "attachment-two"
        second["transfer_id"] = transfer_id_for(
            self.route_id,
            second["conversation_id"],
            second["task_id"],
            second["turn_id"],
            second["attachment_id"],
            second["sha256"],
        )
        for manifest, content in ((first, first_content), (second, second_content)):
            ingest_manifest(manifest, client_route_id=self.route_id)
            ingest_chunk(
                self._chunk(manifest, content, 0),
                client_route_id=self.route_id,
            )

        first_path = resolved_attachment_path(
            self._descriptor(first),
            client_route_id=self.route_id,
            conversation_id=first["conversation_id"],
            task_id=first["task_id"],
            turn_id=first["turn_id"],
        )
        second_path = resolved_attachment_path(
            self._descriptor(second),
            client_route_id=self.route_id,
            conversation_id=second["conversation_id"],
            task_id=second["task_id"],
            turn_id=second["turn_id"],
        )

        self.assertNotEqual(first_path, second_path)
        self.assertEqual(first_content, first_path.read_bytes())
        self.assertEqual(second_content, second_path.read_bytes())

    def test_manifest_rejects_route_and_transfer_identity_mismatch(self):
        content = b"identity"
        manifest = self._manifest(content)
        manifest["conversation_id"] = "changed-conversation"

        with self.assertRaisesRegex(ValueError, "identity"):
            ingest_manifest(manifest, client_route_id=self.route_id)

    def test_expired_partial_transfer_and_completed_file_are_removed(self):
        content = b"expired"
        manifest = self._manifest(content)
        ingest_manifest(manifest, client_route_id=self.route_id)
        stored = ingest_chunk(
            self._chunk(manifest, content, 0),
            client_route_id=self.route_id,
        )
        completed = resolved_attachment_path(
            self._descriptor(manifest),
            client_route_id=self.route_id,
            conversation_id=manifest["conversation_id"],
            task_id=manifest["task_id"],
            turn_id=manifest["turn_id"],
        )
        transfer_root = (
            Path(self.temporary.name)
            / "tasks"
            / manifest["task_id"]
            / "downloads"
            / "input"
            / ".transfers"
            / manifest["transfer_id"]
        )
        manifest_path = transfer_root / "manifest.json"
        persisted = json.loads(manifest_path.read_text(encoding="utf-8"))
        persisted["created_at"] = 1
        manifest_path.write_text(json.dumps(persisted), encoding="utf-8")

        removed = prune_expired_transfers(now_seconds=2_000_000, force=True)

        self.assertEqual("stored", stored.status)
        self.assertEqual(1, removed)
        self.assertFalse(transfer_root.exists())
        self.assertFalse(completed.exists())

    def _manifest(self, content: bytes, *, resume: bool = False) -> dict:
        digest = hashlib.sha256(content).hexdigest()
        conversation_id = "conversation-one"
        task_id = "task-one"
        turn_id = "turn-one"
        attachment_id = "attachment-one"
        return {
            "type": "input_attachment_manifest",
            "transfer_id": transfer_id_for(
                self.route_id,
                conversation_id,
                task_id,
                turn_id,
                attachment_id,
                digest,
            ),
            "attachment_id": attachment_id,
            "attachment_ordinal": 0,
            "name": "../quarterly report.xlsx",
            "original_name": "quarterly report.xlsx",
            "mime_type": "application/octet-stream",
            "size_bytes": len(content),
            "original_size_bytes": len(content),
            "sha256": digest,
            "chunk_count": (
                len(content) + ATTACHMENT_CHUNK_BYTES - 1
            ) // ATTACHMENT_CHUNK_BYTES,
            "chunk_size_bytes": ATTACHMENT_CHUNK_BYTES,
            "client_route_id": self.route_id,
            "conversation_id": conversation_id,
            "task_id": task_id,
            "turn_id": turn_id,
            "contact_id": "codex",
            "client_message_id": 41,
            "resume": resume,
        }

    @staticmethod
    def _chunk(manifest: dict, content: bytes, index: int) -> dict:
        start = index * ATTACHMENT_CHUNK_BYTES
        value = content[start:start + ATTACHMENT_CHUNK_BYTES]
        return {
            **manifest,
            "type": "input_attachment_chunk",
            "chunk_index": index,
            "chunk_size": len(value),
            "chunk_sha256": hashlib.sha256(value).hexdigest(),
            "data_b64": base64.b64encode(value).decode("ascii"),
        }

    @staticmethod
    def _descriptor(manifest: dict) -> dict:
        return {
            "transfer_id": manifest["transfer_id"],
            "sha256": manifest["sha256"],
            "name": manifest["name"],
        }


if __name__ == "__main__":
    unittest.main()
