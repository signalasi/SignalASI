"""Managed local MCP connections for SignalASI Desktop."""
from __future__ import annotations

import argparse
import json
import os
import re
import threading
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

from mcp_agent_wrapper import call_mcp, list_mcp_tools


MCP_ID = re.compile(r"[a-z0-9][a-z0-9._-]{1,63}\Z")


def _state_path() -> Path:
    configured = str(os.environ.get("SIGNALASI_STATE_DIR") or "").strip()
    root = Path(configured) if configured else Path(os.environ.get("APPDATA") or Path.home()) / "SignalASI"
    return root / "desktop-mcp.json"


@dataclass(frozen=True)
class DesktopMcpConnection:
    id: str
    name: str
    command: str
    default_tool: str = ""
    triggers: tuple[str, ...] = ()
    enabled: bool = True
    auto_invoke: bool = False
    timeout_seconds: int = 20
    updated_at: int = 0

    def public(self, include_command: bool = False) -> dict[str, Any]:
        value = asdict(self)
        value["triggers"] = list(self.triggers)
        value["transport"] = "stdio"
        value["configured"] = bool(self.command)
        if not include_command:
            value.pop("command", None)
        return value


class DesktopMcpRegistry:
    def __init__(self, path: Path | None = None) -> None:
        self.path = Path(path) if path else _state_path()
        self._lock = threading.RLock()

    def list(self, include_command: bool = False) -> list[dict[str, Any]]:
        return [item.public(include_command) for item in self._load()]

    def get(self, connection_id: str) -> DesktopMcpConnection | None:
        return next((item for item in self._load() if item.id == str(connection_id)), None)

    def upsert(self, value: dict[str, Any]) -> dict[str, Any]:
        connection_id = str(value.get("id") or "").strip().casefold()
        if not MCP_ID.fullmatch(connection_id):
            raise ValueError("MCP connection id is invalid")
        name = str(value.get("name") or "").strip()[:80]
        command = str(value.get("command") or "").strip()[:4_000]
        default_tool = str(value.get("default_tool") or "").strip()[:160]
        triggers = tuple(dict.fromkeys(str(item).strip()[:80] for item in list(value.get("triggers") or []) if str(item).strip()))[:32]
        timeout = max(3, min(int(value.get("timeout_seconds") or 20), 120))
        if not name or not command:
            raise ValueError("MCP connection requires a name and stdio server command")
        connection = DesktopMcpConnection(
            connection_id, name, command, default_tool, triggers,
            bool(value.get("enabled", True)), bool(value.get("auto_invoke", False)),
            timeout, int(time.time() * 1_000),
        )
        with self._lock:
            rows = [item for item in self._load() if item.id != connection_id]
            rows.append(connection)
            self._save(rows)
        return connection.public(include_command=True)

    def delete(self, connection_id: str) -> bool:
        with self._lock:
            rows = self._load()
            updated = [item for item in rows if item.id != str(connection_id)]
            if len(rows) == len(updated):
                return False
            self._save(updated)
            return True

    def match(self, prompt: str) -> DesktopMcpConnection | None:
        normalized = re.sub(r"\s+", " ", str(prompt or "")).casefold()
        ranked = []
        for item in self._load():
            if not item.enabled:
                continue
            explicitly_named = item.name.casefold() in normalized or item.id.casefold() in normalized
            score = sum(1 for trigger in item.triggers if trigger.casefold() in normalized) if item.auto_invoke else 0
            if explicitly_named:
                score += 2
            if score:
                ranked.append((score, item))
        return sorted(ranked, key=lambda pair: (-pair[0], pair[1].id))[0][1] if ranked else None

    def probe(self, connection_id: str) -> dict[str, Any]:
        connection = self._require(connection_id)
        started = time.monotonic()
        try:
            tools = list_mcp_tools(self._args(connection))
            return {
                "id": connection.id,
                "status": "ready",
                "duration_ms": round((time.monotonic() - started) * 1_000),
                "tools": [self._public_tool(item) for item in tools[:200]],
            }
        except Exception as exc:
            return {
                "id": connection.id,
                "status": "error",
                "duration_ms": round((time.monotonic() - started) * 1_000),
                "tools": [],
                "error": str(exc)[:500],
            }

    def invoke_prompt(
        self,
        connection_id: str,
        prompt: str,
        process_callback=None,
    ) -> dict[str, Any]:
        connection = self._require(connection_id)
        if not connection.enabled:
            raise RuntimeError("MCP connection is disabled")
        started = time.monotonic()
        result = call_mcp(
            self._args(connection),
            str(prompt or ""),
            on_process=process_callback,
        )
        return {
            "id": connection.id,
            "name": connection.name,
            "tool": connection.default_tool,
            "result": result,
            "duration_ms": round((time.monotonic() - started) * 1_000),
        }

    @staticmethod
    def _args(connection: DesktopMcpConnection) -> argparse.Namespace:
        return argparse.Namespace(
            server=connection.command,
            server_python=None,
            tool=connection.default_tool or None,
            arg_json=None,
            timeout=float(connection.timeout_seconds),
            stdio=True,
        )

    @staticmethod
    def _public_tool(value: dict[str, Any]) -> dict[str, Any]:
        return {
            "name": str(value.get("name") or "")[:160],
            "description": str(value.get("description") or "")[:1_000],
            "input_schema": value.get("inputSchema") if isinstance(value.get("inputSchema"), dict) else {},
        }

    def _require(self, connection_id: str) -> DesktopMcpConnection:
        connection = self.get(connection_id)
        if connection is None:
            raise KeyError(f"MCP connection not found: {connection_id}")
        return connection

    def _load(self) -> list[DesktopMcpConnection]:
        if not self.path.exists():
            return []
        try:
            data = json.loads(self.path.read_text(encoding="utf-8-sig"))
        except Exception:
            return []
        result: list[DesktopMcpConnection] = []
        for item in list(data.get("connections") or []):
            try:
                result.append(DesktopMcpConnection(
                    id=str(item["id"]), name=str(item["name"]), command=str(item["command"]),
                    default_tool=str(item.get("default_tool") or ""),
                    triggers=tuple(str(value) for value in list(item.get("triggers") or [])),
                    enabled=bool(item.get("enabled", True)),
                    auto_invoke=bool(item.get("auto_invoke", False)),
                    timeout_seconds=max(3, min(int(item.get("timeout_seconds") or 20), 120)),
                    updated_at=int(item.get("updated_at") or 0),
                ))
            except Exception:
                continue
        return result

    def _save(self, rows: list[DesktopMcpConnection]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(self.path.suffix + ".tmp")
        temporary.write_text(
            json.dumps({"connections": [item.public(include_command=True) for item in rows]}, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        temporary.replace(self.path)


_MCP: DesktopMcpRegistry | None = None
_MCP_LOCK = threading.Lock()


def desktop_mcp_registry() -> DesktopMcpRegistry:
    global _MCP
    with _MCP_LOCK:
        if _MCP is None:
            _MCP = DesktopMcpRegistry()
        return _MCP
