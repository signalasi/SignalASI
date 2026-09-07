from dataclasses import asdict
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from agent_latency import AgentLatencyTracer, summarize
from agent_recovery_timing import recovery_timing
from agent_task_recovery_query import IDENTITY_FIELDS, TASK_FIELDS, recovery_query
from agent_task_result_archive import TaskResultArchive


class Sink:
    def __init__(self):
        self.points = []

    def append(self, point):
        self.points.append(point)


class RecoveryTimingTest(unittest.TestCase):
    def setUp(self):
        self.sink = Sink()
        self.tracer = AgentLatencyTracer(self.sink)
        self.record = patch("agent_recovery_timing.record_task", side_effect=self.tracer.record)
        self.record.start()
        self.addCleanup(self.record.stop)
        self.fields = dict(zip(IDENTITY_FIELDS, (
            "private-route", "private-conversation", "private-task", "private-turn",
            "private-contact", "42", "codex"))) | {
                "request_id": "private-query", "execution_generation": 2, "page_index": 0}

    def metric(self, phase):
        return summarize(self.sink.points)["metrics"][f"desktop_recovery_{phase}_ms"]

    def test_exact_monotonic_span(self):
        with patch("agent_recovery_timing.now_ns", side_effect=[1_000_000, 7_500_000]):
            with recovery_timing(self.fields, "page") as measurement:
                measurement.completed = True
        self.assertEqual(6.5, self.metric("page")["p95_ms"])

    def test_repeated_page_calls_in_one_request_are_separate_attempts(self):
        for completed in [False, True]:
            with recovery_timing(self.fields, "page") as measurement:
                measurement.completed = completed
        self.assertEqual((1, 1), (self.metric("page")["count"], self.metric("page")["unsuccessful"]))
        self.assertNotEqual(self.sink.points[0].operation_id, self.sink.points[2].operation_id)

    def test_failed_remote_task_can_be_a_successful_status_lookup(self):
        manager = Mock(spec=["recovery_snapshot"])
        manager.recovery_snapshot.return_value = dict(zip(TASK_FIELDS,
            [self.fields[key] for key in IDENTITY_FIELDS])) | {"status": "failed", "execution_generation": 2}
        result = recovery_query({"client_route_id": "private-route", "request_id": "private-query",
                                 "items": [self.fields]}, client_route_id="private-route", manager=manager)
        self.assertEqual("failed", result["items"][0]["status"])
        self.assertEqual(1, self.metric("lookup")["count"])
        manager.recovery_snapshot.assert_called_once()

    def test_unavailable_status_is_not_a_fast_success(self):
        manager = Mock(spec=["recovery_snapshot"])
        manager.recovery_snapshot.return_value = None
        recovery_query({"client_route_id": "private-route", "request_id": "private-query",
                        "items": [self.fields]}, client_route_id="private-route", manager=manager)
        self.assertEqual((0, 1, None), (self.metric("lookup")["count"],
            self.metric("lookup")["unsuccessful"], self.metric("lookup")["p95_ms"]))

    def test_query_rejection_does_not_emit_or_open_storage(self):
        manager = Mock(spec=["recovery_snapshot"])
        result = recovery_query({"client_route_id": "wrong-route", "request_id": "private-query",
                                 "items": [self.fields]}, client_route_id="private-route", manager=manager)
        self.assertIsNone(result)
        self.assertEqual([], self.sink.points)
        manager.recovery_snapshot.assert_not_called()

    def test_original_exception_is_preserved_and_span_is_unsuccessful(self):
        error = RuntimeError("private error detail")
        with self.assertRaises(RuntimeError) as raised:
            with recovery_timing(self.fields, "restore") as measurement:
                measurement.completed = True
                raise error
        self.assertIs(error, raised.exception)
        self.assertEqual(1, self.metric("restore")["unsuccessful"])
        self.assertNotIn("private error detail", json.dumps([asdict(p) for p in self.sink.points]))

    def test_diagnostic_failures_cannot_change_operation_result(self):
        with patch("agent_recovery_timing.record_task", side_effect=OSError("full")):
            with recovery_timing(self.fields, "publish") as measurement:
                measurement.completed = True
        self.assertEqual([], self.sink.points)

    def test_no_plaintext_identity_content_or_request_ids(self):
        with recovery_timing(self.fields | {"content": "secret answer", "error": "private exception"}, "page") as measurement:
            measurement.completed = True
        encoded = json.dumps([asdict(p) for p in self.sink.points])
        for forbidden in ["private-", "secret answer", "private exception"]:
            self.assertNotIn(forbidden, encoded)
        self.assertEqual(64, len(self.sink.points[0].operation_id))

    def test_invalid_timing_metadata_does_not_prevent_business_work(self):
        for fields in [None, {}, self.fields | {"execution_generation": True},
                       self.fields | {"page_index": -2}, self.fields | {"request_id": "x" * 129}]:
            with recovery_timing(fields, "page") as measurement:
                measurement.completed = True
        self.assertEqual([], self.sink.points)

    def test_scopes_and_generations_do_not_join(self):
        for key in IDENTITY_FIELDS:
            with recovery_timing(self.fields | {key: "another"}, "page") as measurement:
                measurement.completed = True
        for generation in [1, 3]:
            with recovery_timing(self.fields | {"execution_generation": generation}, "page") as measurement:
                measurement.completed = True
        self.assertEqual(9, self.metric("page")["count"])
        self.assertEqual(9, len({p.operation_id for p in self.sink.points}))

    def test_real_encrypted_archive_missing_then_ready_same_request(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = TaskResultArchive(Path(directory) / "archive.db")
            self.assertEqual("unavailable", archive.page(self.fields, client_route_id="private-route")["status"])
            archive.put(self.fields | {"type": "text", "task_status": "completed", "content": "actual archived text"})
            response = archive.page(self.fields, client_route_id="private-route")
            self.assertEqual("ready", response["status"])
        self.assertEqual((1, 1), (self.metric("page")["count"], self.metric("page")["unsuccessful"]))

    def test_real_archive_rejects_other_route_before_any_timing_or_io(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = TaskResultArchive(Path(directory) / "archive.db")
            self.assertIsNone(archive.page(self.fields, client_route_id="other-route"))
            self.assertFalse(archive.path.exists())
        self.assertEqual([], self.sink.points)
