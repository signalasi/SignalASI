import base64
from contextlib import closing
import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

from agent_task_result_archive import PAGE_BYTES, TaskResultArchive, identity
from agent_task_recovery_query import IDENTITY_FIELDS


class TaskResultArchiveTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / "results.db"
        self.archive = TaskResultArchive(self.path)
        self.fields = dict(zip(IDENTITY_FIELDS, ("route", "conversation", "task", "turn", "contact", "42", "codex")))
        self.payload = {**self.fields, "type": "text", "task_status": "completed", "content": "private-answer"}

    def tearDown(self):
        self.directory.cleanup()

    def request(self, page=0, **extra):
        return {**self.fields, "request_id": "nonce", "page_index": page, **extra}

    def page(self, request=None):
        return self.archive.page(request or self.request(), client_route_id="route")

    def test_exact_payload_round_trip_after_reopen(self):
        self.payload["content"] = "\u4f60\u597d\U0001f680" * 10000
        self.payload["rich_output"] = {"blocks": [{"type": "artifact", "uri": "artifact://opaque"}]}
        receipt = self.archive.put(self.payload)
        self.archive = TaskResultArchive(self.path)
        first = self.page()
        self.assertGreater(first["page_count"], 1)
        chunks = []
        for index in range(first["page_count"]):
            page = self.page(self.request(index, sha256=receipt["sha256"]))
            raw = base64.b64decode(page["data_b64"])
            self.assertLessEqual(len(raw), PAGE_BYTES)
            self.assertEqual(hashlib.sha256(raw).hexdigest(), page["page_sha256"])
            chunks.append(raw)
        body = b"".join(chunks)
        self.assertEqual(first["total_bytes"], len(body))
        self.assertEqual(receipt["sha256"], hashlib.sha256(body).hexdigest())
        self.assertEqual(self.payload, json.loads(body))

    def test_every_scope_component_is_required_for_read(self):
        self.archive.put(self.payload)
        for field in IDENTITY_FIELDS:
            with self.subTest(field=field):
                result = self.page(self.request(**{field: "another"}))
                if field == "client_route_id":
                    self.assertIsNone(result)
                else:
                    self.assertEqual("unavailable", result["status"])
                    self.assertNotIn("data_b64", result)

    def test_malformed_request_and_missing_nonce(self):
        self.archive.put(self.payload)
        for page in (-1, True, "0", None, 2**31):
            self.assertIsNone(self.page(self.request(page)))
        for nonce in (None, "", "x" * 129):
            self.assertIsNone(self.page(self.request(request_id=nonce)))
        for field in IDENTITY_FIELDS:
            self.assertIsNone(self.page(self.request(**{field: ""})))

    def test_only_terminal_nonempty_text_results_are_archived(self):
        for status in ("running", "paused", "waiting_input", "unknown"):
            self.assertIsNone(self.archive.put({**self.payload, "task_status": status}))
        self.assertIsNone(self.archive.put({**self.payload, "content": ""}))
        self.assertIsNone(self.archive.put({**self.payload, "type": "agent_task_event"}))
        self.assertIsNone(self.archive.put({**self.payload, "turn_id": ""}))

    def test_duplicate_publish_never_overwrites_canonical_result(self):
        first = self.archive.put(self.payload)
        self.assertEqual(first, self.archive.put({**self.payload, "content": "changed on replay"}))
        stored = json.loads(base64.b64decode(self.page()["data_b64"]))
        self.assertEqual(self.payload, stored)

    def test_digest_pins_all_pages_to_same_reply(self):
        self.archive.put(self.payload)
        result = self.page(self.request(sha256="0" * 64))
        self.assertEqual("unavailable", result["status"])
        self.assertNotIn("data_b64", result)
        self.assertEqual("unavailable", self.page(self.request(123))["status"])

    def test_acknowledgement_requires_exact_scope_and_digest(self):
        receipt = self.archive.put(self.payload)
        ack = {**self.fields, **receipt}
        for field in IDENTITY_FIELDS:
            self.assertFalse(self.archive.acknowledge({**ack, field: "wrong"}, client_route_id="route"))
        self.assertFalse(self.archive.acknowledge({**ack, "sha256": "wrong"}, client_route_id="route"))
        self.assertEqual("ready", self.page()["status"])
        self.assertTrue(self.archive.acknowledge(ack, client_route_id="route"))
        self.assertTrue(self.archive.acknowledge(ack, client_route_id="route"))
        self.assertEqual("unavailable", self.page()["status"])

    def test_receipt_deletes_body_and_duplicate_publish_cannot_resurrect_it(self):
        receipt = self.archive.put(self.payload)
        self.archive.acknowledge({**self.fields, **receipt}, client_route_id="route")
        self.archive.put(self.payload)
        self.archive = TaskResultArchive(self.path)
        self.assertEqual("unavailable", self.page()["status"])
        with closing(sqlite3.connect(self.path)) as db:
            self.assertEqual(0, db.execute("SELECT COUNT(*) FROM pages").fetchone()[0])
            self.assertEqual(1, db.execute("SELECT COUNT(*) FROM results").fetchone()[0])

    def test_contents_and_identity_are_not_plaintext_at_rest(self):
        self.archive.put(self.payload)
        with closing(sqlite3.connect(self.path)) as db:
            stored = repr(db.execute("SELECT * FROM results").fetchall()) + repr(db.execute("SELECT * FROM pages").fetchall())
        self.assertNotIn("private-answer", stored)
        self.assertNotIn("conversation", stored)
        self.assertIn("enc:v1:", stored)

    def test_corrupted_or_transplanted_page_fails_authentication(self):
        self.payload["content"] *= 2000
        self.archive.put(self.payload)
        with closing(sqlite3.connect(self.path)) as db:
            db.execute("UPDATE pages SET body=(SELECT body FROM pages WHERE page=0) WHERE page=1")
            db.commit()
        with self.assertRaises(Exception):
            self.page(self.request(1))

    def test_request_does_not_create_missing_result_or_execute_any_task(self):
        self.assertEqual("unavailable", self.page()["status"])
        with closing(sqlite3.connect(self.path)) as db:
            self.assertEqual(0, db.execute("SELECT COUNT(*) FROM results").fetchone()[0])

    def test_optional_transport_metadata_is_not_rearchived(self):
        self.archive.put({**self.payload, "result_recovery": {"sha256": "old"}})
        result = json.loads(base64.b64decode(self.page()["data_b64"]))
        self.assertNotIn("result_recovery", result)
        self.assertEqual(self.fields, identity(result))

    def test_normal_delivery_archives_before_outbound_queue_and_keeps_receipt(self):
        import agent_task_result_archive
        import mqtt_bridge

        def queue(task_id, route, wire, payload, *, replay=False):
            self.assertFalse(replay)
            self.assertEqual("ready", self.page()["status"])
            self.assertEqual("route", route)
            self.assertEqual(self.page()["sha256"], payload["result_recovery"]["sha256"])

        with patch.object(agent_task_result_archive, "archive", self.archive), \
                patch.object(mqtt_bridge, "queue_task_result", side_effect=queue) as queued, \
                patch.object(mqtt_bridge, "_ensure_outbound_retry_thread"), \
                patch.object(mqtt_bridge, "outbound_status", return_value=None):
            self.assertFalse(mqtt_bridge._publish_or_queue_task_result(None,
                {"scheme": "signal", "_client_route_id": "route"}, self.payload))
            queued.assert_called_once()
        self.archive = TaskResultArchive(self.path)
        self.assertEqual("ready", self.page()["status"])

    def test_wrong_wire_route_never_archives_or_queues_result(self):
        import agent_task_result_archive
        import mqtt_bridge

        with patch.object(agent_task_result_archive, "archive", self.archive), \
                patch.object(mqtt_bridge, "queue_task_result") as queued:
            self.assertFalse(mqtt_bridge._publish_or_queue_task_result(None,
                {"scheme": "signal", "_client_route_id": "other-phone"}, self.payload))
            queued.assert_not_called()
        self.assertEqual("unavailable", self.page()["status"])


if __name__ == "__main__":
    unittest.main()
