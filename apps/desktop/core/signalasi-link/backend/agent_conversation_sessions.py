"""Durable bindings between SignalASI conversations and native Agent sessions."""
from __future__ import annotations

import json
import os
import threading
import time
import uuid
from dataclasses import dataclass
from pathlib import Path


MAX_SYNC_IDENTIFIERS = 2_048


def _default_path() -> Path:
    configured = os.environ.get("SIGNALASI_STATE_DIR", "").strip()
    root = Path(configured) if configured else (
        Path(os.environ.get("APPDATA") or Path.home()) / "SignalASI"
    )
    return root / "agent-conversation-sessions.json"


@dataclass(frozen=True)
class AgentConversationSession:
    session_id: str = ""
    updated_at_millis: int = 0
    through_created_at_millis: int = 0
    through_task_id: str = ""
    synced_turn_ids: tuple[str, ...] = ()
    synced_entry_ids: tuple[str, ...] = ()
    summary_digest: str = ""

    @property
    def cursor(self) -> tuple[int, str]:
        return self.through_created_at_millis, self.through_task_id


class AgentConversationSessions:
    """Stores native session ids and serializes turns within one conversation."""

    def __init__(self, path: Path | None = None) -> None:
        self.path = Path(path or _default_path())
        self._lock = threading.RLock()
        self._conversation_locks: dict[tuple[str, str], threading.RLock] = {}
        self._sessions = self._load()

    def get(self, agent_id: str, conversation_id: str) -> AgentConversationSession:
        key = self._key(agent_id, conversation_id)
        if not all(key):
            return AgentConversationSession()
        with self._lock:
            item = self._sessions.get(self._storage_key(*key)) or {}
            return AgentConversationSession(
                session_id=str(item.get("session_id") or ""),
                updated_at_millis=max(0, int(item.get("updated_at_millis") or 0)),
                through_created_at_millis=max(
                    0,
                    int(item.get("through_created_at_millis") or 0),
                ),
                through_task_id=str(item.get("through_task_id") or ""),
                synced_turn_ids=self._identifiers(item.get("synced_turn_ids")),
                synced_entry_ids=self._identifiers(item.get("synced_entry_ids")),
                summary_digest=str(item.get("summary_digest") or "")[:128],
            )

    def ensure(self, agent_id: str, conversation_id: str) -> AgentConversationSession:
        existing = self.get(agent_id, conversation_id)
        if existing.session_id:
            return existing
        generated = str(uuid.uuid4())
        self.put(agent_id, conversation_id, generated)
        return self.get(agent_id, conversation_id)

    def put(self, agent_id: str, conversation_id: str, session_id: str) -> None:
        agent, conversation = self._key(agent_id, conversation_id)
        value = str(session_id or "").strip()
        if not agent or not conversation or not value:
            return
        with self._lock:
            key = self._storage_key(agent, conversation)
            now = int(time.time() * 1_000)
            item = self._sessions.get(key) or {}
            if str(item.get("session_id") or "") == value:
                item["updated_at_millis"] = now
            else:
                item = {
                    "agent_id": agent,
                    "conversation_id": conversation,
                    "session_id": value[:240],
                    "updated_at_millis": now,
                    "through_created_at_millis": max(
                        0,
                        int(item.get("through_created_at_millis") or 0),
                    ),
                    "through_task_id": str(item.get("through_task_id") or ""),
                    "synced_turn_ids": list(self._identifiers(item.get("synced_turn_ids"))),
                    "synced_entry_ids": list(self._identifiers(item.get("synced_entry_ids"))),
                    "summary_digest": str(item.get("summary_digest") or "")[:128],
                }
            self._sessions[key] = item
            self._save_locked()

    def mark_synced(
        self,
        agent_id: str,
        conversation_id: str,
        *,
        through_created_at_millis: int,
        through_task_id: str,
        synced_turn_ids: tuple[str, ...] | list[str] = (),
        synced_entry_ids: tuple[str, ...] | list[str] = (),
        summary_digest: str | None = None,
    ) -> None:
        agent, conversation = self._key(agent_id, conversation_id)
        if not agent or not conversation:
            return
        with self._lock:
            key = self._storage_key(agent, conversation)
            item = self._sessions.get(key)
            if not isinstance(item, dict) or not str(item.get("session_id") or ""):
                return
            previous = (
                max(0, int(item.get("through_created_at_millis") or 0)),
                str(item.get("through_task_id") or ""),
            )
            requested = (
                max(0, int(through_created_at_millis or 0)),
                str(through_task_id or ""),
            )
            cursor = max(previous, requested)
            item["through_created_at_millis"] = cursor[0]
            item["through_task_id"] = cursor[1]
            item["synced_turn_ids"] = self._merge_identifiers(
                item.get("synced_turn_ids"),
                synced_turn_ids,
            )
            item["synced_entry_ids"] = self._merge_identifiers(
                item.get("synced_entry_ids"),
                synced_entry_ids,
            )
            if summary_digest is not None:
                item["summary_digest"] = str(summary_digest or "")[:128]
            item["updated_at_millis"] = int(time.time() * 1_000)
            self._save_locked()

    def delete(self, agent_id: str, conversation_id: str) -> bool:
        key = self._key(agent_id, conversation_id)
        if not all(key):
            return False
        with self._lock:
            storage_key = self._storage_key(*key)
            removed = self._sessions.pop(storage_key, None)
            self._conversation_locks.pop(key, None)
            if removed is not None:
                self._save_locked()
            return removed is not None

    def delete_conversation(self, conversation_id: str) -> int:
        conversation = str(conversation_id or "").strip()
        if not conversation:
            return 0
        with self._lock:
            keys = [
                key
                for key, item in self._sessions.items()
                if str(item.get("conversation_id") or "") == conversation
            ]
            for key in keys:
                self._sessions.pop(key, None)
            lock_keys = [key for key in self._conversation_locks if key[1] == conversation]
            for key in lock_keys:
                self._conversation_locks.pop(key, None)
            if keys:
                self._save_locked()
            return len(keys)

    def conversation_lock(self, agent_id: str, conversation_id: str) -> threading.RLock:
        key = self._key(agent_id, conversation_id)
        if not all(key):
            key = (key[0] or "__unknown_agent__", key[1] or "__unscoped__")
        with self._lock:
            return self._conversation_locks.setdefault(key, threading.RLock())

    @staticmethod
    def _key(agent_id: str, conversation_id: str) -> tuple[str, str]:
        return str(agent_id or "").strip(), str(conversation_id or "").strip()

    @staticmethod
    def _storage_key(agent_id: str, conversation_id: str) -> str:
        return f"{agent_id}\u001f{conversation_id}"

    def _load(self) -> dict[str, dict]:
        try:
            payload = json.loads(self.path.read_text(encoding="utf-8"))
            if not isinstance(payload, dict):
                return {}
            result: dict[str, dict] = {}
            for key, item in payload.items():
                if not isinstance(item, dict):
                    continue
                agent = str(item.get("agent_id") or "").strip()
                conversation = str(item.get("conversation_id") or "").strip()
                session_id = str(item.get("session_id") or "").strip()
                if not agent or not conversation or not session_id:
                    continue
                result[str(key)] = {
                    "agent_id": agent[:240],
                    "conversation_id": conversation[:240],
                    "session_id": session_id[:240],
                    "updated_at_millis": max(0, int(item.get("updated_at_millis") or 0)),
                    "through_created_at_millis": max(
                        0,
                        int(item.get("through_created_at_millis") or 0),
                    ),
                    "through_task_id": str(item.get("through_task_id") or "")[:240],
                    "synced_turn_ids": list(self._identifiers(item.get("synced_turn_ids"))),
                    "synced_entry_ids": list(self._identifiers(item.get("synced_entry_ids"))),
                    "summary_digest": str(item.get("summary_digest") or "")[:128],
                }
            return result
        except (FileNotFoundError, json.JSONDecodeError, OSError, TypeError, ValueError):
            return {}

    def _save_locked(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(f"{self.path.suffix}.tmp")
        temporary.write_text(
            json.dumps(self._sessions, ensure_ascii=True, separators=(",", ":")),
            encoding="utf-8",
        )
        try:
            os.chmod(temporary, 0o600)
        except OSError:
            pass
        temporary.replace(self.path)

    @staticmethod
    def _identifiers(value: object) -> tuple[str, ...]:
        if not isinstance(value, (list, tuple)):
            return ()
        result: list[str] = []
        seen: set[str] = set()
        for item in value:
            clean = str(item or "").strip()[:240]
            if not clean or clean in seen:
                continue
            seen.add(clean)
            result.append(clean)
        return tuple(result[-MAX_SYNC_IDENTIFIERS:])

    @classmethod
    def _merge_identifiers(
        cls,
        previous: object,
        values: tuple[str, ...] | list[str],
    ) -> list[str]:
        return list(cls._identifiers((*cls._identifiers(previous), *values)))


_sessions: AgentConversationSessions | None = None
_sessions_lock = threading.Lock()


def agent_conversation_sessions() -> AgentConversationSessions:
    global _sessions
    with _sessions_lock:
        if _sessions is None:
            _sessions = AgentConversationSessions()
        return _sessions
