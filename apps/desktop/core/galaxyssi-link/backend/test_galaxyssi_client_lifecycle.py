import threading
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
from unittest.mock import mock_open

import galaxyssi_client


class SignalSidecarLifecycleTests(unittest.TestCase):
    def setUp(self):
        self.original_process = galaxyssi_client._process
        self.original_port = galaxyssi_client.SIDECAR_PORT
        self.original_base = galaxyssi_client.SIDECAR_BASE
        galaxyssi_client._process = None

    def tearDown(self):
        galaxyssi_client._process = self.original_process
        galaxyssi_client.SIDECAR_PORT = self.original_port
        galaxyssi_client.SIDECAR_BASE = self.original_base

    def test_concurrent_callers_share_one_sidecar_startup(self):
        process = Mock(pid=321)
        process.poll.return_value = None
        healthy = iter([False, True, True])
        errors = []

        def start():
            try:
                galaxyssi_client.start_signal_sidecar()
            except Exception as error:  # pragma: no cover - asserted below
                errors.append(error)

        with patch.object(galaxyssi_client, "_is_healthy", side_effect=lambda: next(healthy, True)), \
                patch.object(Path, "exists", return_value=True), \
                patch.object(galaxyssi_client, "_port_is_in_use", return_value=False), \
                patch.object(galaxyssi_client, "derived_storage_key", return_value=b"x" * 32), \
                patch.object(galaxyssi_client, "open", mock_open()), \
                patch.object(galaxyssi_client.subprocess, "Popen", return_value=process) as popen:
            callers = [threading.Thread(target=start) for _ in range(2)]
            for caller in callers:
                caller.start()
            for caller in callers:
                caller.join(timeout=2)

        self.assertEqual([], errors)
        self.assertEqual(1, popen.call_count)

    def test_failed_startup_terminates_spawned_process(self):
        process = Mock(pid=654)
        process.poll.return_value = 1

        with patch.object(galaxyssi_client, "_is_healthy", return_value=False), \
                patch.object(Path, "exists", return_value=True), \
                patch.object(galaxyssi_client, "_port_is_in_use", return_value=False), \
                patch.object(galaxyssi_client, "derived_storage_key", return_value=b"x" * 32), \
                patch.object(galaxyssi_client, "open", mock_open()), \
                patch.object(galaxyssi_client.subprocess, "Popen", return_value=process), \
                patch.object(galaxyssi_client, "_terminate_process") as terminate:
            with self.assertRaisesRegex(RuntimeError, "did not become healthy"):
                galaxyssi_client.start_signal_sidecar()

        terminate.assert_called_once_with(process)
        self.assertIsNone(galaxyssi_client._process)

    def test_failed_stop_retains_the_owned_handle_for_retry(self):
        process = Mock(pid=654)
        process.poll.return_value = None
        galaxyssi_client._process = process
        with patch.object(galaxyssi_client, "_terminate_process", side_effect=RuntimeError("still stopping")):
            with self.assertRaisesRegex(RuntimeError, "still stopping"):
                galaxyssi_client.stop_signal_sidecar()
        self.assertIs(process, galaxyssi_client._process)

    def test_failed_stale_process_stop_cannot_spawn_a_second_sidecar(self):
        process = Mock(pid=654)
        process.poll.return_value = None
        galaxyssi_client._process = process
        with patch.object(galaxyssi_client, "_is_healthy", return_value=False), \
                patch.object(galaxyssi_client, "_terminate_process", side_effect=RuntimeError("still stopping")), \
                patch.object(galaxyssi_client.subprocess, "Popen") as popen:
            with self.assertRaisesRegex(RuntimeError, "still stopping"):
                galaxyssi_client.start_signal_sidecar()
        popen.assert_not_called()
        self.assertIs(process, galaxyssi_client._process)

    def test_configured_sidecar_runtime_is_discovered(self):
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory) / ("galaxyssi-link-sidecar.bat" if os.name == "nt" else "galaxyssi-link-sidecar")
            script.write_text("", encoding="utf-8")
            with patch.dict(os.environ, {"GALAXYSSI_LINK_SIDECAR_SCRIPT": str(script)}):
                self.assertEqual(script, galaxyssi_client.resolve_sidecar_script())

    def test_missing_local_runtime_falls_back_to_trusted_candidate(self):
        with tempfile.TemporaryDirectory() as directory:
            fallback = Path(directory) / "galaxyssi-link-sidecar.bat"
            fallback.write_text("", encoding="utf-8")
            with patch.object(
                galaxyssi_client,
                "sidecar_script_candidates",
                return_value=[Path(directory) / "missing.bat", fallback],
            ):
                self.assertEqual(fallback, galaxyssi_client.resolve_sidecar_script())


if __name__ == "__main__":
    unittest.main()
