"""Regression gates must fail even when a launcher or a transport-only test succeeds."""
import importlib.util
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
