import hashlib
import io
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import signalasi_guest_agent as guest


class GuestProtocolTest(unittest.TestCase):
    def envelope(self):
        return {
            "protocol_version": 1,
            "message_id": "message-1",
            "request_id": "request-1",
            "type": "execute",
            "sequence": 2,
            "timestamp_millis": 1_700_000_000_000,
            "payload": {
                "arguments": ["alpha"],
                "language": "python",
                "limits": {"wall_clock_ms": 1000},
                "workspace_path": "/workspace/a/b",
            },
            "mac": "",
        }

    def test_canonical_payload_contract(self):
        digest = hashlib.sha256(guest.unsigned_payload(self.envelope())).hexdigest()
        self.assertEqual(
            "abfc19846ceaf0b2d26a67314e51dc04c333131ae8a146bec2b29f1960f7b639",
            digest,
        )
        self.assertEqual(
            "bQOnaC5JTWzoMgk+2beIwM/kTTghus6R73k6P1hopg8=",
            guest.sign_envelope(self.envelope(), bytes(range(32)))["mac"],
        )

    def test_sign_verify_and_tamper_detection(self):
        key = bytes(range(32))
        signed = guest.sign_envelope(self.envelope(), key)
        self.assertTrue(guest.verify_envelope(signed, key, now_millis=1_700_000_000_000))
        signed["payload"]["language"] = "shell"
        self.assertFalse(guest.verify_envelope(signed, key, now_millis=1_700_000_000_000))

    def test_unicode_canonicalization_and_float_rejection(self):
        self.assertEqual(
            '{"emoji":"\U0001F600","slash":"a/b","text":"\u4e2d\u6587\\n\\"x\\""}',
            guest.canonical_json(
                {"text": '\u4e2d\u6587\n"x"', "slash": "a/b", "emoji": "\U0001F600"}
            ),
        )
        with self.assertRaises(ValueError):
            guest.canonical_json({"value": 1.5})

    def test_frame_round_trip(self):
        stream = io.BytesIO()
        envelope = guest.sign_envelope(self.envelope(), bytes(range(32)))
        guest.write_frame(stream, envelope)
        stream.seek(0)
        self.assertEqual(envelope, guest.read_frame(stream))

    def test_resource_limits_reject_invalid_wall_clock(self):
        with self.assertRaises(ValueError):
            guest.ExecutionLimits.from_payload({"limits": {"wall_clock_ms": 99}})

    def test_launcher_plan_applies_identity_and_resource_limits(self):
        limits = guest.ExecutionLimits.from_payload({"limits": {}})
        with mock.patch.object(guest, "LAUNCHER_PATH", Path(sys.executable)):
            plan = guest.launcher_plan(
                {"workspace_uid": 10123, "workspace_gid": 10123},
                Path("/workspace/a/request"),
                limits,
                ["/usr/bin/python3", "/work/main.py"],
            )

        self.assertEqual(sys.executable, plan[0])
        self.assertEqual("10123", plan[plan.index("--uid") + 1])
        self.assertEqual("10123", plan[plan.index("--gid") + 1])
        self.assertEqual(
            ["/usr/bin/python3", "/work/main.py"],
            plan[plan.index("--") + 1 :],
        )

    def test_command_plan_resolves_executables_from_mounted_pack_path(self):
        with tempfile.TemporaryDirectory() as directory:
            pack_root = Path(directory) / "python-uv"
            pack_bin = pack_root / "bin"
            pack_bin.mkdir(parents=True)
            uv = pack_bin / "uv"
            uv.touch(mode=0o755)
            uv.chmod(0o755)
            with mock.patch.object(guest, "PACK_ROOT", Path(directory)):
                environment = guest.runtime_environment()
                plan = guest.command_plan("uv", Path("/work"), [], environment["PATH"])

        self.assertEqual(str(uv), plan[0][0])
        self.assertEqual(["run", "--no-cache", "--offline", str(Path("/work") / "main.py")], plan[0][1:])

    def test_command_plan_exposes_ffprobe_as_a_separate_read_only_operation(self):
        with tempfile.TemporaryDirectory() as directory:
            pack_bin = Path(directory) / "ffmpeg" / "bin"
            pack_bin.mkdir(parents=True)
            ffprobe = pack_bin / "ffprobe"
            ffprobe.touch(mode=0o755)
            ffprobe.chmod(0o755)
            with mock.patch.object(guest, "PACK_ROOT", Path(directory)):
                environment = guest.runtime_environment()
                plan = guest.command_plan(
                    "ffprobe",
                    Path("/work"),
                    ["-show_format", "input.mp4"],
                    environment["PATH"],
                )

        self.assertEqual(
            [[str(ffprobe), "-show_format", "input.mp4"]],
            plan,
        )

    def test_command_plan_executes_browser_source_with_the_installed_launcher(self):
        with tempfile.TemporaryDirectory() as directory:
            pack_bin = Path(directory) / "browser-automation" / "bin"
            pack_bin.mkdir(parents=True)
            launcher = pack_bin / "signalasi-browser"
            launcher.touch(mode=0o755)
            launcher.chmod(0o755)
            with mock.patch.object(guest, "PACK_ROOT", Path(directory)):
                environment = guest.runtime_environment()
                plan = guest.command_plan(
                    "browser",
                    Path("/work"),
                    ["--trace"],
                    environment["PATH"],
                )

        self.assertEqual(
            [[str(launcher), str(Path("/work") / "main.browser.js"), "--trace"]],
            plan,
        )

    def test_runtime_environment_keeps_mutable_language_caches_in_private_task_temp(self):
        environment = guest.runtime_environment()
        task_temp = guest.ISOLATED_WORKSPACE_ROOT / ".tmp"
        self.assertEqual("true", environment["CARGO_NET_OFFLINE"])
        self.assertEqual("1", environment["UV_NO_CACHE"])
        self.assertEqual(str(task_temp / "uv-cache"), environment["UV_CACHE_DIR"])
        self.assertEqual(str(task_temp / "cargo"), environment["CARGO_HOME"])
        self.assertEqual(
            str(task_temp / "zig-global-cache"),
            environment["ZIG_GLOBAL_CACHE_DIR"],
        )
        self.assertEqual(str(guest.PACK_ROOT / "java"), environment["JAVA_HOME"])

    def test_secret_environment_is_memory_only_and_strictly_bounded(self):
        environment = {"PATH": "/usr/bin"}
        guest.inject_secret_environment(environment, {"ACCESS_TOKEN": "secret-value"})

        self.assertEqual("secret-value", environment["ACCESS_TOKEN"])
        with self.assertRaises(ValueError):
            guest.inject_secret_environment(environment, {"invalid-name": "value"})
        with self.assertRaises(ValueError):
            guest.inject_secret_environment(environment, {"TOKEN": "x" * 4097})

    def test_guest_clock_bootstraps_from_trusted_host_epoch(self):
        host_epoch_millis = 1_784_385_257_000
        with (
            mock.patch.object(guest.time, "time", side_effect=[10.0, host_epoch_millis / 1000]),
            mock.patch.object(guest.time, "CLOCK_REALTIME", 0, create=True),
            mock.patch.object(guest.time, "clock_settime", create=True) as set_clock,
        ):
            guest.synchronize_guest_clock({"host_epoch_millis": host_epoch_millis})

        set_clock.assert_called_once_with(0, host_epoch_millis / 1000)

    def test_guest_clock_rejects_untrusted_host_epoch(self):
        with self.assertRaises(ValueError):
            guest.synchronize_guest_clock({"host_epoch_millis": 0})

    def test_runtime_readiness_requires_launcher_and_workspace_identity(self):
        with mock.patch.object(guest, "LAUNCHER_PATH", Path(sys.executable)):
            self.assertEqual(
                (True, ""),
                guest.runtime_readiness({"workspace_uid": 10123, "workspace_gid": 10123}),
            )
            ready, reason = guest.runtime_readiness({"workspace_uid": 0, "workspace_gid": 10123})
        self.assertFalse(ready)
        self.assertIn("workspace_uid", reason)

    def test_runtime_channel_is_discovered_without_udev_named_symlink(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            class_root = root / "sys" / "class" / "virtio-ports"
            device_root = root / "dev"
            port = class_root / "vport0p1"
            port.mkdir(parents=True)
            device_root.mkdir()
            (port / "name").write_text(guest.CHANNEL_NAME + "\n", encoding="utf-8")
            (port / "uevent").write_text("DEVNAME=vport0p1\n", encoding="utf-8")
            device = device_root / "vport0p1"
            device.touch()

            self.assertEqual(
                device,
                guest.wait_for_runtime_channel(
                    timeout_seconds=0.1,
                    class_root=class_root,
                    device_root=device_root,
                ),
            )

    def test_runtime_channel_rejects_unsafe_uevent_device_name(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            class_root = root / "sys" / "class" / "virtio-ports"
            device_root = root / "dev"
            port = class_root / "port-entry"
            port.mkdir(parents=True)
            device_root.mkdir()
            (port / "name").write_text(guest.CHANNEL_NAME, encoding="utf-8")
            (port / "uevent").write_text("DEVNAME=../outside\n", encoding="utf-8")

            with self.assertRaises(FileNotFoundError):
                guest.wait_for_runtime_channel(
                    timeout_seconds=0.01,
                    class_root=class_root,
                    device_root=device_root,
                )

    def test_mounted_pack_requires_matching_descriptor_capabilities_and_entrypoints(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)
            (target / "bin").mkdir()
            for executable_name in ("python3", "uv"):
                executable_path = target / "bin" / executable_name
                executable_path.touch()
                executable_path.chmod(0o755)
            descriptor = {
                "format_version": 1,
                "id": "python-uv",
                "version": "1.2.3",
                "capabilities": ["python.execute", "uv.sync"],
            }
            (target / guest.PACK_DESCRIPTOR_NAME).write_text(
                guest.canonical_json(descriptor), encoding="utf-8"
            )

            guest.validate_mounted_pack(target, descriptor)

            descriptor["capabilities"] = ["python.execute"]
            with self.assertRaises(ValueError):
                guest.validate_mounted_pack(target, descriptor)

    def test_resource_limits_reject_invalid_disk_and_artifact_bounds(self):
        with self.assertRaises(ValueError):
            guest.ExecutionLimits.from_payload({"limits": {"disk_bytes": 1}})
        with self.assertRaises(ValueError):
            guest.ExecutionLimits.from_payload({"limits": {"max_artifact_bytes": 1}})


if __name__ == "__main__":
    unittest.main()
