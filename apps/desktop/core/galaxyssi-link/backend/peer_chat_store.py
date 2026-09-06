"""Durable local history for direct phone-to-Desktop conversations."""
from __future__ import annotations

import json
import hashlib
import os
import shutil
import sqlite3
import threading
import time
import uuid
from contextlib import closing
from pathlib import Path
from typing import Callable

from pairing_state import DATA_DIR
from peer_attachment_storage import PeerAttachmentStorage, PeerAttachmentError
from secure_state import decrypt_text, encrypt_text, seal_identifier, unseal_identifier


MAX_MESSAGE_CHARS = 24_000
MAX_ATTACHMENTS = 12
MAX_ATTACHMENT_BYTES = 1024 * 1024 * 1024
_SAFE_NAME_CHARS = frozenset(
    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.() []"
)
_ROUTE_PURPOSE = "desktop.peer-chat.route.v1"
_REMOTE_PURPOSE = "desktop.peer-chat.remote.v1"
_SENDER_PURPOSE = "desktop.peer-chat.sender.v1"
_CONTENT_PURPOSE = "desktop.peer-chat.content.v1"
_ATTACHMENTS_PURPOSE = "desktop.peer-chat.attachments.v1"


class PeerChatStore:
    def __init__(self, database_path: Path | None = None) -> None:
        self.database_path = Path(database_path or (DATA_DIR / "peer_chat.db"))
        self.files_root = self.database_path.parent / "peer-chat-files"
        self._attachment_storage = PeerAttachmentStorage()
        self._lock = threading.RLock()
        self._listeners: dict[str, Callable[[dict], None]] = {}
        self._initialize()

    def _connect(self) -> sqlite3.Connection:
        self.database_path.parent.mkdir(parents=True, exist_ok=True)
        connection = sqlite3.connect(self.database_path, timeout=10)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute("PRAGMA synchronous=NORMAL")
        return connection

    def _initialize(self) -> None:
        with self._lock, closing(self._connect()) as connection:
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS peer_messages (
                    message_id TEXT PRIMARY KEY,
                    client_route_id TEXT NOT NULL,
                    remote_message_id TEXT NOT NULL DEFAULT '',
                    direction TEXT NOT NULL,
                    sender_name TEXT NOT NULL DEFAULT '',
                    content TEXT NOT NULL DEFAULT '',
                    attachments_json TEXT NOT NULL DEFAULT '[]',
                    delivery_status TEXT NOT NULL DEFAULT 'stored',
                    created_at_ms INTEGER NOT NULL
                );
                CREATE UNIQUE INDEX IF NOT EXISTS peer_messages_remote_unique
                  ON peer_messages(client_route_id, remote_message_id)
                  WHERE remote_message_id <> '';
                CREATE INDEX IF NOT EXISTS peer_messages_route_time
                  ON peer_messages(client_route_id, created_at_ms, message_id);
                CREATE TABLE IF NOT EXISTS peer_message_tombstones (message_id TEXT PRIMARY KEY);
                """
            )
            connection.execute(
                """UPDATE peer_messages SET delivery_status = 'failed'
                   WHERE delivery_status IN ('sending', 'preparing')"""
            )
            self._migrate_plaintext_rows(connection)
            connection.commit()

    def subscribe(self, listener: Callable[[dict], None]) -> str:
        subscription_id = uuid.uuid4().hex
        with self._lock:
            self._listeners[subscription_id] = listener
        return subscription_id

    def unsubscribe(self, subscription_id: str) -> None:
        with self._lock:
            self._listeners.pop(subscription_id, None)

    def append(
        self,
        *,
        client_route_id: str,
        direction: str,
        content: str = "",
        sender_name: str = "",
        attachments: list[dict] | None = None,
        message_id: str = "",
        remote_message_id: str = "",
        delivery_status: str = "stored",
        created_at_ms: int = 0,
        idempotent: bool = False,
    ) -> dict:
        route_id = str(client_route_id or "").strip()
        if not route_id:
            raise ValueError("client_route_id is required")
        normalized_direction = str(direction or "").strip().lower()
        if normalized_direction not in {"inbound", "outbound"}:
            raise ValueError("direction must be inbound or outbound")
        normalized_content = str(content or "")[:MAX_MESSAGE_CHARS]
        normalized_attachments = self._normalize_attachments(attachments or [])
        if not normalized_content.strip() and not normalized_attachments:
            raise ValueError("peer message requires text or an attachment")
        local_id = str(message_id or "").strip() or f"peer-{uuid.uuid4()}"
        created = int(created_at_ms or int(time.time() * 1000))
        stored_route_id = self._seal_route(route_id)
        stored_remote_message_id = self._seal_remote(remote_message_id)
        encoded_attachments = self._encrypt_attachments(normalized_attachments)
        with self._lock, closing(self._connect()) as connection:
            if idempotent:
                connection.execute("BEGIN IMMEDIATE")
                if connection.execute("SELECT 1 FROM peer_message_tombstones WHERE message_id=?", (local_id,)).fetchone():
                    raise ValueError("peer_message_deleted")
                existing = connection.execute("SELECT * FROM peer_messages WHERE message_id=?", (local_id,)).fetchone()
                if existing is not None:
                    if (existing["client_route_id"] != stored_route_id or existing["direction"] != normalized_direction
                            or self._decrypt_content(existing["content"]) != normalized_content
                            or self._decrypt_attachments(existing["attachments_json"]) != normalized_attachments):
                        raise ValueError("peer_message_conflict")
                    return self._public(existing)
            if remote_message_id:
                existing = connection.execute(
                    """
                    SELECT * FROM peer_messages
                    WHERE client_route_id = ? AND remote_message_id = ?
                    """,
                    (stored_route_id, stored_remote_message_id),
                ).fetchone()
                if existing is not None:
                    return self._public(existing)
            connection.execute(
                """
                INSERT INTO peer_messages (
                    message_id, client_route_id, remote_message_id, direction,
                    sender_name, content, attachments_json, delivery_status,
                    created_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    local_id,
                    stored_route_id,
                    stored_remote_message_id,
                    normalized_direction,
                    self._encrypt_sender(str(sender_name or "")[:160]),
                    self._encrypt_content(normalized_content),
                    encoded_attachments,
                    str(delivery_status or "stored")[:32],
                    created,
                ),
            )
            row = connection.execute(
                "SELECT * FROM peer_messages WHERE message_id = ?",
                (local_id,),
            ).fetchone()
            connection.commit()
        result = self._public(row)
        self._notify(result)
        return result

    def update_delivery_status(self, message_id: str, status: str, *, only_if: tuple[str, ...] | None = None) -> dict | None:
        with self._lock, closing(self._connect()) as connection:
            predicate = " AND delivery_status IN (" + ",".join("?" for _ in only_if) + ")" if only_if is not None else ""
            updated = connection.execute(
                "UPDATE peer_messages SET delivery_status = ? WHERE message_id = ?" + predicate,
                (str(status or "")[:32], str(message_id or ""), *(only_if or ())),
            )
            row = connection.execute(
                "SELECT * FROM peer_messages WHERE message_id = ?",
                (str(message_id or ""),),
            ).fetchone()
            connection.commit()
        if row is None:
            return None
        result = self._public(row)
        if only_if is not None and updated.rowcount == 0:
            return result
        self._notify(result)
        return result

    def message_was_deleted(self, message_id: str) -> bool:
        with self._lock, closing(self._connect()) as connection:
            return connection.execute("SELECT 1 FROM peer_message_tombstones WHERE message_id=?",
                                      (str(message_id),)).fetchone() is not None

    def get_message(self, message_id: str) -> dict | None:
        with self._lock, closing(self._connect()) as connection:
            row = connection.execute(
                "SELECT * FROM peer_messages WHERE message_id = ?",
                (str(message_id or ""),),
            ).fetchone()
        return self._public(row) if row is not None else None

    def list_messages(self, client_route_id: str = "", limit: int = 500) -> list[dict]:
        bounded_limit = max(1, min(int(limit or 500), 2_000))
        route_id = str(client_route_id or "").strip()
        stored_route_id = self._seal_route(route_id) if route_id else ""
        with self._lock, closing(self._connect()) as connection:
            if route_id:
                rows = connection.execute(
                    """
                    SELECT * FROM peer_messages
                    WHERE client_route_id = ?
                    ORDER BY created_at_ms DESC, message_id DESC LIMIT ?
                    """,
                    (stored_route_id, bounded_limit),
                ).fetchall()
            else:
                rows = connection.execute(
                    """
                    SELECT * FROM peer_messages
                    ORDER BY created_at_ms DESC, message_id DESC LIMIT ?
                    """,
                    (bounded_limit,),
                ).fetchall()
        return [self._public(row) for row in reversed(rows)]

    def delete_route(self, client_route_id: str) -> int:
        """Delete a revoked device conversation and its imported attachments."""
        route_id = str(client_route_id or "").strip()
        if not route_id:
            return 0
        stored_route_id = self._seal_route(route_id)
        with self._lock, closing(self._connect()) as connection:
            connection.execute("INSERT OR IGNORE INTO peer_message_tombstones(message_id) "
                               "SELECT message_id FROM peer_messages WHERE client_route_id=?", (stored_route_id,))
            cursor = connection.execute(
                "DELETE FROM peer_messages WHERE client_route_id = ?",
                (stored_route_id,),
            )
            connection.commit()
        route_directory = self._route_directory(route_id)
        if route_directory.is_dir():
            shutil.rmtree(route_directory, ignore_errors=True)
        return max(0, int(cursor.rowcount or 0))

    def import_attachment(
        self,
        *,
        client_route_id: str,
        message_id: str,
        source: Path,
        name: str,
        mime_type: str,
        sha256: str,
    ) -> dict:
        source_path = Path(source).resolve()
        if not source_path.is_file() or source_path.is_symlink():
            raise ValueError("peer attachment is unavailable")
        size = source_path.stat().st_size
        if size <= 0 or size > MAX_ATTACHMENT_BYTES:
            raise ValueError("peer attachment size is outside the supported range")
        route_directory = self._route_directory(client_route_id)
        message_directory = route_directory / self._safe_component(message_id)
        message_directory.mkdir(parents=True, exist_ok=True)
        safe_name = self._safe_name(name or source_path.name)
        target = message_directory / f"{uuid.uuid4().hex}.sasi"
        stored_size, stored_sha256 = self._attachment_storage.store_file(
            source_path,
            target,
            expected_sha256=sha256,
        )
        return {
            "name": safe_name,
            "mime_type": str(mime_type or "application/octet-stream")[:160],
            "size_bytes": stored_size,
            "sha256": stored_sha256,
            "local_path": str(target.resolve()),
        }

    def attachment_record(self, message_id: str, index: int) -> dict | None:
        with self._lock, closing(self._connect()) as connection:
            row = connection.execute(
                "SELECT attachments_json FROM peer_messages WHERE message_id = ?",
                (str(message_id or ""),),
            ).fetchone()
        if row is None:
            return None
        attachments = self._decrypt_attachments(row["attachments_json"])
        if index not in range(len(attachments)):
            return None
        attachment = dict(attachments[index])
        value = self._validated_attachment_path(attachment)
        if value is None:
            return None
        attachment["local_path"] = str(value)
        return attachment

    @staticmethod
    def _comparison_path(value: Path) -> str:
        normalized = os.path.normcase(os.path.normpath(str(value)))
        if os.name != "nt":
            return normalized
        extended_prefix = "\\\\?\\"
        extended_unc_prefix = f"{extended_prefix}unc\\"
        if normalized.startswith(extended_unc_prefix):
            return f"\\\\{normalized[len(extended_unc_prefix):]}"
        if normalized.startswith(extended_prefix):
            return normalized[len(extended_prefix):]
        return normalized

    def _validated_attachment_path(self, attachment: dict) -> Path | None:
        raw_path = str(attachment.get("local_path") or "").strip()
        if not raw_path:
            return None
        try:
            value = Path(raw_path).resolve()
            root = self.files_root.resolve()
            candidate_key = self._comparison_path(value)
            root_key = self._comparison_path(root)
            if os.path.commonpath((candidate_key, root_key)) != root_key:
                return None
        except (OSError, ValueError):
            return None
        if not value.is_file() or value.is_symlink():
            return None
        return value

    def stream_attachment(self, message_id: str, index: int):
        attachment = self.attachment_record(message_id, index)
        if attachment is None:
            raise PeerAttachmentError("Peer attachment is unavailable")
        return self._attachment_storage.read_stream(
            Path(attachment["local_path"]),
            expected_size=int(attachment.get("size_bytes") or 0),
            expected_sha256=str(attachment.get("sha256") or ""),
        )

    def _public(self, row: sqlite3.Row | None) -> dict:
        if row is None:
            raise ValueError("peer message was not stored")
        attachments = self._decrypt_attachments(row["attachments_json"])
        return {
            "message_id": row["message_id"],
            "client_route_id": self._unseal_route(row["client_route_id"]),
            "direction": row["direction"],
            "sender_name": self._decrypt_sender(row["sender_name"]),
            "content": self._decrypt_content(row["content"]),
            "attachments": [
                {key: value for key, value in item.items() if key != "local_path"}
                | {"available": self._attachment_available(item)}
                for item in attachments
            ],
            "delivery_status": row["delivery_status"],
            "created_at_ms": int(row["created_at_ms"]),
        }

    def _notify(self, message: dict) -> None:
        with self._lock:
            listeners = list(self._listeners.values())
        for listener in listeners:
            try:
                listener(dict(message))
            except Exception:
                continue

    @staticmethod
    def _normalize_attachments(attachments: list[dict]) -> list[dict]:
        normalized = []
        for item in attachments[:MAX_ATTACHMENTS]:
            if not isinstance(item, dict):
                continue
            normalized.append({
                "name": str(item.get("name") or "attachment")[:180],
                "mime_type": str(item.get("mime_type") or "application/octet-stream")[:160],
                "size_bytes": max(0, int(item.get("size_bytes") or item.get("size") or 0)),
                "sha256": str(item.get("sha256") or "")[:64],
                "duration_ms": min(
                    60 * 60 * 1000,
                    max(0, int(item.get("duration_ms") or 0)),
                ),
                "artifact_uri": str(item.get("artifact_uri") or "")[:1_024],
                "local_path": str(item.get("local_path") or "")[:4_096],
            })
        return normalized

    @staticmethod
    def _decode_attachment_json(value: str) -> list[dict]:
        try:
            decoded = json.loads(value or "[]")
        except (TypeError, ValueError):
            return []
        return [item for item in decoded if isinstance(item, dict)][:MAX_ATTACHMENTS]

    def _seal_route(self, value: str) -> str:
        return seal_identifier(self.database_path, str(value), purpose=_ROUTE_PURPOSE)

    def _unseal_route(self, value: str) -> str:
        return unseal_identifier(self.database_path, str(value), purpose=_ROUTE_PURPOSE)

    def _seal_remote(self, value: str) -> str:
        text = str(value or "")[:160]
        return seal_identifier(self.database_path, text, purpose=_REMOTE_PURPOSE) if text else ""

    def _encrypt_sender(self, value: str) -> str:
        return encrypt_text(self.database_path, str(value), purpose=_SENDER_PURPOSE)

    def _decrypt_sender(self, value: str) -> str:
        return decrypt_text(self.database_path, str(value), purpose=_SENDER_PURPOSE)

    def _encrypt_content(self, value: str) -> str:
        return encrypt_text(self.database_path, str(value), purpose=_CONTENT_PURPOSE)

    def _decrypt_content(self, value: str) -> str:
        return decrypt_text(self.database_path, str(value), purpose=_CONTENT_PURPOSE)

    def _encrypt_attachments(self, value: list[dict]) -> str:
        serialized = json.dumps(value, ensure_ascii=True, separators=(",", ":"))
        return encrypt_text(self.database_path, serialized, purpose=_ATTACHMENTS_PURPOSE)

    def _decrypt_attachments(self, value: str) -> list[dict]:
        serialized = decrypt_text(self.database_path, str(value), purpose=_ATTACHMENTS_PURPOSE)
        return self._decode_attachment_json(serialized)

    def _attachment_available(self, attachment: dict) -> bool:
        return self._validated_attachment_path(attachment) is not None

    def _route_directory(self, client_route_id: str) -> Path:
        digest = hashlib.sha256(str(client_route_id or "").encode("utf-8")).hexdigest()
        return self.files_root / digest

    def _migrate_plaintext_rows(self, connection: sqlite3.Connection) -> None:
        rows = connection.execute("SELECT * FROM peer_messages").fetchall()
        for row in rows:
            requires_migration = (
                not str(row["client_route_id"] or "").startswith("sid:v1:")
                or (
                    bool(row["remote_message_id"])
                    and not str(row["remote_message_id"]).startswith("sid:v1:")
                )
                or not str(row["sender_name"] or "").startswith("enc:v1:")
                or not str(row["content"] or "").startswith("enc:v1:")
                or not str(row["attachments_json"] or "").startswith("enc:v1:")
            )
            route_id = self._legacy_or_unsealed(row["client_route_id"], _ROUTE_PURPOSE)
            remote_message_id = self._legacy_or_unsealed(row["remote_message_id"], _REMOTE_PURPOSE)
            sender_name = self._legacy_or_decrypted(row["sender_name"], _SENDER_PURPOSE)
            content = self._legacy_or_decrypted(row["content"], _CONTENT_PURPOSE)
            attachment_text = self._legacy_or_decrypted(
                row["attachments_json"],
                _ATTACHMENTS_PURPOSE,
            )
            attachments = self._decode_attachment_json(attachment_text)
            if not requires_migration:
                continue
            connection.execute(
                """
                UPDATE peer_messages SET
                  client_route_id = ?, remote_message_id = ?, sender_name = ?,
                  content = ?, attachments_json = ? WHERE message_id = ?
                """,
                (
                    self._seal_route(route_id),
                    self._seal_remote(remote_message_id),
                    self._encrypt_sender(sender_name),
                    self._encrypt_content(content),
                    self._encrypt_attachments(attachments),
                    row["message_id"],
                ),
            )
        connection.commit()

    def _legacy_or_unsealed(self, value: str, purpose: str) -> str:
        text = str(value or "")
        if not text or not text.startswith("sid:v1:"):
            return text
        return unseal_identifier(self.database_path, text, purpose=purpose)

    def _legacy_or_decrypted(self, value: str, purpose: str) -> str:
        text = str(value or "")
        if not text.startswith("enc:v1:"):
            return text
        return decrypt_text(self.database_path, text, purpose=purpose)

    @staticmethod
    def _safe_component(value: str) -> str:
        cleaned = "".join(character for character in str(value or "") if character.isalnum() or character in "-_")
        return cleaned[:96] or uuid.uuid4().hex

    @staticmethod
    def _safe_name(value: str) -> str:
        name = Path(str(value or "attachment")).name
        cleaned = "".join(character if character in _SAFE_NAME_CHARS or ord(character) > 127 else "_" for character in name)
        return cleaned.strip(" .")[:180] or "attachment"


_store: PeerChatStore | None = None
_store_lock = threading.Lock()


def peer_chat_store() -> PeerChatStore:
    global _store
    with _store_lock:
        if _store is None:
            _store = PeerChatStore()
        return _store
