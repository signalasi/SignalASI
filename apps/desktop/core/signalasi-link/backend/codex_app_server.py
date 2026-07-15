"""Codex App Server JSON-RPC client used for observable remote tasks."""
from __future__ import annotations

import json
import os
import queue
import subprocess
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable


TaskEvent = Callable[[str, dict], None]
CONVERSATION_THREADS_PATH = Path.home() / ".signalasi" / "codex_conversation_threads.json"
CONVERSATION_THREAD_VERSION = "v2"
CODEX_TASK_POLICY = """
SignalASI execution policy:
- Do not inspect or invoke personal Codex Skills. Execute the request with the model and available tools directly.
- Preserve the user's requested source, output format, and presentation constraints exactly.
- When only one component of a webpage is requested, never return the parent page URL. Extract the original media URL or return minimal HTML containing only the original component.
- If a required source or tool is unavailable, report that failure. Do not synthesize replacement media or data.
- For current information, verify the date and requested location, prefer primary or authoritative sources, and cite the source concisely.
- Never expose internal task workspace or attachment download paths. Refer to uploaded inputs by their original filename only.
""".strip()


@dataclass
class CodexRun:
    task_id: str
    thread_id: str = ""
    turn_id: str = ""
    final_text: str = ""
    pending_requests: dict[int, dict] = field(default_factory=dict)


class CodexAppServer:
    def __init__(self, executable: str, env: dict[str, str], on_event: TaskEvent) -> None:
        self.executable = executable
        self.env = env
        self.on_event = on_event
        self.process: subprocess.Popen | None = None
        self._lock = threading.RLock()
        self._next_id = 1
        self._pending: dict[int, queue.Queue] = {}
        self._runs: dict[str, CodexRun] = {}
        self._turn_tasks: dict[str, str] = {}
        self._conversation_threads: dict[str, str] = self._load_conversation_threads()
        self._initialized_process_pid = 0

    def warm(self) -> dict[str, object]:
        """Start and initialize the official Codex App Server without creating a task."""
        started = time.perf_counter()
        self._ensure_started()
        return {
            "ready": True,
            "pid": self.process.pid if self.process is not None else 0,
            "elapsed_ms": round((time.perf_counter() - started) * 1000, 1),
        }

    def is_ready(self) -> bool:
        return (
            self.process is not None and self.process.poll() is None and
            self._initialized_process_pid == self.process.pid
        )

    def start_task(
        self,
        task_id: str,
        prompt: str,
        cwd: str,
        model: str = "gpt-5.6-sol",
        conversation_id: str = "",
    ) -> CodexRun:
        self._ensure_started()
        run = CodexRun(task_id=task_id)
        self._runs[task_id] = run
        conversation_key = self._conversation_key(conversation_id)
        run.thread_id = self._conversation_threads.get(conversation_key, "") if conversation_key else ""
        if not run.thread_id:
            run.thread_id = self._start_thread(cwd, model, conversation_id)
        if not run.thread_id:
            raise RuntimeError("Codex App Server did not return a thread id")
        self.on_event(task_id, {"status": "starting", "thread_id": run.thread_id, "current_step": "Starting Codex turn"})
        try:
            response = self._start_turn(run.thread_id, prompt, model)
        except RuntimeError as exc:
            if not run.thread_id or "thread not found" not in str(exc).lower():
                raise
            if conversation_id:
                self._conversation_threads.pop(conversation_key, None)
                self._save_conversation_threads()
            run.thread_id = self._start_thread(cwd, model, conversation_id)
            self.on_event(task_id, {
                "status": "starting", "thread_id": run.thread_id,
                "current_step": "Starting a fresh Codex thread",
            })
            response = self._start_turn(run.thread_id, prompt, model)
        run.turn_id = str((response.get("turn") or {}).get("id") or "")
        if run.turn_id:
            self._turn_tasks[run.turn_id] = task_id
        return run

    def _start_thread(self, cwd: str, model: str, conversation_id: str) -> str:
        response = self._request("thread/start", {
            "cwd": os.path.abspath(cwd), "model": model, "ephemeral": False,
            "approvalPolicy": "never", "sandbox": "workspace-write",
        }, timeout=30)
        thread_id = str((response.get("thread") or {}).get("id") or "")
        if conversation_id and thread_id:
            self._conversation_threads[self._conversation_key(conversation_id)] = thread_id
            self._save_conversation_threads()
        return thread_id

    @staticmethod
    def _conversation_key(conversation_id: str) -> str:
        value = str(conversation_id or "").strip()
        return f"{CONVERSATION_THREAD_VERSION}:{value}" if value else ""

    def _start_turn(self, thread_id: str, prompt: str, model: str) -> dict:
        from response_policy import apply_response_policy
        styled_prompt = apply_response_policy(prompt)
        return self._request("turn/start", {
            "threadId": thread_id,
            "input": [{"type": "text", "text": f"{styled_prompt.rstrip()}\n\n{CODEX_TASK_POLICY}", "text_elements": []}],
            "model": model, "effort": "low",
        }, timeout=30)

    def _load_conversation_threads(self) -> dict[str, str]:
        try:
            data = json.loads(CONVERSATION_THREADS_PATH.read_text(encoding="utf-8"))
            return {
                str(key)[:120]: str(value)[:160]
                for key, value in data.items()
                if str(key).strip() and str(value).strip()
            }
        except Exception:
            return {}

    def _save_conversation_threads(self) -> None:
        try:
            CONVERSATION_THREADS_PATH.parent.mkdir(parents=True, exist_ok=True)
            temporary = CONVERSATION_THREADS_PATH.with_suffix(".tmp")
            temporary.write_text(json.dumps(self._conversation_threads, ensure_ascii=True), encoding="utf-8")
            temporary.replace(CONVERSATION_THREADS_PATH)
        except Exception:
            pass

    def delete_conversation(self, conversation_id: str) -> bool:
        clean_id = str(conversation_id or "").strip()
        if not clean_id:
            return False
        with self._lock:
            removed = self._conversation_threads.pop(self._conversation_key(clean_id), None)
            legacy = self._conversation_threads.pop(clean_id, None)
            if removed is not None or legacy is not None:
                self._save_conversation_threads()
            return removed is not None or legacy is not None

    def interrupt(self, task_id: str) -> bool:
        run = self._runs.get(task_id)
        if not run or not run.thread_id or not run.turn_id:
            return False
        self._request("turn/interrupt", {"threadId": run.thread_id, "turnId": run.turn_id}, timeout=10)
        return True

    def close(self) -> None:
        with self._lock:
            process = self.process
            self.process = None
            self._initialized_process_pid = 0
            self._runs.clear()
            self._turn_tasks.clear()
        if process is None or process.poll() is not None:
            return
        process.terminate()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=3)

    def _ensure_started(self) -> None:
        with self._lock:
            if self.is_ready():
                return
            if self.process is None or self.process.poll() is not None:
                command = [
                    self.executable,
                    "-c", "sandbox_workspace_write.network_access=true",
                    "app-server", "--listen", "stdio://",
                ]
                if os.name == "nt" and self.executable.lower().endswith((".cmd", ".bat")):
                    command = [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/s", "/c", *command]
                self.process = subprocess.Popen(
                    command,
                    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                    text=True, encoding="utf-8", errors="replace", bufsize=1, env=self.env,
                    creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
                )
                self._initialized_process_pid = 0
                threading.Thread(target=self._read_stdout, daemon=True).start()
                threading.Thread(target=self._drain_stderr, daemon=True).start()
            self._request("initialize", {
                "clientInfo": {"name": "signalasi-desktop", "title": "SignalASI Desktop", "version": "0.1.13"},
                "capabilities": {"experimentalApi": True},
            }, timeout=15)
            self._notify("initialized", {})
            self._initialized_process_pid = self.process.pid

    def _request(self, method: str, params: dict, timeout: int) -> dict:
        with self._lock:
            request_id = self._next_id
            self._next_id += 1
            response_queue: queue.Queue = queue.Queue(maxsize=1)
            self._pending[request_id] = response_queue
            self._write({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})
        try:
            response = response_queue.get(timeout=timeout)
        except queue.Empty as exc:
            self._pending.pop(request_id, None)
            raise TimeoutError(f"Codex App Server request timed out: {method}") from exc
        if "error" in response:
            raise RuntimeError(str(response["error"]))
        return response.get("result") or {}

    def _notify(self, method: str, params: dict) -> None:
        self._write({"jsonrpc": "2.0", "method": method, "params": params})

    def _write(self, payload: dict) -> None:
        process = self.process
        if process is None or process.stdin is None or process.poll() is not None:
            raise RuntimeError("Codex App Server is not running")
        process.stdin.write(json.dumps(payload, separators=(",", ":")) + "\n")
        process.stdin.flush()

    def _read_stdout(self) -> None:
        process = self.process
        if process is None or process.stdout is None:
            return
        for line in process.stdout:
            try:
                message = json.loads(line)
            except Exception:
                continue
            if "id" in message and ("result" in message or "error" in message):
                waiter = self._pending.pop(message["id"], None)
                if waiter:
                    waiter.put(message)
                continue
            if "method" in message:
                self._handle_event(message)

    def _handle_event(self, message: dict) -> None:
        method = str(message.get("method") or "")
        params = message.get("params") or {}
        turn_id = str(params.get("turnId") or (params.get("turn") or {}).get("id") or "")
        task_id = self._turn_tasks.get(turn_id, "")
        if not task_id:
            thread_id = str(params.get("threadId") or "")
            task_id = next((key for key, run in self._runs.items() if run.thread_id == thread_id), "")
        if not task_id:
            return
        run = self._runs[task_id]
        common = {"thread_id": run.thread_id, "turn_id": turn_id or run.turn_id}
        if "id" in message:
            run.pending_requests[int(message["id"])] = message
            self.on_event(task_id, {**common, "status": "waiting_approval", "current_step": self._request_label(method)})
        elif method == "turn/started":
            if turn_id:
                run.turn_id = turn_id
                self._turn_tasks[turn_id] = task_id
            self.on_event(task_id, {**common, "turn_id": run.turn_id, "status": "running", "current_step": "Codex is working"})
        elif method == "item/started":
            self.on_event(task_id, {**common, "status": "running", "current_step": self._item_label(params.get("item") or {})})
        elif method == "item/completed":
            item = params.get("item") or {}
            if item.get("type") == "agentMessage" and item.get("text"):
                run.final_text = str(item["text"])
        elif method == "turn/completed":
            status = str((params.get("turn") or {}).get("status") or "completed")
            mapped = {"completed": "completed", "failed": "failed", "interrupted": "cancelled"}.get(status, status)
            self.on_event(task_id, {**common, "status": mapped, "current_step": "", "result": run.final_text})
        elif method == "thread/status/changed":
            status = params.get("status") or {}
            status_type = status if isinstance(status, str) else status.get("type", "")
            if status_type == "active":
                detail = status.get("activeFlags", []) if isinstance(status, dict) else []
                if "waitingOnApproval" in detail:
                    self.on_event(task_id, {**common, "status": "waiting_approval", "current_step": "Waiting for approval"})
                elif "waitingOnUserInput" in detail:
                    self.on_event(task_id, {**common, "status": "waiting_input", "current_step": "Waiting for user input"})

    @staticmethod
    def _item_label(item: dict, completed: bool = False) -> str:
        labels = {
            "commandExecution": "Running command", "fileChange": "Updating files",
            "mcpToolCall": "Calling MCP tool", "dynamicToolCall": "Calling tool",
            "webSearch": "Searching the web", "agentMessage": "Preparing response",
            "reasoning": "Planning", "plan": "Updating plan",
        }
        label = labels.get(str(item.get("type") or ""), "Working")
        return f"{label} complete" if completed and label not in {"Preparing response", "Planning"} else label

    @staticmethod
    def _request_label(method: str) -> str:
        return "Waiting for approval" if "approval" in method.lower() else "Waiting for user input"

    def _drain_stderr(self) -> None:
        process = self.process
        if process is not None and process.stderr is not None:
            for _line in process.stderr:
                pass
