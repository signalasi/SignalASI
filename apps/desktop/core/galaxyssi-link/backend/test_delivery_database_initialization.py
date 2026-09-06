"""Initialization faults, real concurrency, and restart without production state."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

import link_delivery
from test_agent_task_result_outbox import outcome


class TrackedConnection:
    def __init__(self, connection, fault=None):
        self.connection, self.fault, self.closed = connection, fault, False

    def execute(self, sql, *args):
        if self.fault is not None:
            fault, self.fault = self.fault, None
            raise fault
        return self.connection.execute(sql, *args)

    def close(self):
        self.closed = True
        self.connection.close()

    def __getattr__(self, name):
        return getattr(self.connection, name)


class DeliveryDatabaseInitializationTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="delivery-init-")
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.path = self.root / "delivery.db"
        target = patch.object(link_delivery, "DB_PATH", self.path)
        target.start()
        self.addCleanup(target.stop)
        self.real_connect = sqlite3.connect

    def tracked_factory(self, faults):
        opened = []
        def connect(*args, **kwargs):
            item = TrackedConnection(self.real_connect(*args, **kwargs), faults.pop(0) if faults else None)
            opened.append(item)
            return item
        return opened, connect

    def error(self, code, message="injected SQLite initialization fault"):
        error = sqlite3.OperationalError(message)
        error.sqlite_errorcode = code
        return error

    def enqueue(self, number):
        payload = outcome(task_id=f"task-{number}")
        return link_delivery.queue_task_result(payload["task_id"], "phone", {"_client_route_id": "phone"}, payload)

    def test_busy_and_extended_locked_errors_retry_with_fresh_closed_connections(self):
        opened, factory = self.tracked_factory([
            self.error(sqlite3.SQLITE_BUSY), self.error(sqlite3.SQLITE_LOCKED | (1 << 8))])
        with patch.object(link_delivery.sqlite3, "connect", side_effect=factory), \
                patch.object(link_delivery, "time", wraps=time) as clock:
            db = link_delivery._connect()
        try:
            self.assertEqual([True, True, False], [item.closed for item in opened])
            self.assertEqual(2, clock.sleep.call_count)
            self.assertEqual("wal", db.execute("PRAGMA journal_mode").fetchone()[0])
        finally:
            db.close()

    def test_non_busy_errors_and_interruptions_close_without_retry(self):
        errors = [self.error(sqlite3.SQLITE_CORRUPT), sqlite3.IntegrityError("constraint"),
                  sqlite3.OperationalError("database is locked but no structured code"), KeyboardInterrupt()]
        for error in errors:
            with self.subTest(error=type(error).__name__):
                opened, factory = self.tracked_factory([error])
                with patch.object(link_delivery.sqlite3, "connect", side_effect=factory), \
                        patch.object(link_delivery, "time", wraps=time) as clock:
                    with self.assertRaises(type(error)) as caught:
                        link_delivery._connect()
                self.assertIs(error, caught.exception)
                self.assertEqual([True], [item.closed for item in opened])
                clock.sleep.assert_not_called()
        self.path.unlink()

    def test_persistent_busy_stops_at_initialization_deadline_and_closes(self):
        error = self.error(sqlite3.SQLITE_BUSY)
        opened, factory = self.tracked_factory([error])
        with patch.object(link_delivery.sqlite3, "connect", side_effect=factory), \
                patch.object(link_delivery, "time", wraps=time) as clock:
            clock.monotonic.side_effect = [0, 0, 11]
            with self.assertRaises(sqlite3.OperationalError) as caught:
                link_delivery._connect()
        self.assertIs(error, caught.exception)
        self.assertTrue(opened[0].closed)
        clock.sleep.assert_not_called()

    def test_failed_initialization_rolls_back_and_allows_immediate_reopen(self):
        db = link_delivery._connect()
        db.close()
        original = self.enqueue("preserved")
        error = sqlite3.IntegrityError("injected before schema commit")
        def initialize(connection):
            connection.execute("BEGIN IMMEDIATE")
            connection.execute("DELETE FROM task_result_queue")
            raise error
        with patch.object(link_delivery, "_initialize_connection", side_effect=initialize):
            with self.assertRaises(sqlite3.IntegrityError):
                link_delivery._connect()
        self.assertEqual([original], link_delivery.pending_task_results())

    def test_repeated_first_open_with_eight_threads_preserves_all_results(self):
        for trial in range(12):
            with self.subTest(trial=trial), patch.object(link_delivery, "DB_PATH", self.root / f"burst-{trial}.db"):
                barrier = threading.Barrier(8)
                def run(worker):
                    barrier.wait(timeout=10)
                    return [self.enqueue(worker * 3 + i) for i in range(3)]
                with ThreadPoolExecutor(max_workers=8) as pool:
                    records = [record for batch in pool.map(run, range(8)) for record in batch]
                self.assertEqual(24, len({record["scope"] for record in records}))
                self.assertEqual({record["scope"] for record in records},
                                 {record["scope"] for record in link_delivery.pending_task_results()})

    def test_warm_reopen_does_not_reset_wal_or_change_encrypted_state(self):
        record = self.enqueue("preserved")
        keys = {path.name: path.read_bytes() for path in self.root.glob(".galaxyssi-state-key*")}
        statements = []
        def connect(*args, **kwargs):
            db = self.real_connect(*args, **kwargs)
            db.set_trace_callback(statements.append)
            return db
        with patch.object(link_delivery.sqlite3, "connect", side_effect=connect):
            self.assertEqual([record], link_delivery.pending_task_results())
        self.assertNotIn("PRAGMA journal_mode=WAL", statements)
        self.assertEqual(keys, {path.name: path.read_bytes() for path in self.root.glob(".galaxyssi-state-key*")})

    def run_process_burst(self, *, legacy=False):
        if legacy:
            with closing(self.real_connect(self.path)) as db:
                db.execute("CREATE TABLE outbound_messages (client_route_id TEXT NOT NULL, message_id TEXT NOT NULL, "
                           "topic TEXT NOT NULL, wire_payload TEXT NOT NULL, created_at REAL NOT NULL, "
                           "updated_at REAL NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL, "
                           "PRIMARY KEY(client_route_id,message_id))")
                db.commit()
        script = """
import json,sys,time
from pathlib import Path
import link_delivery
from test_agent_task_result_outbox import outcome
root=Path(sys.argv[1]); worker=int(sys.argv[2]); link_delivery.DB_PATH=root/'delivery.db'
(root/f'ready-{worker}').touch()
deadline=time.monotonic()+20
while not (root/'go').exists():
    if time.monotonic()>deadline: raise TimeoutError('Parent did not release barrier')
    time.sleep(0.01)
for index in range(4):
    p=outcome(task_id=f'process-{worker}-{index}')
    assert link_delivery.queue_task_result(p['task_id'],'phone',{'_client_route_id':'phone'},p)
print(json.dumps({'written':4}))
"""
        children = []
        try:
            for worker in range(4):
                children.append(subprocess.Popen([sys.executable, "-c", script, str(self.root), str(worker)],
                    cwd=Path(__file__).parent, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True))
            deadline = time.monotonic() + 20
            while len(list(self.root.glob("ready-*"))) < 4 and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertEqual(4, len(list(self.root.glob("ready-*"))))
            (self.root / "go").touch()
            for child in children:
                stdout, stderr = child.communicate(timeout=30)
                self.assertEqual(0, child.returncode, stderr)
                self.assertEqual({"written": 4}, json.loads(stdout))
        finally:
            for child in children:
                if child.poll() is None:
                    child.kill()
                child.communicate(timeout=10)
        records = link_delivery.pending_task_results()
        self.assertEqual(16, len({item["scope"] for item in records}))
        with closing(self.real_connect(self.path)) as db:
            self.assertEqual("ok", db.execute("PRAGMA integrity_check").fetchone()[0])
            self.assertEqual("wal", db.execute("PRAGMA journal_mode").fetchone()[0])
        # A separate process reopens the committed database and verifies all rows.
        result = subprocess.run([sys.executable, "-c",
            "import sys; from pathlib import Path; import link_delivery; "
            "link_delivery.DB_PATH=Path(sys.argv[1]); "
            "print(len(link_delivery.pending_task_results()))", str(self.path)],
            cwd=Path(__file__).parent, capture_output=True, text=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("16", result.stdout.strip())

    def test_four_processes_initialize_fresh_database_and_reopen(self):
        self.run_process_burst()

    def test_four_processes_upgrade_legacy_priority_column_and_reopen(self):
        self.run_process_burst(legacy=True)
