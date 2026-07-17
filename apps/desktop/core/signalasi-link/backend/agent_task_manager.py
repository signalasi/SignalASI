"""Persistent lifecycle manager for remote Agent tasks."""
from __future__ import annotations

import json
import os
import subprocess
import threading
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable


TASKS_PATH = Path.home() / ".signalasi" / "agent_tasks.json"
TERMINAL_STATES = {"completed", "failed", "cancelled", "timed_out"}
EventCallback = Callable[[dict], None]


@dataclass
class AgentTask:
    task_id: str
    agent_id: str
    contact_id: str
    source_message_id: str
    prompt: str
    conversation_id: str = ""
    client_route_id: str = ""
    status: str = "accepted"
    created_at: int = field(default_factory=lambda: int(time.time() * 1000))
    started_at: int = 0
    updated_at: int = field(default_factory=lambda: int(time.time() * 1000))
    completed_at: int = 0
    result: str = ""
    error: str = ""
    exit_code: int | None = None
    status_seq: int = 0
    thread_id: str = ""
    turn_id: str = ""
    current_step: str = ""
    output_files: list[dict] = field(default_factory=list)
    process: subprocess.Popen | None = field(default=None, repr=False, compare=False)
    cancel_requested: bool = field(default=False, repr=False, compare=False)

    def public(self, include_prompt: bool = False) -> dict:
        data = {
            "task_id": self.task_id,
            "agent_id": self.agent_id,
            "contact_id": self.contact_id,
            "source_message_id": self.source_message_id,
            "conversation_id": self.conversation_id,
            "client_route_id": self.client_route_id,
            "status": self.status,
            "created_at": self.created_at,
            "started_at": self.started_at,
            "updated_at": self.updated_at,
            "completed_at": self.completed_at,
            "elapsed_ms": max(0, (self.completed_at or self.updated_at) - (self.started_at or self.created_at)),
            "result": self.result,
            "error": self.error,
            "exit_code": self.exit_code,
            "status_seq": self.status_seq,
            "thread_id": self.thread_id,
            "turn_id": self.turn_id,
            "current_step": self.current_step,
            "output_files": self.output_files,
            "process_id": self.process.pid if self.process is not None and self.process.poll() is None else 0,
        }
        if include_prompt:
            data["prompt"] = self.prompt
        return data


class AgentTaskManager:
    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._tasks: dict[str, AgentTask] = {}
        self._recovered_task_ids: set[str] = set()
        self._load()
        if self._tasks:
            self._save_locked()

    def create(
        self,
        agent_id: str,
        contact_id: str,
        source_message_id: str,
        prompt: str,
        runner: Callable[[AgentTask], str],
        on_event: EventCallback,
        on_result: EventCallback | None = None,
        task_id: str = "",
        conversation_id: str = "",
        client_route_id: str = "",
    ) -> AgentTask:
        task = AgentTask(
            task_id=task_id.strip() or str(uuid.uuid4()),
            agent_id=agent_id,
            contact_id=contact_id,
            source_message_id=source_message_id,
            prompt=prompt,
            conversation_id=conversation_id,
            client_route_id=client_route_id,
        )
        with self._lock:
            if task.task_id in self._tasks:
                task.task_id = str(uuid.uuid4())
            self._tasks[task.task_id] = task
            self._save_locked()
        self._emit(task, on_event)
        self._set_status(task, "queued", on_event)
        threading.Thread(target=self._run, args=(task, runner, on_event, on_result), daemon=True).start()
        return task

    def create_external(
        self, agent_id: str, contact_id: str, source_message_id: str, prompt: str,
        on_event: EventCallback, task_id: str = "", conversation_id: str = "",
        client_route_id: str = "",
    ) -> AgentTask:
        task = AgentTask(
            task_id=task_id.strip() or str(uuid.uuid4()), agent_id=agent_id,
            contact_id=contact_id, source_message_id=source_message_id, prompt=prompt,
            conversation_id=conversation_id,
            client_route_id=client_route_id,
        )
        with self._lock:
            if task.task_id in self._tasks:
                task.task_id = str(uuid.uuid4())
            self._tasks[task.task_id] = task
            self._save_locked()
        self._emit(task, on_event)
        return task

    def update(
        self, task_id: str, status: str, on_event: EventCallback | None = None,
        *, thread_id: str | None = None, turn_id: str | None = None,
        current_step: str | None = None, result: str | None = None,
        error: str | None = None,
    ) -> AgentTask | None:
        with self._lock:
            task = self._tasks.get(task_id)
            if task is None or task.status in TERMINAL_STATES:
                return task
            now = int(time.time() * 1000)
            task.status = status
            task.updated_at = now
            task.status_seq += 1
            if not task.started_at and status not in {"accepted", "queued"}:
                task.started_at = now
            if thread_id is not None:
                task.thread_id = thread_id
            if turn_id is not None:
                task.turn_id = turn_id
            if current_step is not None:
                task.current_step = current_step
            if result is not None:
                task.result = result
            if error is not None:
                task.error = error
            if status in TERMINAL_STATES or status == "interrupted":
                task.completed_at = now
                task.output_files = self._task_artifacts(task.task_id)
            self._save_locked()
        self._emit(task, on_event)
        return task

    def _run(
        self,
        task: AgentTask,
        runner: Callable[[AgentTask], str],
        on_event: EventCallback,
        on_result: EventCallback | None,
    ) -> None:
        if task.cancel_requested:
            self._finish(task, "cancelled", on_event)
            return
        task.started_at = int(time.time() * 1000)
        self._set_status(task, "running", on_event)
        heartbeat_stop = threading.Event()
        heartbeat = threading.Thread(target=self._heartbeat, args=(task, on_event, heartbeat_stop), daemon=True)
        heartbeat.start()
        try:
            result = runner(task)
            if task.cancel_requested:
                self._finish(task, "cancelled", on_event)
            elif task.status == "timed_out" or self._looks_timed_out(result):
                self._finish(task, "timed_out", on_event, result=result)
            elif self._looks_failed(result):
                self._finish(task, "failed", on_event, result=result, error=result[:240])
            else:
                self._finish(task, "completed", on_event, result=result)
            if not task.cancel_requested and task.result and on_result is not None:
                self._emit(task, on_result)
        except Exception as exc:
            self._finish(task, "failed", on_event, error=str(exc)[:500])
        finally:
            heartbeat_stop.set()
            task.process = None

    def _heartbeat(self, task: AgentTask, on_event: EventCallback, stop: threading.Event) -> None:
        while not stop.wait(5):
            with self._lock:
                if task.status != "running":
                    return
                task.updated_at = int(time.time() * 1000)
                task.status_seq += 1
                self._save_locked()
            self._emit(task, on_event)

    @staticmethod
    def _looks_timed_out(result: str) -> bool:
        normalized = result.lower()
        return result.startswith("[") and ("timeout" in normalized or "timed out" in normalized or "\u8d85\u65f6" in result)

    @staticmethod
    def _looks_failed(result: str) -> bool:
        if not result.startswith("["):
            return False
        normalized = result.lower()
        return any(marker in normalized for marker in (
            "failed", "failure", "timeout", "not configured", "not detected", "no response",
            "\u5931\u8d25", "\u8d85\u65f6", "\u672a\u914d\u7f6e", "\u672a\u68c0\u6d4b", "\u65e0\u54cd\u5e94", "\u672a\u8fde\u63a5",
        ))

    def register_process(self, task_id: str, process: subprocess.Popen) -> None:
        with self._lock:
            task = self._tasks.get(task_id)
            if task is None:
                return
            task.process = process
            if task.cancel_requested:
                self._terminate(process)

    def record_exit_code(self, task_id: str, exit_code: int | None) -> None:
        with self._lock:
            task = self._tasks.get(task_id)
            if task is not None:
                task.exit_code = exit_code
                task.updated_at = int(time.time() * 1000)
                self._save_locked()

    def cancel(self, task_id: str, on_event: EventCallback | None = None) -> AgentTask | None:
        with self._lock:
            task = self._tasks.get(task_id)
            if task is None:
                return task
            if task.status in TERMINAL_STATES:
                terminal_task = task
                process = None
            else:
                terminal_task = None
                task.cancel_requested = True
                process = task.process
        if terminal_task is not None:
            self._emit(terminal_task, on_event)
            return terminal_task
        if process is not None:
            self._terminate(process)
        self._finish(task, "cancelled", on_event)
        return task

    def get(self, task_id: str) -> AgentTask | None:
        with self._lock:
            return self._tasks.get(task_id)

    def list(self, limit: int = 100) -> list[dict]:
        with self._lock:
            tasks = sorted(self._tasks.values(), key=lambda item: item.updated_at, reverse=True)
            return [item.public() for item in tasks[:max(1, min(limit, 500))]]

    def delete_conversation(self, conversation_id: str, task_ids: set[str] | None = None) -> list[str]:
        clean_id = str(conversation_id or "").strip()
        allowed_ids = {str(value).strip() for value in (task_ids or set()) if str(value).strip()}
        if not clean_id and not allowed_ids:
            return []
        with self._lock:
            deleted = [
                task_id for task_id, task in self._tasks.items()
                if (clean_id and task.conversation_id == clean_id) or task_id in allowed_ids
            ]
            for task_id in deleted:
                task = self._tasks.pop(task_id, None)
                if task is not None and task.process is not None:
                    self._terminate(task.process)
                self._recovered_task_ids.discard(task_id)
            self._save_locked()
        return deleted

    def drain_recovered(self, limit: int = 100) -> list[dict]:
        with self._lock:
            candidates = sorted(
                (self._tasks[task_id] for task_id in self._recovered_task_ids if task_id in self._tasks),
                key=lambda item: item.updated_at,
                reverse=True,
            )[:max(1, min(limit, 500))]
            for task in candidates:
                self._recovered_task_ids.discard(task.task_id)
            return [task.public() for task in candidates]

    def _set_status(self, task: AgentTask, status: str, on_event: EventCallback | None) -> None:
        with self._lock:
            if task.status in TERMINAL_STATES and status not in TERMINAL_STATES:
                return
            task.status = status
            task.updated_at = int(time.time() * 1000)
            task.status_seq += 1
            self._save_locked()
        self._emit(task, on_event)

    def _finish(self, task: AgentTask, status: str, on_event: EventCallback | None, result: str = "", error: str = "") -> None:
        with self._lock:
            if task.status in TERMINAL_STATES:
                return
            now = int(time.time() * 1000)
            task.status = status
            task.updated_at = now
            task.status_seq += 1
            task.completed_at = now
            task.result = result
            task.error = error
            task.output_files = self._task_artifacts(task.task_id)
            self._save_locked()
        self._emit(task, on_event)

    def _emit(self, task: AgentTask, on_event: EventCallback | None) -> None:
        if on_event is None:
            return
        try:
            on_event(task.public())
        except Exception:
            pass

    @staticmethod
    def _terminate(process: subprocess.Popen) -> None:
        if os.name == "nt" and process.poll() is None:
            try:
                subprocess.run(
                    ["taskkill", "/PID", str(process.pid), "/T", "/F"],
                    capture_output=True,
                    timeout=5,
                    check=False,
                )
                return
            except Exception:
                pass
        try:
            process.terminate()
            process.wait(timeout=3)
        except Exception:
            try:
                process.kill()
            except Exception:
                pass

    def _load(self) -> None:
        try:
            rows = json.loads(TASKS_PATH.read_text(encoding="utf-8"))
            for row in rows if isinstance(rows, list) else []:
                status = str(row.get("status") or "failed")
                interrupted = status not in TERMINAL_STATES
                if interrupted:
                    status = "failed"
                    row["error"] = "Desktop restarted while task was running"
                    recovered_at = int(time.time() * 1000)
                    row["completed_at"] = recovered_at
                    row["updated_at"] = recovered_at
                    row["status_seq"] = int(row.get("status_seq") or 0) + 1
                task = AgentTask(
                    task_id=str(row.get("task_id") or uuid.uuid4()),
                    agent_id=str(row.get("agent_id") or ""),
                    contact_id=str(row.get("contact_id") or ""),
                    source_message_id=str(row.get("source_message_id") or ""),
                    prompt=str(row.get("prompt") or ""),
                    conversation_id=str(row.get("conversation_id") or ""),
                    client_route_id=str(row.get("client_route_id") or ""),
                    status=status,
                    created_at=int(row.get("created_at") or 0),
                    started_at=int(row.get("started_at") or 0),
                    updated_at=int(row.get("updated_at") or 0),
                    completed_at=int(row.get("completed_at") or 0),
                    result=str(row.get("result") or ""),
                    error=str(row.get("error") or ""),
                    exit_code=row.get("exit_code"),
                    status_seq=int(row.get("status_seq") or 0),
                    thread_id=str(row.get("thread_id") or ""),
                    turn_id=str(row.get("turn_id") or ""),
                    current_step=str(row.get("current_step") or ""),
                    output_files=list(row.get("output_files") or [])[:100],
                )
                self._tasks[task.task_id] = task
                if interrupted:
                    self._recovered_task_ids.add(task.task_id)
        except Exception:
            return

    def _save_locked(self) -> None:
        try:
            TASKS_PATH.parent.mkdir(parents=True, exist_ok=True)
            rows = [task.public(include_prompt=True) for task in sorted(self._tasks.values(), key=lambda item: item.updated_at)[-500:]]
            temporary = TASKS_PATH.with_suffix(".tmp")
            temporary.write_text(json.dumps(rows, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
            temporary.replace(TASKS_PATH)
        except Exception:
            pass

    @staticmethod
    def _task_artifacts(task_id: str) -> list[dict]:
        try:
            from task_workspace import task_artifacts
            return task_artifacts(task_id)
        except Exception:
            return []


agent_task_manager = AgentTaskManager()
