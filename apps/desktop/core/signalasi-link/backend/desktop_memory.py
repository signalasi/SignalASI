"""Local long-term memory for the SignalASI Desktop super agent."""
from __future__ import annotations

import hashlib
import json
import os
import re
import sqlite3
import threading
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Any


MAX_MEMORY_ROWS = 2_000
MAX_CONTENT_CHARS = 2_000
SECRET_PATTERN = re.compile(
    r"(?i)(api[_ -]?key|access[_ -]?token|password|passwd|secret|authorization)\s*[:=]\s*\S+"
)
GREETING_PATTERN = re.compile(r"(?i)^\s*(hello|hi|hey|thanks?|ok(?:ay)?|\u4f60\u597d|\u8c22\u8c22|\u597d\u7684)[.!\s]*$")
VOLATILE_PATTERN = re.compile(
    r"(?i)(?:\b(?:today|now|current|latest|weather|news|status|cpu|ram|memory usage|battery|process(?:es)?)\b|"
    r"\u4eca\u5929|\u73b0\u5728|\u5f53\u524d|\u6700\u65b0|\u5929\u6c14|\u65b0\u95fb|\u7cfb\u7edf\u72b6\u6001|"
    r"\u7535\u91cf|\u5185\u5b58\u4f7f\u7528|\u8fdb\u7a0b)"
)
FAILED_OUTCOME_PATTERN = re.compile(
    r"(?i)(?:timed?\s*out|failed|could not|unavailable|no response|\u8d85\u65f6|\u5931\u8d25|\u65e0\u6cd5|\u65e0\u54cd\u5e94)"
)
WORD_PATTERN = re.compile(r"[a-z0-9_\-]{2,}|[\u4e00-\u9fff]")


def _state_root() -> Path:
    configured = str(os.environ.get("SIGNALASI_STATE_DIR") or "").strip()
    return Path(configured) if configured else Path(os.environ.get("APPDATA") or Path.home()) / "SignalASI"


def _clean(value: str, maximum: int = MAX_CONTENT_CHARS) -> str:
    return re.sub(r"\s+", " ", str(value or "")).strip()[:maximum]


def _tokens(value: str) -> set[str]:
    text = _clean(value).casefold()
    tokens = set(WORD_PATTERN.findall(text))
    cjk = "".join(character for character in text if "\u4e00" <= character <= "\u9fff")
    tokens.update(cjk[index:index + 2] for index in range(max(0, len(cjk) - 1)))
    return {token for token in tokens if token}


def _memory_key(content: str, kind: str) -> str:
    normalized = _clean(content).casefold()
    separators = (" is ", " are ", " should ", " uses ", " use ", "\u6539\u4e3a", "\u8bbe\u7f6e\u4e3a", "\u9ed8\u8ba4")
    topic = normalized
    for separator in separators:
        if separator in normalized:
            prefix = normalized.split(separator, 1)[0].strip()
            if 2 <= len(prefix) <= 120:
                topic = prefix
                break
    digest = hashlib.sha256(f"{kind}:{topic}".encode("utf-8")).hexdigest()[:32]
    return f"{kind}:{digest}"


class DesktopMemoryStore:
    def __init__(self, path: Path | None = None, now=time.time) -> None:
        self.path = Path(path) if path else _state_root() / "desktop-memory.db"
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.now = now
        self._lock = threading.RLock()
        self._initialize()

    @contextmanager
    def _connect(self):
        connection = sqlite3.connect(self.path, timeout=10)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute("PRAGMA synchronous=NORMAL")
        try:
            yield connection
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    def _initialize(self) -> None:
        with self._lock, self._connect() as connection:
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS memories (
                    id TEXT PRIMARY KEY,
                    memory_key TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    content TEXT NOT NULL,
                    status TEXT NOT NULL,
                    confidence REAL NOT NULL,
                    importance REAL NOT NULL,
                    source_conversation_id TEXT NOT NULL,
                    source_task_id TEXT NOT NULL,
                    tags_json TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    last_accessed_at INTEGER NOT NULL,
                    use_count INTEGER NOT NULL DEFAULT 0,
                    supersedes_id TEXT NOT NULL DEFAULT ''
                )
                """
            )
            connection.execute("CREATE INDEX IF NOT EXISTS idx_memory_status ON memories(status, updated_at DESC)")
            connection.execute("CREATE INDEX IF NOT EXISTS idx_memory_key ON memories(memory_key, status)")

    def remember(
        self,
        content: str,
        *,
        kind: str = "fact",
        confidence: float = 0.75,
        importance: float = 0.55,
        conversation_id: str = "",
        task_id: str = "",
        tags: list[str] | None = None,
        key: str = "",
    ) -> dict[str, Any] | None:
        content = _clean(content)
        if len(content) < 4 or SECRET_PATTERN.search(content) or GREETING_PATTERN.fullmatch(content):
            return None
        kind = re.sub(r"[^a-z0-9_-]", "", str(kind or "fact").casefold())[:32] or "fact"
        key = str(key or _memory_key(content, kind))[:160]
        now_ms = int(self.now() * 1_000)
        memory_id = hashlib.sha256(f"{key}:{content}:{now_ms}".encode("utf-8")).hexdigest()[:32]
        supersedes_id = ""
        with self._lock, self._connect() as connection:
            previous = connection.execute(
                "SELECT * FROM memories WHERE memory_key = ? AND status = 'active' ORDER BY updated_at DESC LIMIT 1",
                (key,),
            ).fetchone()
            if previous and _clean(previous["content"]).casefold() == content.casefold():
                connection.execute(
                    "UPDATE memories SET confidence = ?, importance = ?, updated_at = ?, use_count = use_count + 1 WHERE id = ?",
                    (max(float(previous["confidence"]), confidence), max(float(previous["importance"]), importance), now_ms, previous["id"]),
                )
                return self.get(str(previous["id"]))
            if previous:
                supersedes_id = str(previous["id"])
                connection.execute("UPDATE memories SET status = 'superseded', updated_at = ? WHERE id = ?", (now_ms, supersedes_id))
            connection.execute(
                """
                INSERT INTO memories (
                    id, memory_key, kind, content, status, confidence, importance,
                    source_conversation_id, source_task_id, tags_json, created_at,
                    updated_at, last_accessed_at, use_count, supersedes_id
                ) VALUES (?, ?, ?, ?, 'active', ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                """,
                (
                    memory_id, key, kind, content, max(0.0, min(1.0, confidence)),
                    max(0.0, min(1.0, importance)), str(conversation_id)[:160], str(task_id)[:160],
                    json.dumps(sorted(set(tags or []))[:24], ensure_ascii=False), now_ms, now_ms, now_ms, supersedes_id,
                ),
            )
            self._prune(connection)
        return self.get(memory_id)

    def evolve(self, prompt: str, reply: str, *, conversation_id: str = "", task_id: str = "") -> list[dict[str, Any]]:
        prompt = _clean(prompt, 1_200)
        reply = _clean(reply, 1_200)
        if not prompt or not reply or SECRET_PATTERN.search(prompt) or GREETING_PATTERN.fullmatch(prompt):
            return []
        learned: list[dict[str, Any]] = []
        explicit = re.search(r"(?is)(?:remember(?: that)?|\u8bf7?\u8bb0\u4f4f)\s*[:：]?\s*(.+)", prompt)
        preference = re.search(r"(?is)(?:i prefer|my preference is|use .+ by default|\u6211\u559c\u6b22|\u6211\u504f\u597d|\u9ed8\u8ba4\u4f7f\u7528)\s*[:：]?\s*(.+)", prompt)
        decision = re.search(r"(?is)(?:we decided|change .+ to|set .+ to|\u51b3\u5b9a|\u6539\u4e3a|\u8bbe\u7f6e\u4e3a)\s*[:：]?\s*(.+)", prompt)
        if explicit:
            value = self.remember(explicit.group(1), kind="explicit", confidence=0.98, importance=0.9, conversation_id=conversation_id, task_id=task_id)
            if value:
                learned.append(value)
        elif preference:
            value = self.remember(prompt, kind="preference", confidence=0.9, importance=0.8, conversation_id=conversation_id, task_id=task_id)
            if value:
                learned.append(value)
        elif decision:
            value = self.remember(prompt, kind="decision", confidence=0.88, importance=0.82, conversation_id=conversation_id, task_id=task_id)
            if value:
                learned.append(value)

        if (
            len(prompt) >= 12
            and len(reply) >= 24
            and not VOLATILE_PATTERN.search(prompt)
            and not FAILED_OUTCOME_PATTERN.search(reply)
        ):
            episode = f"Request: {prompt[:500]} Outcome: {reply[:700]}"
            value = self.remember(
                episode,
                kind="episode",
                confidence=0.7,
                importance=0.42,
                conversation_id=conversation_id,
                task_id=task_id,
                key=f"episode:{task_id or hashlib.sha256(prompt.encode('utf-8')).hexdigest()[:24]}",
            )
            if value:
                learned.append(value)
        return learned

    def search(self, query: str, limit: int = 8) -> list[dict[str, Any]]:
        query_tokens = _tokens(query)
        if not query_tokens:
            return []
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                "SELECT * FROM memories WHERE status = 'active' ORDER BY importance DESC, updated_at DESC LIMIT 500"
            ).fetchall()
            now_ms = int(self.now() * 1_000)
            ranked: list[tuple[float, sqlite3.Row]] = []
            for row in rows:
                memory_tokens = _tokens(str(row["content"]))
                overlap = len(query_tokens & memory_tokens)
                if overlap <= 0:
                    continue
                coverage = overlap / max(1, len(query_tokens))
                age_days = max(0.0, (now_ms - int(row["updated_at"])) / 86_400_000)
                recency = 1.0 / (1.0 + age_days / 30.0)
                score = coverage * 0.62 + float(row["importance"]) * 0.25 + recency * 0.13
                ranked.append((score, row))
            selected = [row for _score, row in sorted(ranked, key=lambda item: item[0], reverse=True)[:max(1, min(limit, 20))]]
            if selected:
                connection.executemany(
                    "UPDATE memories SET last_accessed_at = ?, use_count = use_count + 1 WHERE id = ?",
                    [(now_ms, row["id"]) for row in selected],
                )
        return [self._public(row) for row in selected]

    def compile_context(self, query: str, *, limit: int = 6, max_chars: int = 5_000) -> str:
        rows = self.search(query, limit=limit)
        lines = [f"- [{row['kind']}] {row['content']}" for row in rows]
        return "\n".join(lines)[:max_chars]

    def list(self, limit: int = 100, status: str = "active") -> list[dict[str, Any]]:
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                "SELECT * FROM memories WHERE status = ? ORDER BY importance DESC, updated_at DESC LIMIT ?",
                (status, max(1, min(limit, 500))),
            ).fetchall()
        return [self._public(row) for row in rows]

    def get(self, memory_id: str) -> dict[str, Any] | None:
        with self._lock, self._connect() as connection:
            row = connection.execute("SELECT * FROM memories WHERE id = ?", (str(memory_id),)).fetchone()
        return self._public(row) if row else None

    def forget(self, memory_id: str) -> bool:
        with self._lock, self._connect() as connection:
            cursor = connection.execute("UPDATE memories SET status = 'retracted', updated_at = ? WHERE id = ?", (int(self.now() * 1_000), str(memory_id)))
            return cursor.rowcount > 0

    def clear(self) -> int:
        with self._lock, self._connect() as connection:
            count = int(connection.execute("SELECT COUNT(*) FROM memories").fetchone()[0])
            connection.execute("DELETE FROM memories")
            return count

    def stats(self) -> dict[str, Any]:
        with self._lock, self._connect() as connection:
            rows = connection.execute("SELECT status, COUNT(*) AS count FROM memories GROUP BY status").fetchall()
        counts = {str(row["status"]): int(row["count"]) for row in rows}
        return {"active": counts.get("active", 0), "superseded": counts.get("superseded", 0), "retracted": counts.get("retracted", 0), "total": sum(counts.values())}

    @staticmethod
    def _public(row: sqlite3.Row) -> dict[str, Any]:
        return {
            "id": str(row["id"]), "kind": str(row["kind"]), "content": str(row["content"]),
            "status": str(row["status"]), "confidence": float(row["confidence"]),
            "importance": float(row["importance"]), "conversation_id": str(row["source_conversation_id"]),
            "task_id": str(row["source_task_id"]), "tags": json.loads(str(row["tags_json"] or "[]")),
            "created_at": int(row["created_at"]), "updated_at": int(row["updated_at"]),
            "last_accessed_at": int(row["last_accessed_at"]), "use_count": int(row["use_count"]),
            "supersedes_id": str(row["supersedes_id"]),
        }

    @staticmethod
    def _prune(connection: sqlite3.Connection) -> None:
        count = int(connection.execute("SELECT COUNT(*) FROM memories").fetchone()[0])
        excess = count - MAX_MEMORY_ROWS
        if excess > 0:
            connection.execute(
                "DELETE FROM memories WHERE id IN (SELECT id FROM memories ORDER BY CASE status WHEN 'active' THEN 1 ELSE 0 END, importance ASC, updated_at ASC LIMIT ?)",
                (excess,),
            )


_MEMORY: DesktopMemoryStore | None = None
_MEMORY_LOCK = threading.Lock()


def desktop_memory_store() -> DesktopMemoryStore:
    global _MEMORY
    with _MEMORY_LOCK:
        if _MEMORY is None:
            _MEMORY = DesktopMemoryStore()
        return _MEMORY
