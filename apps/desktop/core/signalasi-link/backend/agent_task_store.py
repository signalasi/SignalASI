"""SQLite persistence for complete Desktop Agent task history."""

from __future__ import annotations

from contextlib import contextmanager
import json
from pathlib import Path
import sqlite3
from typing import Iterable, Iterator


class AgentTaskStore:
    def __init__(self, path: Path) -> None:
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._connection() as connection:
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS agent_tasks (
                    task_id TEXT PRIMARY KEY NOT NULL,
                    conversation_id TEXT NOT NULL,
                    source_message_id TEXT NOT NULL,
                    status TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    payload TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS agent_tasks_conversation_order
                    ON agent_tasks(conversation_id, created_at, task_id);
                CREATE INDEX IF NOT EXISTS agent_tasks_updated_order
                    ON agent_tasks(updated_at DESC, task_id DESC);
                CREATE INDEX IF NOT EXISTS agent_tasks_status
                    ON agent_tasks(status, updated_at DESC);
                """
            )

    def upsert(self, record: dict) -> None:
        task_id = str(record.get("task_id") or "").strip()
        if not task_id:
            raise ValueError("Agent task ID is required")
        payload = json.dumps(record, ensure_ascii=False, separators=(",", ":"))
        with self._connection() as connection:
            connection.execute(
                """
                INSERT INTO agent_tasks (
                    task_id, conversation_id, source_message_id, status,
                    created_at, updated_at, payload
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(task_id) DO UPDATE SET
                    conversation_id = excluded.conversation_id,
                    source_message_id = excluded.source_message_id,
                    status = excluded.status,
                    created_at = excluded.created_at,
                    updated_at = excluded.updated_at,
                    payload = excluded.payload
                """,
                (
                    task_id,
                    str(record.get("conversation_id") or ""),
                    str(record.get("source_message_id") or ""),
                    str(record.get("status") or ""),
                    max(0, int(record.get("created_at") or 0)),
                    max(0, int(record.get("updated_at") or 0)),
                    payload,
                ),
            )

    def get(self, task_id: str) -> dict | None:
        clean_id = str(task_id or "").strip()
        if not clean_id:
            return None
        with self._connection() as connection:
            row = connection.execute(
                "SELECT payload FROM agent_tasks WHERE task_id = ?",
                (clean_id,),
            ).fetchone()
        return self._decode(row[0]) if row else None

    def list_recent(self, limit: int) -> list[dict]:
        safe_limit = max(1, int(limit or 1))
        with self._connection() as connection:
            rows = connection.execute(
                """
                SELECT payload
                FROM agent_tasks
                ORDER BY updated_at DESC, task_id DESC
                LIMIT ?
                """,
                (safe_limit,),
            ).fetchall()
        return [record for row in rows if (record := self._decode(row[0])) is not None]

    def recoverable(self, terminal_states: Iterable[str]) -> list[dict]:
        terminal = tuple(sorted({str(value) for value in terminal_states if str(value)}))
        placeholders = ",".join("?" for _ in terminal)
        where = f"status NOT IN ({placeholders})" if terminal else "1 = 1"
        with self._connection() as connection:
            rows = connection.execute(
                f"""
                SELECT payload
                FROM agent_tasks
                WHERE {where}
                ORDER BY updated_at ASC, task_id ASC
                """,
                terminal,
            ).fetchall()
        return [record for row in rows if (record := self._decode(row[0])) is not None]

    def conversation(
        self,
        conversation_id: str,
        *,
        source_prefix: str | None = None,
        after_cursor: tuple[int, str] = (0, ""),
        limit: int | None = None,
    ) -> list[dict]:
        clean_id = str(conversation_id or "").strip()
        if not clean_id:
            return []
        cursor_time = max(0, int(after_cursor[0] or 0))
        cursor_id = str(after_cursor[1] or "")
        clauses = [
            "conversation_id = ?",
            "(created_at > ? OR (created_at = ? AND task_id > ?))",
        ]
        arguments: list[object] = [clean_id, cursor_time, cursor_time, cursor_id]
        if source_prefix is not None:
            clauses.append("source_message_id LIKE ? ESCAPE '\\'")
            arguments.append(self._like_prefix(source_prefix))
        where = " AND ".join(clauses)
        with self._connection() as connection:
            if limit is None:
                rows = connection.execute(
                    f"""
                    SELECT payload
                    FROM agent_tasks
                    WHERE {where}
                    ORDER BY created_at ASC, task_id ASC
                    """,
                    arguments,
                ).fetchall()
            else:
                safe_limit = max(1, int(limit or 1))
                rows = connection.execute(
                    f"""
                    SELECT payload
                    FROM (
                        SELECT payload, created_at, task_id
                        FROM agent_tasks
                        WHERE {where}
                        ORDER BY created_at DESC, task_id DESC
                        LIMIT ?
                    )
                    ORDER BY created_at ASC, task_id ASC
                    """,
                    [*arguments, safe_limit],
                ).fetchall()
        return [record for row in rows if (record := self._decode(row[0])) is not None]

    def delete_conversation(
        self,
        conversation_id: str,
        task_ids: set[str] | None = None,
    ) -> list[str]:
        clean_id = str(conversation_id or "").strip()
        explicit = sorted({str(value).strip() for value in (task_ids or set()) if str(value).strip()})
        clauses: list[str] = []
        arguments: list[object] = []
        if clean_id:
            clauses.append("conversation_id = ?")
            arguments.append(clean_id)
        if explicit:
            clauses.append(f"task_id IN ({','.join('?' for _ in explicit)})")
            arguments.extend(explicit)
        if not clauses:
            return []
        where = " OR ".join(f"({clause})" for clause in clauses)
        with self._connection() as connection:
            rows = connection.execute(
                f"SELECT task_id FROM agent_tasks WHERE {where} ORDER BY created_at ASC, task_id ASC",
                arguments,
            ).fetchall()
            deleted = [str(row[0]) for row in rows]
            connection.execute(f"DELETE FROM agent_tasks WHERE {where}", arguments)
        return deleted

    def count(self) -> int:
        with self._connection() as connection:
            row = connection.execute("SELECT COUNT(*) FROM agent_tasks").fetchone()
        return int(row[0] if row else 0)

    @contextmanager
    def _connection(self) -> Iterator[sqlite3.Connection]:
        connection = sqlite3.connect(self.path, timeout=30.0)
        try:
            connection.execute("PRAGMA journal_mode = WAL")
            connection.execute("PRAGMA synchronous = NORMAL")
            yield connection
            connection.commit()
        finally:
            connection.close()

    @staticmethod
    def _decode(value: object) -> dict | None:
        try:
            decoded = json.loads(str(value or ""))
            return decoded if isinstance(decoded, dict) else None
        except (TypeError, ValueError):
            return None

    @staticmethod
    def _like_prefix(value: str) -> str:
        escaped = (
            str(value or "")
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        )
        return f"{escaped}%"
