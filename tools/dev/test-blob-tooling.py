"""Regression gates must fail even when a launcher or a transport-only test succeeds."""
import importlib.util
import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
spec = importlib.util.spec_from_file_location("blob_interop", HERE / "test-android-blob-interoperability.py")
interop = importlib.util.module_from_spec(spec)
spec.loader.exec_module(interop)
device_spec = importlib.util.spec_from_file_location("blob_device", HERE / "test-android-blob-device.py")
device = importlib.util.module_from_spec(device_spec)
device_spec.loader.exec_module(device)


class BlobToolingTest(unittest.TestCase):
    @staticmethod
    def device_result():
        return {"result": "passed", "sha256_verified": True, "each_chunk_uploaded_once": True,
                "intentional_app_process_death": True, "metrics": {
                    "interrupting": {"baseline_pss_kib": 141471, "sampled_peak_pss_kib": 155406,
                        "sampled_growth_kib": 13935, "prepare_ms": 2724, "tls_rejection_verified": True},
                    "completed": {"resume_checkpoint_ms": 20, "control_probe_max_ms": 103,
                        "main_callback_max_ms": 3, "control_probes": 326, "probe_failures": 0}}}

    def test_measured_bounded_preparation_passes_benchmark(self):
        self.assertTrue(device.device_acceptance(self.device_result())["passed"])

    def test_transport_success_does_not_hide_original_memory_regression(self):
        value = self.device_result()
        value["metrics"]["interrupting"].update(baseline_pss_kib=141308,
            sampled_peak_pss_kib=323821, sampled_growth_kib=182513)
        result = device.device_acceptance(value)
        self.assertFalse(result["passed"])
        self.assertEqual(["preparation_memory_budget_exceeded"], result["failures"])

    def test_device_gate_rejects_missing_metrics_instead_of_treating_them_as_zero(self):
        original = self.device_result()
        for phase, values in original["metrics"].items():
            for key in values:
                with self.subTest(phase=phase, missing=key):
                    value = copy.deepcopy(original)
                    del value["metrics"][phase][key]
                    self.assertFalse(device.device_acceptance(value)["passed"])
        for bad in (None, [], "bad", {}):
            value = copy.deepcopy(original)
            value["metrics"] = bad
            self.assertFalse(device.device_acceptance(value)["passed"])

    def test_device_gate_rejects_noninteger_negative_and_inconsistent_pss(self):
        for bad in (True, -1, 1.5, "13935", float("nan")):
            value = self.device_result()
            value["metrics"]["interrupting"]["sampled_growth_kib"] = bad
            self.assertFalse(device.device_acceptance(value)["passed"])
        value = self.device_result()
        value["metrics"]["interrupting"]["sampled_peak_pss_kib"] = 141470
        self.assertIn("preparation_memory_inconsistent", device.device_acceptance(value)["failures"])

    def test_recovery_and_responsiveness_are_not_replaced_by_transfer_success(self):
        for key, bad in [("resume_checkpoint_ms", 5001), ("control_probe_max_ms", 501),
                         ("main_callback_max_ms", 101), ("probe_failures", 1), ("control_probes", 0)]:
            value = self.device_result()
            value["metrics"]["completed"][key] = bad
            self.assertFalse(device.device_acceptance(value)["passed"], key)

    def test_integrity_process_death_and_tls_require_explicit_evidence(self):
        for key in ("sha256_verified", "each_chunk_uploaded_once", "intentional_app_process_death"):
            for bad in (False, None, 1, "true"):
                value = self.device_result()
                value[key] = bad
                self.assertFalse(device.device_acceptance(value)["passed"])
        value = self.device_result()
        value["result"] = "failed"
        self.assertFalse(device.device_acceptance(value)["passed"])

    def test_benchmark_budget_is_explicit_validated_and_recorded(self):
        for bad in (0, -1, 1025, True, float("inf")):
            with self.assertRaises(ValueError):
                device.device_acceptance(self.device_result(), bad)
        self.assertEqual(16, device.device_acceptance(self.device_result(), 16)["maximum_prepare_growth_mib"])
        self.assertFalse(device.device_acceptance(self.device_result(), 1)["passed"])

    def test_device_acceptance_failure_is_saved_and_returns_nonzero(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            value = self.device_result()
            self.assertEqual(1, device.write_device_report(root, value, 1))
            saved = json.loads((root / "result.json").read_text(encoding="utf-8"))
            self.assertEqual("failed", saved["result"])
            self.assertIn("preparation_memory_budget_exceeded", saved["acceptance"]["failures"])
            self.assertEqual(13935, saved["metrics"]["interrupting"]["sampled_growth_kib"])

    def test_device_runner_rejects_stale_or_ambiguous_app_version(self):
        self.assertEqual("1.0.21", device.checked_version("  versionCode=867\r\n  versionName=1.0.21\r\n", "1.0.21"))
        for details in ("", "versionName=1.0.20", "versionName=1.0.210", "versionName=1.0.21\nversionName=1.0.20"):
            with self.assertRaises(RuntimeError):
                device.checked_version(details, "1.0.21")

    def test_report_failures_are_not_hidden_by_successful_transport(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for failures, errors in [(1, 0), (0, 1), (1, 1)]:
                report = ET.Element("testsuite", tests="3", failures=str(failures), errors=str(errors))
                ET.ElementTree(report).write(root / "TEST-fixture.xml")
                with self.assertRaisesRegex(RuntimeError, "did not pass"):
                    interop.junit_results(root)

    def test_missing_empty_and_malformed_reports_are_not_passes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaises(RuntimeError):
                interop.junit_results(root)
            for body in ['<testsuite tests="0"/>', '<unexpected/>', '<testsuite']:
                (root / "TEST-fixture.xml").write_text(body, encoding="utf-8")
                with self.assertRaises((RuntimeError, ET.ParseError)):
                    interop.junit_results(root)

    def test_skips_and_all_suites_are_reported_without_inflating_passes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for index, count in enumerate([2, 5]):
                ET.ElementTree(ET.Element("testsuite", tests=str(count), skipped="1")).write(root / f"TEST-{index}.xml")
            self.assertEqual(dict(tests=7, failures=0, errors=0, skipped=2), interop.junit_results(root))

    @unittest.skipUnless(os.name == "nt", "Windows batch launcher")
    def test_local_gradle_exit_code_is_preserved_after_parenthesized_call(self):
        with tempfile.TemporaryDirectory(prefix="blob gradle fixture ") as temporary:
            root = Path(temporary)
            wrapper = root / "gradlew.bat"
            shutil.copyfile(ROOT / "apps/android/gradlew.bat", wrapper)
            local = root / ".gradle/wrapper/dists/gradle-8.14.3-all/10utluxaxniiv4wxiphsi49nj/gradle-8.14.3/bin/gradle.bat"
            local.parent.mkdir(parents=True)
            for code in (0, 7, 42):
                local.write_text(f"@echo off\necho test-argument=%1\nexit /b {code}\n", encoding="ascii")
                result = subprocess.run([str(wrapper), "blob-regression"], cwd=root,
                    env={**os.environ, "USERPROFILE": str(root)}, capture_output=True, text=True,
                    timeout=15, creationflags=subprocess.CREATE_NO_WINDOW)
                self.assertIn("test-argument=blob-regression", result.stdout)
                self.assertEqual(code, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
