"""Isolated JVM process-death recovery; never opens the production identity store."""
import socket
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

import galaxyssi_client as client
from signal_sidecar_supervisor import SignalSidecarSupervisor


class SignalRecoveryLiveTest(unittest.TestCase):
    def test_process_death_recovers_same_identity_and_nonblocking_health(self):
        script = client.resolve_sidecar_script()
        if script is None:
            self.skipTest("Build signal_sidecar installDist before live JVM acceptance")
        with tempfile.TemporaryDirectory(prefix="galaxyssi-sidecar-chaos-") as directory:
            root = Path(directory)
            with socket.socket() as listener:
                listener.bind(("127.0.0.1", 0))
                port = listener.getsockname()[1]
            with patch.multiple(client, _process=None, SIDECAR_DIR=root, SIDECAR_PORT=port,
                                SIDECAR_BASE=f"http://127.0.0.1:{port}", SIGNAL_STORE_PATH=root / "identity.json"), \
                    patch.object(client, "resolve_sidecar_script", return_value=script):
                runtime = SignalSidecarSupervisor(client._is_healthy, client.start_signal_sidecar)
                try:
                    runtime.start()
                    self.wait_ready(runtime)
                    original = client._request("GET", "/bundle")["identityKeySha256"]
                    old_process = client._process
                    self.assertIsNotNone(old_process)
                    client._terminate_process(old_process)
                    start = time.monotonic()
                    latencies = []
                    saw_unready = False
                    deadline = start + 20
                    while time.monotonic() < deadline:
                        tick = time.perf_counter()
                        status = runtime.snapshot()
                        latencies.append(time.perf_counter() - tick)
                        saw_unready |= not status["ready"]
                        if client._process is not old_process and status["ready"]:
                            break
                        time.sleep(.01)
                    else:
                        self.fail("Sidecar did not recover after process death")
                    self.assertTrue(saw_unready)
                    self.assertEqual(original, client._request("GET", "/bundle")["identityKeySha256"])
                    self.assertLess(max(latencies), .1)
                    print(f"\nISOLATED_SIDECAR recovery_ms={(time.monotonic() - start) * 1000:.1f} "
                          f"health_max_ms={max(latencies) * 1000:.3f} identity_preserved=true", flush=True)
                finally:
                    runtime.stop()
                    client.stop_signal_sidecar()
                self.assertFalse(runtime.snapshot()["ready"])

    def wait_ready(self, runtime):
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            if runtime.snapshot()["ready"]:
                return
            time.sleep(.01)
        self.fail(f"Isolated sidecar failed to start: {runtime.snapshot()}")
