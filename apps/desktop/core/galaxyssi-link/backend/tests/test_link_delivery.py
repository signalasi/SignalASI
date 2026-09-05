from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import link_delivery


class LinkDeliveryTest(unittest.TestCase):
    def test_broker_accepted_messages_wait_for_application_ack_before_retry(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "delivery.db"
            with (
                patch.object(link_delivery, "DB_PATH", database),
                patch.object(link_delivery.time, "time", return_value=100.0),
            ):
                link_delivery.queue_outbound("client", "message", "topic", "wire")
                self.assertEqual(1, len(link_delivery.pending_outbound()))

                link_delivery.mark_outbound_sending("client", "message")
                link_delivery.mark_outbound_published("client", "message")

                self.assertEqual([], link_delivery.pending_outbound())
                self.assertEqual([], link_delivery.pending_outbound(now=104.999))
                self.assertEqual("message", link_delivery.pending_outbound(now=105.0)[0]["message_id"])
                self.assertEqual("published", link_delivery.outbound_status("client", "message"))
                self.assertTrue(link_delivery.acknowledge_outbound("client", "message"))
                self.assertIsNone(link_delivery.outbound_status("client", "message"))

    def test_retry_budget_quarantines_exhausted_message(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "delivery.db"
            with (
                patch.object(link_delivery, "DB_PATH", database),
                patch.object(link_delivery.time, "time", return_value=100.0),
            ):
                link_delivery.queue_outbound("client", "persistent", "topic", "wire")
                for _ in range(12):
                    link_delivery.mark_outbound_sending("client", "persistent")
                    link_delivery.mark_outbound_retryable("client", "persistent")

                pending = link_delivery.pending_outbound(max_attempts=8, now=10_000.0)
                self.assertEqual([], pending)
                failed = link_delivery.fail_exhausted_outbound(max_attempts=8)
                self.assertEqual("persistent", failed[0]["message_id"])
                self.assertEqual(12, failed[0]["attempts"])
                self.assertEqual("failed", link_delivery.outbound_status("client", "persistent"))

    def test_exhausted_route_does_not_block_another_route(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "delivery.db"
            with (
                patch.object(link_delivery, "DB_PATH", database),
                patch.object(link_delivery.time, "time", return_value=100.0),
            ):
                link_delivery.queue_outbound("poisoned", "old", "old/topic", "bad-wire")
                link_delivery.queue_outbound("healthy", "new", "new/topic", "good-wire")
                for _ in range(link_delivery.OUTBOUND_MAX_ATTEMPTS):
                    link_delivery.mark_outbound_sending("poisoned", "old")
                    link_delivery.mark_outbound_retryable("poisoned", "old")

                link_delivery.fail_exhausted_outbound()
                pending = link_delivery.pending_outbound(now=10_000.0)

                self.assertEqual(["new"], [item["message_id"] for item in pending])

    def test_inflight_count_and_batch_limit_apply_backpressure(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "delivery.db"
            with (
                patch.object(link_delivery, "DB_PATH", database),
                patch.object(link_delivery.time, "time", return_value=100.0),
            ):
                for index in range(6):
                    link_delivery.queue_outbound("client", f"message-{index}", "topic", "wire")
                link_delivery.mark_outbound_sending("client", "message-0")
                link_delivery.mark_outbound_sending("client", "message-1")

                self.assertEqual(2, link_delivery.outbound_inflight_count(now=101.0))
                due = link_delivery.pending_outbound(limit=2, now=101.0)
                self.assertEqual(["message-2", "message-3"], [item["message_id"] for item in due])

    def test_terminal_message_precedes_older_progress_when_route_is_full(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "delivery.db"
            with patch.object(link_delivery, "DB_PATH", database):
                with patch.object(link_delivery.time, "time", return_value=100.0):
                    link_delivery.queue_outbound(
                        "client", "progress-1", "control", "wire-1", priority=10,
                    )
                    link_delivery.queue_outbound(
                        "client", "progress-2", "control", "wire-2", priority=10,
                    )
                    link_delivery.mark_outbound_sending("client", "progress-1")
                    link_delivery.mark_outbound_sending("client", "progress-2")
                with patch.object(link_delivery.time, "time", return_value=101.0):
                    link_delivery.queue_outbound(
                        "client", "final", "down", "wire-final", priority=100,
                    )

                with patch.object(link_delivery.time, "time", return_value=101.0):
                    pending = link_delivery.pending_outbound(
                        client_route_id="client",
                        now=101.0,
                    )

                self.assertEqual(["final"], [item["message_id"] for item in pending])
                self.assertEqual(100, pending[0]["priority"])

    def test_route_filter_keeps_failed_ciphertexts_in_order(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "delivery.db"
            with patch.object(link_delivery, "DB_PATH", database):
                with patch.object(link_delivery.time, "time", return_value=100.0):
                    link_delivery.queue_outbound("current", "first", "topic", "wire-1")
                    link_delivery.queue_outbound("offline", "other", "topic", "wire-old")
                with patch.object(link_delivery.time, "time", return_value=101.0):
                    link_delivery.mark_outbound_sending("offline", "other")
                    link_delivery.mark_outbound_sending("current", "first")
                    link_delivery.mark_outbound_retryable("current", "first")
                with patch.object(link_delivery.time, "time", return_value=102.0):
                    link_delivery.queue_outbound("current", "second", "topic", "wire-2")

                with patch.object(link_delivery.time, "time", return_value=102.0):
                    self.assertEqual(
                        1,
                        link_delivery.outbound_inflight_count(
                            now=102.0,
                            client_route_id="offline",
                        ),
                    )
                    self.assertEqual(
                        [],
                        link_delivery.pending_outbound(
                            now=105.999,
                            client_route_id="current",
                        ),
                    )
                    self.assertEqual(
                        ["first", "second"],
                        [
                            item["message_id"]
                            for item in link_delivery.pending_outbound(
                                now=106.0,
                                client_route_id="current",
                            )
                        ],
                    )

    @staticmethod
    def result_payload(task_id, route, **changes):
        return {"task_id": task_id, "client_route_id": route, "conversation_id": "conversation",
                "turn_id": "turn", "contact_id": "contact", "source_message_id": "source",
                "agent_id": "codex", **changes}

    def test_transport_epoch_clears_only_broker_bound_ciphertexts_once(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "delivery.db"
            with patch.object(link_delivery, "DB_PATH", database):
                link_delivery.queue_outbound("client", "old", "topic", "wire")
                link_delivery.queue_task_result(
                    "old-task",
                    "client",
                    {"_client_route_id": "client"},
                    self.result_payload("old-task", "client", content="old"),
                )
                self.assertTrue(link_delivery.ensure_transport_epoch("v2"))
                self.assertEqual([], link_delivery.pending_outbound())
                self.assertEqual("old-task", link_delivery.pending_task_results()[0]["task_id"])

                link_delivery.queue_outbound("client", "current", "topic", "wire")
                self.assertFalse(link_delivery.ensure_transport_epoch("v2"))
                self.assertEqual("current", link_delivery.pending_outbound()[0]["message_id"])

    def test_task_result_outbox_survives_restart_until_transport_preparation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "delivery.db"
            with patch.object(link_delivery, "DB_PATH", database):
                link_delivery.queue_task_result(
                    "task-1",
                    "client-1",
                    {"scheme": "signal", "_client_route_id": "client-1"},
                    {
                        **self.result_payload("task-1", "client-1"),
                        "message_id": "5a22fe7b-8ef9-54c2-9c90-3120f17d277e",
                        "content": "completed",
                    },
                )
                link_delivery.queue_task_result(
                    "task-1",
                    "client-1",
                    {"scheme": "signal", "_client_route_id": "client-1"},
                    {
                        **self.result_payload("task-1", "client-1"),
                        "message_id": "5a22fe7b-8ef9-54c2-9c90-3120f17d277e",
                        "content": "completed",
                    },
                )

                pending = link_delivery.pending_task_results()
                self.assertEqual(1, len(pending))
                self.assertEqual("task-1", pending[0]["task_id"])
                self.assertEqual("completed", pending[0]["payload"]["content"])

                link_delivery.remove_task_result(pending[0])
                self.assertEqual([], link_delivery.pending_task_results())

    def test_route_topic_and_payload_are_not_plaintext_at_rest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "delivery.db"
            with patch.object(link_delivery, "DB_PATH", database):
                link_delivery.queue_outbound(
                    "client-route-secret",
                    "message-secret",
                    "o" * 43,
                    '{"content":"payload-secret"}',
                )
                link_delivery.queue_task_result(
                    "task-secret",
                    "client-route-secret",
                    {"body": "wire-secret", "_client_route_id": "client-route-secret"},
                    self.result_payload("task-secret", "client-route-secret", content="result-secret"),
                )

                self.assertEqual(
                    "client-route-secret",
                    link_delivery.pending_outbound()[0]["client_route_id"],
                )
                self.assertEqual(
                    "result-secret",
                    link_delivery.pending_task_results()[0]["payload"]["content"],
                )
                persisted = database.read_bytes()
                for secret in (
                    b"client-route-secret",
                    b"server-secret",
                    b"payload-secret",
                    b"wire-secret",
                    b"result-secret",
                ):
                    self.assertNotIn(secret, persisted)

    def test_discard_route_removes_only_revoked_client_delivery_state(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "delivery.db"
            with patch.object(link_delivery, "DB_PATH", database):
                for route_id in ("revoked", "active"):
                    link_delivery.claim_message(route_id, f"inbound-{route_id}")
                    link_delivery.bind_ciphertext(
                        route_id,
                        f"digest-{route_id}",
                        f"inbound-{route_id}",
                    )
                    link_delivery.queue_outbound(
                        route_id,
                        f"outbound-{route_id}",
                        f"topic/{route_id}",
                        f"wire-{route_id}",
                    )
                    link_delivery.queue_task_result(
                        f"task-{route_id}",
                        route_id,
                        {"_client_route_id": route_id},
                        self.result_payload(f"task-{route_id}", route_id, content=route_id),
                    )

                removed = link_delivery.discard_route("revoked")

                self.assertEqual(
                    {
                        "inbound_messages": 1,
                        "inbound_ciphertexts": 1,
                        "outbound_messages": 1,
                        "task_results": 1,
                    },
                    removed,
                )
                self.assertEqual(
                    ["outbound-active"],
                    [item["message_id"] for item in link_delivery.pending_outbound()],
                )
                self.assertEqual(
                    ["task-active"],
                    [item["task_id"] for item in link_delivery.pending_task_results()],
                )


if __name__ == "__main__":
    unittest.main()
