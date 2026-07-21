"""Durable adapter control plane for independently installed desktop agents.

The adapter layer deliberately does not own Codex, Claude Code, Hermes,
OpenClaw, or model runtimes. It gives those independently upgradeable
processes one delivery, protocol, idempotency, status, event, cancellation,
and recovery contract.
"""
from __future__ import annotations

import hashlib
import json
import os
import threading
import time
import uuid
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Callable, Iterable


STATE_VERSION = 1
MAX_RUNS = 1_000
MAX_EVENTS_PER_RUN = 64
MAX_OBSERVATIONS = 500
MAX_OBSERVATION_CHARS = 16_000
TERMINAL_STATES = {"completed", "failed", "cancelled", "observed", "ignored"}


class AgentDeliveryMode(str, Enum):
    RESPOND = "respond"
    OBSERVE = "observe"
    IGNORE = "ignore"

    @classmethod
    def parse(cls, value: str | "AgentDeliveryMode") -> "AgentDeliveryMode":
        if isinstance(value, cls):
            return value
        normalized = str(value or cls.RESPOND.value).strip().lower()
        try:
            return cls(normalized)
        except ValueError as exc:
            raise ValueError(f"Unsupported delivery mode: {value}") from exc


@dataclass(frozen=True)
class AgentAdapterDescriptor:
    agent_id: str
    name: str
    kind: str
    adapter_type: str
    timeout_seconds: int
    capabilities: tuple[str, ...] = ()
    protocols: tuple[str, ...] = ("1.0",)
    features: frozenset[str] = frozenset({
        "delivery_modes",
        "durable_idempotency",
        "event_cursor",
        "run_recovery",
    })
    independently_upgradeable: bool = True

    def public(self) -> dict:
        return {
            "agent_id": self.agent_id,
            "name": self.name,
            "kind": self.kind,
            "adapter_type": self.adapter_type,
            "timeout_seconds": self.timeout_seconds,
            "capabilities": list(self.capabilities),
            "protocols": list(self.protocols),
            "features": sorted(self.features),
            "independently_upgradeable": self.independently_upgradeable,
            "delivery_modes": [mode.value for mode in AgentDeliveryMode],
        }


@dataclass(frozen=True)
class AgentAdapterRequest:
    agent_id: str
    prompt: str
    run_id: str = ""
    idempotency_key: str = ""
    delivery_mode: AgentDeliveryMode = AgentDeliveryMode.RESPOND
    protocol: str = "1.0"
    required_features: frozenset[str] = frozenset()
    allow_protocol_downgrade: bool = True
    conversation_id: str = ""
    source_message_id: str = ""
    return_path: str = ""
    checkpoint: dict = field(default_factory=dict)
    artifacts: tuple[dict, ...] = ()

    def normalized(self) -> "AgentAdapterRequest":
        run_id = self.run_id.strip() or str(uuid.uuid4())
        return AgentAdapterRequest(
            agent_id=self.agent_id.strip(),
            prompt=str(self.prompt or ""),
            run_id=run_id,
            idempotency_key=self.idempotency_key.strip() or run_id,
            delivery_mode=AgentDeliveryMode.parse(self.delivery_mode),
            protocol=str(self.protocol or "1.0").strip() or "1.0",
            required_features=frozenset(str(item).strip() for item in self.required_features if str(item).strip()),
            allow_protocol_downgrade=bool(self.allow_protocol_downgrade),
            conversation_id=str(self.conversation_id or "").strip(),
            source_message_id=str(self.source_message_id or "").strip(),
            return_path=str(self.return_path or "").strip(),
            checkpoint=dict(self.checkpoint or {}),
            artifacts=tuple(dict(item) for item in self.artifacts if isinstance(item, dict)),
        )


@dataclass(frozen=True)
class AgentAdapterResult:
    run_id: str
    agent_id: str
    delivery_mode: AgentDeliveryMode
    state: str
    reply: str = ""
    error: str = ""
    cursor: int = 0
    negotiated_protocol: str = ""
    replayed: bool = False
    executed: bool = False
    checkpoint: dict = field(default_factory=dict)
    artifacts: tuple[dict, ...] = ()

    @property
    def terminal(self) -> bool:
        return self.state in TERMINAL_STATES

    def public(self) -> dict:
        return {
            "run_id": self.run_id,
            "agent_id": self.agent_id,
            "delivery_mode": self.delivery_mode.value,
            "state": self.state,
            "reply": self.reply,
            "error": self.error,
            "cursor": self.cursor,
            "negotiated_protocol": self.negotiated_protocol,
            "replayed": self.replayed,
            "executed": self.executed,
            "checkpoint": dict(self.checkpoint),
            "artifacts": [dict(item) for item in self.artifacts],
        }


class AgentAdapterError(RuntimeError):
    pass


class AgentAdapterConflict(AgentAdapterError):
    pass


class AgentAdapterProtocolError(AgentAdapterError):
    pass


class AgentAdapterExecutionError(AgentAdapterError):
    pass


class DesktopAgentStateStore:
    """Atomic durable receipts, event cursors, and observation context."""

    def __init__(self, path: Path, now: Callable[[], float] = time.time) -> None:
        self.path = Path(path)
        self._now = now
        self._lock = threading.RLock()
        self._state = self._load()
        self._recover_interrupted_locked()

    def claim(
        self,
        request: AgentAdapterRequest,
        descriptor: AgentAdapterDescriptor,
        negotiated_protocol: str,
    ) -> AgentAdapterResult | None:
        fingerprint = self._fingerprint(request)
        now_ms = self._now_ms()
        with self._lock:
            existing = self._state["runs"].get(request.idempotency_key)
            if isinstance(existing, dict):
                if str(existing.get("fingerprint") or "") != fingerprint:
                    raise AgentAdapterConflict(
                        f"Idempotency key {request.idempotency_key} was already used for a different request"
                    )
                return self._result(existing, replayed=True)
            row = {
                "idempotency_key": request.idempotency_key,
                "run_id": request.run_id,
                "agent_id": request.agent_id,
                "adapter_type": descriptor.adapter_type,
                "delivery_mode": request.delivery_mode.value,
                "fingerprint": fingerprint,
                "state": "running",
                "reply": "",
                "error": "",
                "cursor": 1,
                "created_at": now_ms,
                "updated_at": now_ms,
                "negotiated_protocol": negotiated_protocol,
                "conversation_id": request.conversation_id,
                "source_message_id": request.source_message_id,
                "return_path": request.return_path,
                "checkpoint": dict(request.checkpoint),
                "artifacts": [dict(item) for item in request.artifacts],
                "events": [self._event(1, "run_started", now_ms)],
            }
            self._state["runs"][request.idempotency_key] = row
            self._prune_locked()
            self._save_locked()
        return None

    def observe(
        self,
        request: AgentAdapterRequest,
        descriptor: AgentAdapterDescriptor,
        negotiated_protocol: str,
    ) -> AgentAdapterResult:
        existing = self.claim(request, descriptor, negotiated_protocol)
        if existing is not None:
            return existing
        now_ms = self._now_ms()
        with self._lock:
            row = self._required_row(request.idempotency_key)
            self._state["observations"].append({
                "observation_id": request.run_id,
                "agent_id": request.agent_id,
                "conversation_id": request.conversation_id,
                "source_message_id": request.source_message_id,
                "content": request.prompt[:MAX_OBSERVATION_CHARS],
                "content_sha256": hashlib.sha256(request.prompt.encode("utf-8", errors="replace")).hexdigest(),
                "created_at": now_ms,
            })
            del self._state["observations"][:-MAX_OBSERVATIONS]
            self._finish_row(row, "observed", now_ms)
            self._save_locked()
            return self._result(row, executed=False)

    def complete(
        self,
        idempotency_key: str,
        reply: str,
        checkpoint: dict | None = None,
        artifacts: Iterable[dict] = (),
    ) -> AgentAdapterResult:
        now_ms = self._now_ms()
        with self._lock:
            row = self._required_row(idempotency_key)
            row["reply"] = str(reply or "")
            if checkpoint is not None:
                row["checkpoint"] = dict(checkpoint)
            new_artifacts = [dict(item) for item in artifacts if isinstance(item, dict)]
            if new_artifacts:
                row["artifacts"] = new_artifacts
            self._finish_row(row, "completed", now_ms)
            self._save_locked()
            return self._result(row, executed=True)

    def fail(self, idempotency_key: str, error: str) -> AgentAdapterResult:
        now_ms = self._now_ms()
        with self._lock:
            row = self._required_row(idempotency_key)
            row["error"] = str(error or "Agent execution failed")[:2_000]
            self._finish_row(row, "failed", now_ms)
            self._save_locked()
            return self._result(row, executed=True)

    def cancel(self, run_id: str) -> AgentAdapterResult | None:
        now_ms = self._now_ms()
        with self._lock:
            row = self._find_run_locked(run_id)
            if row is None:
                return None
            if str(row.get("state") or "") not in TERMINAL_STATES:
                self._finish_row(row, "cancelled", now_ms)
                self._save_locked()
            return self._result(row)

    def status(self, run_id: str) -> AgentAdapterResult | None:
        with self._lock:
            row = self._find_run_locked(run_id)
            return self._result(row) if row is not None else None

    def events(self, run_id: str, after_cursor: int = 0) -> list[dict]:
        with self._lock:
            row = self._find_run_locked(run_id)
            if row is None:
                return []
            return [dict(item) for item in row.get("events", []) if int(item.get("cursor") or 0) > after_cursor]

    def recoverable(self, agent_id: str = "") -> list[AgentAdapterResult]:
        with self._lock:
            rows = self._state["runs"].values()
            return [
                self._result(row)
                for row in rows
                if str(row.get("state") or "") == "interrupted"
                and (not agent_id or str(row.get("agent_id") or "") == agent_id)
            ]

    def observations(self, agent_id: str = "", limit: int = 100) -> list[dict]:
        with self._lock:
            rows = self._state["observations"]
            selected = [row for row in rows if not agent_id or str(row.get("agent_id") or "") == agent_id]
            return [dict(row) for row in selected[-max(1, min(int(limit or 100), 500)):]]

    def _load(self) -> dict:
        try:
            payload = json.loads(self.path.read_text(encoding="utf-8"))
            if isinstance(payload, dict):
                runs = payload.get("runs") if isinstance(payload.get("runs"), dict) else {}
                observations = payload.get("observations") if isinstance(payload.get("observations"), list) else []
                return {"version": STATE_VERSION, "runs": runs, "observations": observations}
        except (FileNotFoundError, json.JSONDecodeError, OSError):
            pass
        return {"version": STATE_VERSION, "runs": {}, "observations": []}

    def _recover_interrupted_locked(self) -> None:
        changed = False
        now_ms = self._now_ms()
        with self._lock:
            for row in self._state["runs"].values():
                if str(row.get("state") or "") == "running":
                    row["state"] = "interrupted"
                    row["error"] = "Desktop stopped before the adapter Run reached a terminal state"
                    row["updated_at"] = now_ms
                    cursor = int(row.get("cursor") or 0) + 1
                    row["cursor"] = cursor
                    row.setdefault("events", []).append(self._event(cursor, "run_interrupted", now_ms))
                    changed = True
            if changed:
                self._save_locked()

    def _finish_row(self, row: dict, state: str, now_ms: int) -> None:
        if str(row.get("state") or "") in TERMINAL_STATES:
            return
        row["state"] = state
        row["updated_at"] = now_ms
        cursor = int(row.get("cursor") or 0) + 1
        row["cursor"] = cursor
        events = row.setdefault("events", [])
        events.append(self._event(cursor, f"run_{state}", now_ms))
        del events[:-MAX_EVENTS_PER_RUN]

    def _required_row(self, idempotency_key: str) -> dict:
        row = self._state["runs"].get(idempotency_key)
        if not isinstance(row, dict):
            raise AgentAdapterError(f"Unknown adapter Run: {idempotency_key}")
        return row

    def _find_run_locked(self, run_id: str) -> dict | None:
        direct = self._state["runs"].get(run_id)
        if isinstance(direct, dict):
            return direct
        for row in self._state["runs"].values():
            if str(row.get("run_id") or "") == run_id:
                return row
        return None

    def _prune_locked(self) -> None:
        runs = self._state["runs"]
        if len(runs) <= MAX_RUNS:
            return
        terminal = sorted(
            (
                (key, row)
                for key, row in runs.items()
                if str(row.get("state") or "") in TERMINAL_STATES
            ),
            key=lambda item: int(item[1].get("updated_at") or 0),
        )
        for key, _row in terminal[:max(0, len(runs) - MAX_RUNS)]:
            runs.pop(key, None)

    def _save_locked(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(f"{self.path.suffix}.tmp")
        temporary.write_text(
            json.dumps(self._state, ensure_ascii=False, separators=(",", ":")),
            encoding="utf-8",
        )
        try:
            os.chmod(temporary, 0o600)
        except OSError:
            pass
        temporary.replace(self.path)

    def _result(self, row: dict, replayed: bool = False, executed: bool = False) -> AgentAdapterResult:
        return AgentAdapterResult(
            run_id=str(row.get("run_id") or ""),
            agent_id=str(row.get("agent_id") or ""),
            delivery_mode=AgentDeliveryMode.parse(str(row.get("delivery_mode") or "respond")),
            state=str(row.get("state") or "unknown"),
            reply=str(row.get("reply") or ""),
            error=str(row.get("error") or ""),
            cursor=int(row.get("cursor") or 0),
            negotiated_protocol=str(row.get("negotiated_protocol") or ""),
            replayed=replayed,
            executed=executed,
            checkpoint=dict(row.get("checkpoint") or {}),
            artifacts=tuple(dict(item) for item in row.get("artifacts", []) if isinstance(item, dict)),
        )

    @staticmethod
    def _fingerprint(request: AgentAdapterRequest) -> str:
        body = json.dumps(
            {
                "agent_id": request.agent_id,
                "prompt": request.prompt,
                "delivery_mode": request.delivery_mode.value,
                "conversation_id": request.conversation_id,
                "source_message_id": request.source_message_id,
            },
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        return hashlib.sha256(body).hexdigest()

    @staticmethod
    def _event(cursor: int, event_type: str, timestamp_ms: int) -> dict:
        return {"cursor": cursor, "type": event_type, "timestamp": timestamp_ms}

    def _now_ms(self) -> int:
        return int(self._now() * 1_000)


AgentExecutor = Callable[[AgentAdapterRequest], str]
CancelExecutor = Callable[[str], None]


class DesktopAgentAdapter:
    adapter_type = "desktop-agent"

    def __init__(
        self,
        descriptor: AgentAdapterDescriptor,
        store: DesktopAgentStateStore,
        executor: AgentExecutor,
        cancel_executor: CancelExecutor | None = None,
    ) -> None:
        self.descriptor = descriptor
        self.store = store
        self.executor = executor
        self.cancel_executor = cancel_executor
        self._inflight_lock = threading.RLock()
        self._inflight: dict[str, threading.Event] = {}

    def deliver(self, request: AgentAdapterRequest) -> AgentAdapterResult:
        request = request.normalized()
        if request.agent_id != self.descriptor.agent_id:
            raise AgentAdapterError(
                f"Adapter {self.descriptor.agent_id} cannot execute request for {request.agent_id}"
            )
        negotiated = self._negotiate(request)
        if request.delivery_mode == AgentDeliveryMode.IGNORE:
            return AgentAdapterResult(
                run_id=request.run_id,
                agent_id=request.agent_id,
                delivery_mode=request.delivery_mode,
                state="ignored",
                negotiated_protocol=negotiated,
            )
        if request.delivery_mode == AgentDeliveryMode.OBSERVE:
            return self.store.observe(request, self.descriptor, negotiated)

        existing = self.store.claim(request, self.descriptor, negotiated)
        if existing is not None:
            if existing.state == "running":
                return self._wait_for_inflight(request.idempotency_key, existing)
            return existing

        completion = threading.Event()
        with self._inflight_lock:
            self._inflight[request.idempotency_key] = completion
        try:
            reply = self.executor(request)
            if not str(reply or "").strip():
                raise AgentAdapterExecutionError(f"{self.descriptor.name} returned no response")
            return self.store.complete(request.idempotency_key, str(reply))
        except Exception as exc:
            self.store.fail(request.idempotency_key, str(exc))
            if isinstance(exc, AgentAdapterError):
                raise
            raise AgentAdapterExecutionError(str(exc)) from exc
        finally:
            completion.set()
            with self._inflight_lock:
                self._inflight.pop(request.idempotency_key, None)

    def status(self, run_id: str) -> AgentAdapterResult | None:
        return self.store.status(run_id)

    def events(self, run_id: str, after_cursor: int = 0) -> list[dict]:
        return self.store.events(run_id, after_cursor)

    def recover(self) -> list[AgentAdapterResult]:
        return self.store.recoverable(self.descriptor.agent_id)

    def cancel(self, run_id: str) -> AgentAdapterResult | None:
        if self.cancel_executor is not None:
            self.cancel_executor(run_id)
        return self.store.cancel(run_id)

    def _negotiate(self, request: AgentAdapterRequest) -> str:
        protocols = self.descriptor.protocols
        if request.protocol in protocols:
            negotiated = request.protocol
        elif request.allow_protocol_downgrade and protocols:
            negotiated = protocols[-1]
        else:
            raise AgentAdapterProtocolError(
                f"{self.descriptor.name} does not support protocol {request.protocol}"
            )
        missing = request.required_features - self.descriptor.features
        if missing:
            raise AgentAdapterProtocolError(
                f"{self.descriptor.name} lacks required features: {', '.join(sorted(missing))}"
            )
        return negotiated

    def _wait_for_inflight(self, idempotency_key: str, fallback: AgentAdapterResult) -> AgentAdapterResult:
        with self._inflight_lock:
            completion = self._inflight.get(idempotency_key)
        if completion is None:
            return fallback
        completion.wait(timeout=max(1, self.descriptor.timeout_seconds))
        return self.store.status(idempotency_key) or fallback


class HermesAgentAdapter(DesktopAgentAdapter):
    adapter_type = "hermes-cli"


class CodexAgentAdapter(DesktopAgentAdapter):
    adapter_type = "codex-app-server-or-cli"


class ClaudeCodeAgentAdapter(DesktopAgentAdapter):
    adapter_type = "claude-code-cli"


class OpenClawAgentAdapter(DesktopAgentAdapter):
    adapter_type = "openclaw-cli"


class LocalModelAgentAdapter(DesktopAgentAdapter):
    adapter_type = "local-model-api"


class CloudModelAgentAdapter(DesktopAgentAdapter):
    adapter_type = "cloud-model-api"


class CustomAgentAdapter(DesktopAgentAdapter):
    adapter_type = "custom-agent"


class WindowsHostAgentAdapter(DesktopAgentAdapter):
    adapter_type = "windows-host-tools"


class AndroidDeviceAgentAdapter(DesktopAgentAdapter):
    adapter_type = "android-device-tools"


ADAPTER_CLASSES: dict[str, type[DesktopAgentAdapter]] = {
    "hermes": HermesAgentAdapter,
    "codex": CodexAgentAdapter,
    "claude": ClaudeCodeAgentAdapter,
    "openclaw": OpenClawAgentAdapter,
    "local-llm": LocalModelAgentAdapter,
    "cloud-model": CloudModelAgentAdapter,
    "windows": WindowsHostAgentAdapter,
    "android": AndroidDeviceAgentAdapter,
}


class DesktopAgentProvider:
    """Enumerates and executes every registered desktop Agent through one API."""

    def __init__(
        self,
        descriptors: Iterable[AgentAdapterDescriptor],
        store: DesktopAgentStateStore,
        executor: Callable[[str, AgentAdapterRequest], str],
        cancel_executor: CancelExecutor | None = None,
    ) -> None:
        self.store = store
        self.executor = executor
        self.cancel_executor = cancel_executor
        self._lock = threading.RLock()
        self._adapters: dict[str, DesktopAgentAdapter] = {}
        self.sync(descriptors)

    def sync(self, descriptors: Iterable[AgentAdapterDescriptor]) -> None:
        with self._lock:
            incoming = {descriptor.agent_id: descriptor for descriptor in descriptors}
            retained: dict[str, DesktopAgentAdapter] = {}
            for agent_id, descriptor in incoming.items():
                current = self._adapters.get(agent_id)
                adapter_class = ADAPTER_CLASSES.get(agent_id, CustomAgentAdapter)
                if current is not None and type(current) is adapter_class and current.descriptor == descriptor:
                    retained[agent_id] = current
                    continue
                retained[agent_id] = adapter_class(
                    descriptor=AgentAdapterDescriptor(
                        agent_id=descriptor.agent_id,
                        name=descriptor.name,
                        kind=descriptor.kind,
                        adapter_type=adapter_class.adapter_type,
                        timeout_seconds=descriptor.timeout_seconds,
                        capabilities=descriptor.capabilities,
                        protocols=descriptor.protocols,
                        features=descriptor.features,
                        independently_upgradeable=descriptor.independently_upgradeable,
                    ),
                    store=self.store,
                    executor=lambda request, selected=agent_id: self.executor(selected, request),
                    cancel_executor=self.cancel_executor,
                )
            self._adapters = retained

    def enumerate(self) -> list[dict]:
        with self._lock:
            return [adapter.descriptor.public() for adapter in self._adapters.values()]

    def deliver(self, request: AgentAdapterRequest) -> AgentAdapterResult:
        with self._lock:
            adapter = self._adapters.get(request.agent_id)
        if adapter is None:
            raise AgentAdapterError(f"Unknown Agent adapter: {request.agent_id}")
        return adapter.deliver(request)

    def status(self, agent_id: str, run_id: str) -> AgentAdapterResult | None:
        return self._required(agent_id).status(run_id)

    def events(self, agent_id: str, run_id: str, after_cursor: int = 0) -> list[dict]:
        return self._required(agent_id).events(run_id, after_cursor)

    def recover(self, agent_id: str = "") -> list[AgentAdapterResult]:
        if agent_id:
            return self._required(agent_id).recover()
        return self.store.recoverable()

    def cancel(self, agent_id: str, run_id: str) -> AgentAdapterResult | None:
        return self._required(agent_id).cancel(run_id)

    def observations(self, agent_id: str = "", limit: int = 100) -> list[dict]:
        return self.store.observations(agent_id, limit)

    def _required(self, agent_id: str) -> DesktopAgentAdapter:
        with self._lock:
            adapter = self._adapters.get(agent_id)
        if adapter is None:
            raise AgentAdapterError(f"Unknown Agent adapter: {agent_id}")
        return adapter
