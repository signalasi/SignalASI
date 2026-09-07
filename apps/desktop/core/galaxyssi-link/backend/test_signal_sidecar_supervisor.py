"""Readiness must describe the encrypted path, without blocking the health caller."""
import threading
import time
import unittest
from unittest.mock import patch

from signal_sidecar_supervisor import SignalSidecarSupervisor, encrypted_transport_health


class SignalSupervisorTest(unittest.TestCase):
    def wait_for(self, predicate, timeout=2):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(.005)
        self.fail("Supervisor did not reach the expected state")

    def runtime(self, probe, recover=lambda: None, **kwargs):
        runtime = SignalSidecarSupervisor(probe, recover, interval=.01, **kwargs)
        self.addCleanup(runtime.stop)
        return runtime

    def test_broker_connected_does_not_mean_encrypted_transport_ready(self):
        for broker in (False, True):
            for crypto in (False, True):
                bridge = {"connected": True, "ready": broker}
                result = encrypted_transport_health(bridge, {"ready": crypto})
                self.assertEqual(broker and crypto, result["ready"])
                self.assertEqual("ok", result["status"])
                self.assertIs(bridge, result["message_bridge"])

    def test_missing_flags_and_truthy_strings_never_mean_ready(self):
        for bridge, crypto in (({}, {}), ({"ready": "true"}, {"ready": True}),
                               ({"ready": True}, {"ready": 1})):
            self.assertFalse(encrypted_transport_health(bridge, crypto)["ready"])

    def test_healthy_probe_does_not_restart_sidecar(self):
        recoveries = []
        runtime = self.runtime(lambda: True, lambda: recoveries.append(True))
        runtime.start(); runtime.start()
        self.wait_for(lambda: runtime.snapshot()["ready"])
        self.assertEqual([], recoveries)
        self.assertEqual(0, runtime.snapshot()["recovery_attempts"])

    def test_missing_runtime_recovers_after_dependency_is_provided(self):
        available = threading.Event()
        healthy = threading.Event()
        def recover():
            if not available.is_set():
                raise FileNotFoundError("private/path/runtime secret-details")
            healthy.set()
        runtime = self.runtime(healthy.is_set, recover)
        runtime.start()
        self.wait_for(lambda: runtime.snapshot()["error_code"] == "signal_runtime_missing")
        self.assertNotIn("private", str(runtime.snapshot()))
        available.set()
        self.wait_for(lambda: runtime.snapshot()["ready"])

    def test_probe_failure_triggers_recovery(self):
        healthy = threading.Event()
        def probe():
            if not healthy.is_set():
                raise TimeoutError("unresponsive")
            return True
        runtime = self.runtime(probe, healthy.set)
        runtime.start()
        self.wait_for(lambda: runtime.snapshot()["ready"])
        self.assertEqual(1, runtime.snapshot()["recovery_attempts"])

    def test_failed_recovery_is_not_reported_as_ready(self):
        def recover():
            raise RuntimeError("raw credential or path must not escape")
        runtime = self.runtime(lambda: False, recover)
        runtime.start()
        self.wait_for(lambda: runtime.snapshot()["recovery_attempts"] > 1)
        self.assertFalse(runtime.snapshot()["ready"])
        self.assertNotIn("credential", str(runtime.snapshot()))

    def test_slow_recovery_does_not_block_health_snapshot(self):
        entered, release = threading.Event(), threading.Event()
        def recover():
            entered.set(); release.wait(3)
        runtime = self.runtime(lambda: False, recover)
        self.addCleanup(release.set)
        runtime.start(); self.assertTrue(entered.wait(1))
        before = time.monotonic()
        for _ in range(100):
            self.assertFalse(runtime.snapshot()["ready"])
        self.assertLess(time.monotonic() - before, .2)
        release.set()

    def test_cached_health_expires_if_probe_stalls(self):
        entered, release = threading.Event(), threading.Event()
        clock = [10.0]
        calls = [0]
        def probe():
            calls[0] += 1
            if calls[0] > 1:
                entered.set(); release.wait(3)
            return True
        runtime = self.runtime(probe, clock=lambda: clock[0], max_age=1)
        self.addCleanup(release.set)
        runtime.start(); self.assertTrue(entered.wait(1))
        self.assertTrue(runtime.snapshot()["ready"])
        clock[0] = 12
        self.assertFalse(runtime.snapshot()["ready"])
        self.assertEqual("signal_runtime_observation_stale", runtime.snapshot()["error_code"])
        release.set()

    def test_stop_cannot_report_a_cached_ready_or_restart_a_process(self):
        recoveries = []
        runtime = self.runtime(lambda: True, lambda: recoveries.append(True))
        runtime.start(); self.wait_for(lambda: runtime.snapshot()["ready"])
        runtime.stop()
        self.assertFalse(runtime.snapshot()["ready"])
        self.assertFalse(runtime.snapshot()["supervised"])
        self.assertEqual([], recoveries)

    def test_sidecar_probe_has_short_timeout_but_crypto_requests_keep_existing_budget(self):
        import galaxyssi_client
        with patch.object(galaxyssi_client, "_request", return_value={}) as request:
            self.assertFalse(galaxyssi_client._is_healthy())
        request.assert_called_once_with("GET", "/health", timeout=.5)
