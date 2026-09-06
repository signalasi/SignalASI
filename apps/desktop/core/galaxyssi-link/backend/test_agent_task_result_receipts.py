from contextlib import closing
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

from agent_task_result_archive import TaskResultArchive
from agent_task_recovery_query import IDENTITY_FIELDS


class TaskResultReceiptTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / "archive.db"
        self.archive = TaskResultArchive(self.path)
        self.fields = dict(zip(IDENTITY_FIELDS, ("route", "conversation", "task", "turn", "contact", "42", "codex")))
        self.body = {**self.fields, "execution_generation": 2, "type": "text", "task_status": "completed",
                     "content": "\u56de\u590d\u786e\u8ba4\u6301\u4e45\u5316\u6d4b\u8bd5"}
        self.request = {**self.fields, **self.archive.put(self.body), "execution_generation": 2,
                        "type": "agent_task_result_received", "receipt_id": "a" * 64}

    def tearDown(self):
        self.directory.cleanup()

    def confirm(self, request=None):
        return self.archive.receipt_confirmation(request or self.request, client_route_id="route")

    def test_confirmation_is_after_durable_body_removal_and_survives_reopen(self):
        first = self.confirm()
        self.assertEqual("agent_task_result_receipt_confirmed", first["type"])
        self.assertEqual(self.request["receipt_id"], first["receipt_id"])
        with closing(sqlite3.connect(self.path)) as db:
            self.assertEqual(0, db.execute("SELECT COUNT(*) FROM pages").fetchone()[0])
            self.assertEqual(1, db.execute("SELECT acknowledged FROM results").fetchone()[0])
        self.archive = TaskResultArchive(self.path)
        self.assertEqual(first, self.confirm())

    def test_lost_confirmation_is_recreated_without_rerunning_agent(self):
        expected = self.confirm()
        for _ in range(100):
            self.assertEqual(expected, self.confirm())
        self.assertNotIn("content", expected)
        self.assertNotIn("rich_output", expected)

    def test_wrong_scope_digest_and_generation_do_not_confirm(self):
        for field in IDENTITY_FIELDS + ("sha256",):
            with self.subTest(field=field):
                self.assertIsNone(self.confirm({**self.request, field: "wrong"}))
        self.assertIsNone(self.confirm({**self.request, "execution_generation": 1}))
        self.assertIsNotNone(self.confirm())

    def test_malformed_receipt_id_cannot_delete_archive(self):
        for value in (None, "", "x" * 64, "a" * 65, 123):
            self.assertIsNone(self.confirm({**self.request, "receipt_id": value}))
        with closing(sqlite3.connect(self.path)) as db:
            self.assertEqual(0, db.execute("SELECT acknowledged FROM results").fetchone()[0])

    def test_unknown_archive_cannot_be_confirmed(self):
        self.assertIsNone(self.confirm({**self.request, "task_id": "unknown"}))

    def test_storage_failure_never_emits_confirmation(self):
        with patch.object(self.archive, "acknowledge", side_effect=sqlite3.OperationalError("disk full")):
            with self.assertRaises(sqlite3.OperationalError):
                self.confirm()
        self.assertIsNotNone(self.confirm())

    def test_old_generation_receipt_does_not_remove_new_body(self):
        newer = self.archive.put({**self.body, "execution_generation": 3, "content": "new"})
        self.assertIsNotNone(self.confirm())
        result = self.archive.page({**self.fields, "execution_generation": 3, "page_index": 0,
                                    "request_id": "read", **newer}, client_route_id="route")
        self.assertEqual("ready", result["status"])


if __name__ == "__main__":
    unittest.main()
