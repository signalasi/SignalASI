from __future__ import annotations

import hashlib
import os
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path

from peer_chat_store import PeerChatStore
from peer_attachment_storage import PeerAttachmentError


class PeerChatStoreTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.store = PeerChatStore(Path(self.temporary.name) / "peer-chat.db")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_text_and_file_messages_are_isolated_by_phone_route(self) -> None:
        source = Path(self.temporary.name) / "report.txt"
        source.write_text("verified peer content", encoding="utf-8")
        attachment = self.store.import_attachment(
            client_route_id="phone-a",
            message_id="message-a",
            source=source,
            name="report.txt",
            mime_type="text/plain",
            sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
        )
        stored = self.store.append(
            client_route_id="phone-a",
            direction="inbound",
            content="For this phone only",
            attachments=[attachment],
            remote_message_id="remote-a",
        )
        self.store.append(
            client_route_id="phone-b",
            direction="outbound",
            content="Separate conversation",
        )

        phone_a = self.store.list_messages("phone-a")
        phone_b = self.store.list_messages("phone-b")
        self.assertEqual([stored["message_id"]], [item["message_id"] for item in phone_a])
        self.assertEqual("Separate conversation", phone_b[0]["content"])
        self.assertTrue(phone_a[0]["attachments"][0]["available"])
        self.assertNotIn("local_path", phone_a[0]["attachments"][0])
        self.assertEqual(
            source.read_bytes(),
            b"".join(self.store.stream_attachment(stored["message_id"], 0)),
        )
        record = self.store.attachment_record(stored["message_id"], 0)
        local_file = Path(record["local_path"])
        self.assertEqual(source.read_bytes(), local_file.read_bytes())
        reopened = PeerChatStore(self.store.database_path)
        self.assertEqual(source.read_bytes(), local_file.read_bytes())
        self.assertEqual(source.read_bytes(), b"".join(reopened.stream_attachment(stored["message_id"], 0)))
        with closing(sqlite3.connect(self.store.database_path)) as connection:
            raw = " ".join(str(value) for value in connection.execute(
                "SELECT client_route_id, sender_name, content, attachments_json FROM peer_messages"
            ).fetchone())
        self.assertNotIn("phone-a", raw)
        self.assertNotIn("For this phone only", raw)
        self.assertNotIn("report.txt", raw)

    def test_remote_message_replay_is_idempotent(self) -> None:
        first = self.store.append(
            client_route_id="phone-a",
            direction="inbound",
            content="hello",
            remote_message_id="same-wire-message",
        )
        second = self.store.append(
            client_route_id="phone-a",
            direction="inbound",
            content="hello",
            remote_message_id="same-wire-message",
        )

        self.assertEqual(first["message_id"], second["message_id"])
        self.assertEqual(1, len(self.store.list_messages("phone-a")))

    def test_tampered_attachment_is_rejected_by_sha256(self) -> None:
        source = Path(self.temporary.name) / "voice.opus"
        source.write_bytes(b"OggS" + b"voice" * 2_000)
        attachment = self.store.import_attachment(
            client_route_id="phone-a",
            message_id="voice-message",
            source=source,
            name="voice.opus",
            mime_type="audio/ogg",
            sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
        )
        stored = self.store.append(
            client_route_id="phone-a",
            direction="inbound",
            attachments=[{**attachment, "duration_ms": 1_546}],
        )
        self.assertEqual(1_546, stored["attachments"][0]["duration_ms"])
        encrypted = Path(self.store.attachment_record(stored["message_id"], 0)["local_path"])
        payload = bytearray(encrypted.read_bytes())
        payload[-1] ^= 0x01
        encrypted.write_bytes(payload)

        with self.assertRaises(PeerAttachmentError):
            b"".join(self.store.stream_attachment(stored["message_id"], 0))

    @unittest.skipUnless(os.name == "nt", "Windows extended paths only")
    def test_windows_extended_attachment_path_remains_available(self) -> None:
        source = Path(self.temporary.name) / "photo.jpg"
        source.write_bytes(b"jpeg-image-content")
        attachment = self.store.import_attachment(
            client_route_id="phone-a",
            message_id="remote-photo",
            source=source,
            name="photo.jpg",
            mime_type="image/jpeg",
            sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
        )
        local_path = str(attachment["local_path"])
        if not local_path.startswith("\\\\?\\"):
            local_path = f"\\\\?\\{local_path}"
        stored = self.store.append(
            client_route_id="phone-a",
            direction="inbound",
            attachments=[{**attachment, "local_path": local_path}],
        )

        self.assertTrue(stored["attachments"][0]["available"])
        self.assertIsNotNone(self.store.attachment_record(stored["message_id"], 0))
        self.assertEqual(
            source.read_bytes(),
            b"".join(self.store.stream_attachment(stored["message_id"], 0)),
        )

    def test_empty_message_is_rejected(self) -> None:
        with self.assertRaises(ValueError):
            self.store.append(client_route_id="phone-a", direction="outbound")

    def test_interrupted_sending_message_is_failed_on_reopen(self) -> None:
        stored = self.store.append(
            client_route_id="phone-a",
            direction="outbound",
            content="hello",
            delivery_status="sending",
        )

        reopened = PeerChatStore(self.store.database_path)

        self.assertEqual(
            "failed",
            reopened.get_message(stored["message_id"])["delivery_status"],
        )

    def test_delete_route_removes_only_revoked_device_history_and_files(self) -> None:
        source = Path(self.temporary.name) / "attachment.txt"
        source.write_text("private peer attachment", encoding="utf-8")
        attachment = self.store.import_attachment(
            client_route_id="phone-a",
            message_id="message-a",
            source=source,
            name="attachment.txt",
            mime_type="text/plain",
            sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
        )
        stored = self.store.append(
            client_route_id="phone-a",
            direction="inbound",
            content="remove me",
            attachments=[attachment],
            message_id="message-a",
        )
        attachment_path = Path(self.store.attachment_record(stored["message_id"], 0)["local_path"])
        self.store.append(
            client_route_id="phone-b",
            direction="inbound",
            content="keep me",
        )

        self.assertEqual(1, self.store.delete_route("phone-a"))

        self.assertEqual([], self.store.list_messages("phone-a"))
        self.assertEqual("keep me", self.store.list_messages("phone-b")[0]["content"])
        self.assertFalse(attachment_path.exists())


if __name__ == "__main__":
    unittest.main()
