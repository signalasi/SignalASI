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

    def test_resource_limits_reject_invalid_disk_and_artifact_bounds(self):
        with self.assertRaises(ValueError):
            guest.ExecutionLimits.from_payload({"limits": {"disk_bytes": 1}})
        with self.assertRaises(ValueError):
            guest.ExecutionLimits.from_payload({"limits": {"max_artifact_bytes": 1}})


if __name__ == "__main__":
    unittest.main()
