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
- For image review or homework grading, inspect the supplied image and return the findings before offering optional edits.
- Camera photos may be sideways even when EXIF says normal; orient the content for reading before OCR or grading.
- Never claim that an image or file is being generated, edited, or returned unless an output file was actually created and is available to SignalASI.
- When the user requests a returned file, create it inside the task workspace `outputs` directory and verify that it exists before the final response.
- For requested image annotations, use local image tools or a short script, preserve readable resolution, and save the finished image under `outputs`.
- If the requested media-editing capability is unavailable, say so briefly and still return every useful textual finding.
""".strip()
CODEX_STALL_TIMEOUT_SECONDS = max(30, int(os.environ.get("SIGNALASI_CODEX_STALL_TIMEOUT_SECONDS", "180")))
CODEX_MAX_TASK_SECONDS = max(
    CODEX_STALL_TIMEOUT_SECONDS,
    int(os.environ.get("SIGNALASI_CODEX_MAX_TASK_SECONDS", "900")),
)
MAX_VISIBLE_PROGRESS_TEXT = 2_000
MAX_VISIBLE_TOOL_DETAIL = 500


class CodexConversationBusyError(RuntimeError):
    def __init__(self, active_task_id: str) -> None:
        super().__init__(f"Codex conversation already has an active task: {active_task_id}")
        self.active_task_id = active_task_id


@dataclass
class CodexRun:
    task_id: str
    conversation_id: str = ""
    thread_id: str = ""
    turn_id: str = ""
    final_text: str = ""
    last_agent_text: str = ""
    agent_message_deltas: dict[str, str] = field(default_factory=dict)
    reasoning_summary_deltas: dict[str, dict[int, str]] = field(default_factory=dict)
    pending_requests: dict[int, dict] = field(default_factory=dict)
    started_monotonic: float = field(default_factory=time.monotonic)
    last_event_monotonic: float = field(default_factory=time.monotonic)
    finished: bool = False
    prefers_chinese: bool = False


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
        image_paths: list[str] | None = None,
        fresh_thread_prompt: str = "",
    ) -> CodexRun:
        self._ensure_started()
        local_images = [
            os.path.abspath(path)
            for path in (image_paths or [])
            if str(path or "").strip() and os.path.isfile(path)
        ][:10]
        clean_conversation_id = str(conversation_id or "").strip()
        run = CodexRun(
            task_id=task_id,
            conversation_id=clean_conversation_id,
            prefers_chinese=self._contains_chinese(prompt),
        )
        reused_thread = False
        try:
            with self._lock:
                active_run = self._active_run_locked(clean_conversation_id, exclude_task_id=task_id)
                if active_run is not None:
                    raise CodexConversationBusyError(active_run.task_id)
                self._runs[task_id] = run
                conversation_key = self._conversation_key(clean_conversation_id)
                run.thread_id = self._conversation_threads.get(conversation_key, "") if conversation_key else ""
                reused_thread = bool(run.thread_id)
                if not run.thread_id:
                    run.thread_id = self._start_thread(cwd, model, clean_conversation_id)
        except Exception:
            self._discard_run(run)
            raise
        if not run.thread_id:
            self._discard_run(run)
            raise RuntimeError("Codex App Server did not return a thread id")
        self.on_event(task_id, {"status": "starting", "thread_id": run.thread_id, "current_step": "Starting Codex turn"})
        turn_prompt = prompt if reused_thread else (fresh_thread_prompt or prompt)
        try:
            try:
                response = self._start_turn(run.thread_id, turn_prompt, model, local_images)
            except RuntimeError as exc:
                if not run.thread_id or "thread not found" not in str(exc).lower():
                    raise
                if clean_conversation_id:
                    self._conversation_threads.pop(conversation_key, None)
                    self._save_conversation_threads()
                run.thread_id = self._start_thread(cwd, model, clean_conversation_id)
                self.on_event(task_id, {
                    "status": "starting", "thread_id": run.thread_id,
                    "current_step": "Starting a fresh Codex thread",
                })
                response = self._start_turn(
                    run.thread_id,
                    fresh_thread_prompt or prompt,
                    model,
                    local_images,
                )
        except Exception:
            self._discard_run(run)
            raise
        run.turn_id = str((response.get("turn") or {}).get("id") or "")
        if run.turn_id:
            self._turn_tasks[run.turn_id] = task_id
        threading.Thread(
            target=self._watch_run,
            args=(task_id,),
            daemon=True,
            name=f"codex-watch-{task_id[:8]}",
        ).start()
        return run

    def recover_task(
        self,
        task_id: str,
        thread_id: str,
        turn_id: str,
        original_prompt: str,
        conversation_id: str = "",
        elapsed_seconds: float = 0,
    ) -> CodexRun:
        """Reconnect to an existing Codex turn without replaying the prompt."""
        clean_thread_id = str(thread_id or "").strip()
        clean_turn_id = str(turn_id or "").strip()
        if not clean_thread_id or not clean_turn_id:
            raise RuntimeError(
                "Cannot recover the original Codex turn because its thread or turn identity is missing"
            )

        self._ensure_started()
        now = time.monotonic()
        run = CodexRun(
            task_id=task_id,
            conversation_id=str(conversation_id or "").strip(),
            thread_id=clean_thread_id,
            turn_id=clean_turn_id,
            started_monotonic=now - max(0.0, float(elapsed_seconds or 0)),
            last_event_monotonic=now,
            prefers_chinese=self._contains_chinese(original_prompt),
        )
        with self._lock:
            self._runs[task_id] = run
            self._turn_tasks[clean_turn_id] = task_id

        self.on_event(task_id, {
            "thread_id": clean_thread_id,
            "turn_id": clean_turn_id,
            "status": "starting",
            "current_step": "Reconnecting to Codex turn",
        })
        try:
            response = self._request("thread/resume", {
                "threadId": clean_thread_id,
                "approvalPolicy": "never",
                "sandbox": "workspace-write",
            }, timeout=30)
            if run.finished:
                return run
            thread = response.get("thread") or {}
            turns = thread.get("turns") or []
            if not turns:
                response = self._request("thread/read", {
                    "threadId": clean_thread_id,
                    "includeTurns": True,
                }, timeout=30)
                if run.finished:
                    return run
                thread = response.get("thread") or thread
                turns = thread.get("turns") or []
            turn = next((
                candidate for candidate in turns
                if str(candidate.get("id") or "") == clean_turn_id
            ), None)
            if turn is None:
                raise RuntimeError(
                    "The original Codex turn is no longer available; the task was not replayed"
                )
            run.final_text = self._latest_agent_message(turn)
            turn_status = str(turn.get("status") or "")
            if turn_status == "completed":
                if not run.final_text:
                    raise RuntimeError(
                        "The original Codex turn completed without a final response"
                    )
                run.finished = True
                self._remove_turn_mapping(run)
                self.on_event(task_id, {
                    "thread_id": clean_thread_id,
                    "turn_id": clean_turn_id,
                    "status": "completed",
                    "current_step": "",
                    "result": run.final_text,
                })
                return run
            if turn_status in {"failed", "interrupted"}:
                run.finished = True
                self._remove_turn_mapping(run)
                reason = self._turn_error(turn)
                result = (
                    "Codex \u539f\u4efb\u52a1\u5df2\u4e2d\u65ad\uff0c\u672a\u91cd\u590d\u6267\u884c\u3002\u8bf7\u91cd\u65b0\u53d1\u9001\u4efb\u52a1\u3002"
                    if run.prefers_chinese else
                    "The original Codex turn ended before completion and was not replayed. Please send the task again."
                )
                self.on_event(task_id, {
                    "thread_id": clean_thread_id,
                    "turn_id": clean_turn_id,
                    "status": "failed",
                    "current_step": "",
                    "result": result,
                    "error": reason or f"Codex turn {turn_status}",
                })
                return run
            if turn_status != "inProgress":
                raise RuntimeError(
                    f"The original Codex turn returned an unsupported status: {turn_status or 'unknown'}"
                )

            thread_status = thread.get("status") or {}
            active_flags = (
                thread_status.get("activeFlags") or []
                if isinstance(thread_status, dict) else []
            )
            status = "running"
            current_step = "Reconnected to Codex turn"
            if "waitingOnApproval" in active_flags:
                status = "waiting_approval"
                current_step = "Waiting for approval"
            elif "waitingOnUserInput" in active_flags:
                status = "waiting_input"
                current_step = "Waiting for user input"
            self.on_event(task_id, {
                "thread_id": clean_thread_id,
                "turn_id": clean_turn_id,
                "status": status,
                "current_step": current_step,
            })
            threading.Thread(
                target=self._watch_run,
                args=(task_id,),
                daemon=True,
                name=f"codex-recover-watch-{task_id[:8]}",
            ).start()
            return run
        except Exception:
            run.finished = True
            self._remove_turn_mapping(run)
            raise

    def _watch_run(self, task_id: str) -> None:
        while True:
            time.sleep(1)
            run = self._runs.get(task_id)
            if run is None or run.finished:
                return
            now = time.monotonic()
            stalled = now - run.last_event_monotonic >= CODEX_STALL_TIMEOUT_SECONDS
            exceeded = now - run.started_monotonic >= CODEX_MAX_TASK_SECONDS
            if not stalled and not exceeded:
                continue
            run.finished = True
            message = (
                "Codex \u957f\u65f6\u95f4\u6ca1\u6709\u65b0\u8fdb\u5c55\uff0c\u4efb\u52a1\u5df2\u505c\u6b62\uff0c\u907f\u514d\u7ee7\u7eed\u963b\u585e\u540e\u7eed\u8bf7\u6c42\u3002\u8bf7\u91cd\u65b0\u53d1\u9001\u4e00\u6b21\u3002"
                if run.prefers_chinese else
                "Codex made no progress for too long, so the task was stopped instead of blocking later requests. Please send it again."
            )
            self.on_event(task_id, {
                "thread_id": run.thread_id,
                "turn_id": run.turn_id,
                "status": "timed_out",
                "current_step": "",
                "result": message,
                "error": "Codex task stalled",
            })
            try:
                self.interrupt(task_id)
            except Exception:
                pass
            return

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

    def _active_run_locked(
        self,
        conversation_id: str,
        *,
        exclude_task_id: str = "",
    ) -> CodexRun | None:
        clean_conversation_id = str(conversation_id or "").strip()
        if not clean_conversation_id:
            return None
        mapped_thread_id = self._conversation_threads.get(
            self._conversation_key(clean_conversation_id),
            "",
        )
        candidates = [
            run for run in self._runs.values()
            if run.task_id != exclude_task_id and not run.finished and (
                run.conversation_id == clean_conversation_id
                or (mapped_thread_id and run.thread_id == mapped_thread_id)
            )
        ]
        return max(candidates, key=lambda item: item.started_monotonic, default=None)

    def active_task_id(self, conversation_id: str, exclude_task_id: str = "") -> str:
        with self._lock:
            run = self._active_run_locked(conversation_id, exclude_task_id=exclude_task_id)
            return run.task_id if run is not None else ""

    def wait_for_conversation_idle(
        self,
        conversation_id: str,
        timeout_seconds: float = 2.0,
    ) -> bool:
        deadline = time.monotonic() + max(0.0, float(timeout_seconds))
        while True:
            if not self.active_task_id(conversation_id):
                return True
            if time.monotonic() >= deadline:
                return False
            time.sleep(0.02)

    def steer_task(
        self,
        task_id: str,
        prompt: str,
        image_paths: list[str] | None = None,
        wait_for_turn_seconds: float = 15.0,
    ) -> CodexRun | None:
        """Add user guidance to an active turn without creating a parallel thread."""
        self._ensure_started()
        deadline = time.monotonic() + max(0.0, float(wait_for_turn_seconds))
        run: CodexRun | None = None
        while True:
            with self._lock:
                run = self._runs.get(str(task_id or "").strip())
                if run is not None and run.finished:
                    return None
                if run is not None and run.turn_id:
                    break
            if time.monotonic() >= deadline:
                return None
            time.sleep(0.02)

        local_images = [
            os.path.abspath(path)
            for path in (image_paths or [])
            if str(path or "").strip() and os.path.isfile(path)
        ][:10]
        follow_up = (
            "Apply this latest user instruction to the task already in progress. "
            "Do not treat it as a separate request:\n"
            f"{str(prompt or '').strip()}"
        )
        try:
            self._request("turn/steer", {
                "threadId": run.thread_id,
                "expectedTurnId": run.turn_id,
                "input": self._user_input(follow_up, local_images, include_task_policy=False),
            }, timeout=30)
        except RuntimeError as exc:
            if self._is_not_steerable_error(exc):
                return None
            raise
        run.last_event_monotonic = time.monotonic()
        return run

    def _start_turn(self, thread_id: str, prompt: str, model: str, image_paths: list[str] | None = None) -> dict:
        return self._request("turn/start", {
            "threadId": thread_id,
            "input": self._user_input(prompt, image_paths, include_task_policy=True),
            "model": model, "effort": "low",
        }, timeout=30)

    @staticmethod
    def _user_input(
        prompt: str,
        image_paths: list[str] | None,
        *,
        include_task_policy: bool,
    ) -> list[dict]:
        from response_policy import apply_response_policy
        styled_prompt = apply_response_policy(prompt)
        text = styled_prompt.rstrip()
        if include_task_policy:
            text = f"{text}\n\n{CODEX_TASK_POLICY}"
        user_input = [
            {"type": "text", "text": text, "text_elements": []}
        ]
        user_input.extend(
            {"type": "localImage", "path": path, "detail": "original"}
            for path in (image_paths or [])
        )
        return user_input

    @staticmethod
    def _latest_agent_message(turn: dict) -> str:
        for item in reversed(list(turn.get("items") or [])):
            if item.get("type") == "agentMessage" and str(item.get("text") or "").strip():
                return str(item["text"]).strip()
        return ""

    @staticmethod
    def _turn_error(turn: dict) -> str:
        error = turn.get("error")
        if isinstance(error, dict):
            return str(error.get("message") or error.get("code") or "").strip()
        return str(error or "").strip()

    def _remove_turn_mapping(self, run: CodexRun) -> None:
        with self._lock:
            if self._turn_tasks.get(run.turn_id) == run.task_id:
                self._turn_tasks.pop(run.turn_id, None)

    def _discard_run(self, run: CodexRun) -> None:
        run.finished = True
        with self._lock:
            if self._turn_tasks.get(run.turn_id) == run.task_id:
                self._turn_tasks.pop(run.turn_id, None)
            if self._runs.get(run.task_id) is run:
                self._runs.pop(run.task_id, None)

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
                "clientInfo": {"name": "signalasi-desktop", "title": "SignalASI Desktop", "version": "0.1.18"},
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
            task_id = next((
                key for key, run in reversed(list(self._runs.items()))
                if run.thread_id == thread_id and not run.finished
            ), "")
        if not task_id:
            return
        run = self._runs[task_id]
        run.last_event_monotonic = time.monotonic()
        common = {"thread_id": run.thread_id, "turn_id": turn_id or run.turn_id}
        if "id" in message:
            run.pending_requests[int(message["id"])] = message
            self.on_event(task_id, {**common, "status": "waiting_approval", "current_step": self._request_label(method)})
        elif method == "turn/started":
            if turn_id:
                run.turn_id = turn_id
                self._turn_tasks[turn_id] = task_id
            self.on_event(task_id, {**common, "turn_id": run.turn_id, "status": "running", "current_step": "Codex is working"})
        elif method == "item/agentMessage/delta":
            item_id = str(params.get("itemId") or "")
            if item_id:
                run.agent_message_deltas[item_id] = (
                    run.agent_message_deltas.get(item_id, "") + str(params.get("delta") or "")
                )[:MAX_VISIBLE_PROGRESS_TEXT]
        elif method == "item/reasoning/summaryTextDelta":
            item_id = str(params.get("itemId") or "")
            if item_id:
                summary_index = max(0, int(params.get("summaryIndex") or 0))
                summaries = run.reasoning_summary_deltas.setdefault(item_id, {})
                summaries[summary_index] = (
                    summaries.get(summary_index, "") + str(params.get("delta") or "")
                )[:MAX_VISIBLE_PROGRESS_TEXT]
        elif method == "item/started":
            self._emit_item_progress(
                task_id,
                common,
                params.get("item") or {},
                completed=False,
            )
        elif method == "item/completed":
            item = params.get("item") or {}
            item_type = str(item.get("type") or "")
            item_id = str(item.get("id") or params.get("itemId") or "")
            if item_type == "agentMessage":
                text = self._clean_visible_text(
                    item.get("text") or run.agent_message_deltas.pop(item_id, "")
                )
                phase = str(item.get("phase") or "")
                if text:
                    run.last_agent_text = text
                    if phase == "commentary":
                        self._emit_progress(
                            task_id,
                            common,
                            self._narration_progress(item_id, text, "commentary"),
                        )
                    else:
                        run.final_text = text
            elif item_type == "reasoning":
                summary = self._reasoning_summary(run, item_id, item)
                if summary:
                    self._emit_progress(
                        task_id,
                        common,
                        self._narration_progress(item_id, summary, "reasoning_summary"),
                    )
                legacy_event = self._item_event(item, "completed")
                if legacy_event:
                    self.on_event(task_id, {
                        **common,
                        "status": "running",
                        "current_step": self._item_label(item, completed=True),
                        **legacy_event,
                    })
            elif item_type == "plan":
                text = self._clean_visible_text(item.get("text"))
                if text:
                    self._emit_progress(
                        task_id,
                        common,
                        self._narration_progress(item_id, text, "plan"),
                    )
                legacy_event = self._item_event(item, "completed")
                if legacy_event:
                    self.on_event(task_id, {
                        **common,
                        "status": "running",
                        "current_step": self._item_label(item, completed=True),
                        **legacy_event,
                    })
            else:
                self._emit_item_progress(task_id, common, item, completed=True)
        elif method == "turn/completed":
            status = str((params.get("turn") or {}).get("status") or "completed")
            mapped = {"completed": "completed", "failed": "failed", "interrupted": "cancelled"}.get(status, status)
            if not run.final_text:
                run.final_text = run.last_agent_text
            run.finished = True
            run.agent_message_deltas.clear()
            run.reasoning_summary_deltas.clear()
            if turn_id:
                self._turn_tasks.pop(turn_id, None)
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

    def _emit_progress(self, task_id: str, common: dict, progress: dict) -> None:
        self.on_event(task_id, {
            **common,
            "status": "running",
            "current_step": str(progress.get("title") or "Codex is working"),
            "progress_event": progress,
        })

    def _emit_item_progress(
        self,
        task_id: str,
        common: dict,
        item: dict,
        *,
        completed: bool,
    ) -> None:
        progress = self._tool_progress_event(item, completed=completed)
        legacy_event = self._item_event(
            item,
            "completed" if completed else "running",
        )
        if not progress and not legacy_event:
            return
        self.on_event(task_id, {
            **common,
            "status": "running",
            "current_step": (
                str(progress.get("title") or "")
                if progress
                else self._item_label(item, completed=completed)
            ),
            **({"progress_event": progress} if progress else {}),
            **legacy_event,
        })

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

    @classmethod
    def _item_event(cls, item: dict, status: str) -> dict:
        item_type = str(item.get("type") or "").strip()
        if not item_type or item_type == "agentMessage":
            return {}
        kind = {
            "reasoning": "reasoning",
            "plan": "plan",
            "commandExecution": "command",
            "fileChange": "file",
            "mcpToolCall": "mcp",
            "dynamicToolCall": "tool",
            "webSearch": "network",
        }.get(item_type, "tool")
        detail = cls._item_detail(item, item_type)
        item_id = str(item.get("id") or "").strip()
        return {
            "event_id": f"codex:{item_id}" if item_id else "",
            "event_kind": kind,
            "event_title": cls._item_label(item),
            "event_status": status,
            "event_detail": detail,
            "event_metadata": {"provider": "codex", "item_type": item_type},
        }

    @staticmethod
    def _item_detail(item: dict, item_type: str) -> str:
        if item_type == "commandExecution":
            command = item.get("command") or item.get("cmd") or ""
            if isinstance(command, list):
                command = " ".join(str(value) for value in command)
            return str(command or "").strip()[:1_000]
        if item_type in {"mcpToolCall", "dynamicToolCall"}:
            server = str(item.get("server") or item.get("serverName") or "").strip()
            tool = str(item.get("tool") or item.get("toolName") or item.get("name") or "").strip()
            return " / ".join(value for value in (server, tool) if value)[:1_000]
        if item_type == "webSearch":
            return str(item.get("query") or "").strip()[:1_000]
        if item_type == "fileChange":
            changes = item.get("changes") or []
            if isinstance(changes, list):
                paths = [
                    str(value.get("path") or value.get("file") or "").strip()
                    for value in changes
                    if isinstance(value, dict)
                ]
                return ", ".join(value for value in paths if value)[:1_000]
        # Reasoning internals are deliberately not forwarded.
        return ""

    @classmethod
    def _narration_progress(cls, item_id: str, text: str, code: str) -> dict:
        clean = cls._clean_visible_text(text)
        return {
            "event_id": cls._progress_event_id(item_id, code),
            "kind": "narration",
            "code": code,
            "title": clean.splitlines()[0][:240],
            "status": "completed",
            "detail": clean,
            "metadata": {"source": "codex_app_server"},
        }

    @classmethod
    def _tool_progress_event(cls, item: dict, completed: bool) -> dict | None:
        item_type = str(item.get("type") or "")
        item_id = str(item.get("id") or "")
        if not item_id:
            return None
        status = "completed" if completed else "running"
        raw_status = str(item.get("status") or "").lower()
        if completed and ("fail" in raw_status or raw_status == "declined"):
            status = "failed"

        code = ""
        started_title = ""
        completed_title = ""
        detail = ""
        metadata: dict[str, object] = {"source": "codex_app_server", "item_type": item_type}
        if item_type == "commandExecution":
            code, started_title, completed_title = "command", "Running command", "Ran command"
            detail = cls._clean_visible_text(item.get("command"))
        elif item_type == "fileChange":
            code, started_title, completed_title = "file_change", "Updating files", "Updated files"
            metadata["count"] = len(item.get("changes") or [])
        elif item_type == "mcpToolCall":
            code, started_title, completed_title = "mcp_tool", "Calling MCP tool", "Called MCP tool"
            detail = ".".join(filter(None, (
                str(item.get("server") or "").strip(),
                str(item.get("tool") or "").strip(),
            )))
        elif item_type == "dynamicToolCall":
            code, started_title, completed_title = "dynamic_tool", "Calling tool", "Called tool"
            detail = ".".join(filter(None, (
                str(item.get("namespace") or "").strip(),
                str(item.get("tool") or "").strip(),
            )))
        elif item_type == "webSearch":
            code, started_title, completed_title = "web_search", "Searching the web", "Searched the web"
            action = item.get("action") if isinstance(item.get("action"), dict) else {}
            queries = action.get("queries") if isinstance(action.get("queries"), list) else []
            detail = cls._clean_visible_text(
                item.get("query") or action.get("query") or (queries[0] if queries else "")
            )
        elif item_type == "imageView":
            code, started_title, completed_title = "image_view", "Viewing image", "Viewed image"
            metadata["count"] = 1
        elif item_type == "imageGeneration":
            code, started_title, completed_title = "image_generation", "Generating image", "Generated image"
        elif item_type in {"collabAgentToolCall", "subAgentActivity"}:
            code, started_title, completed_title = (
                "agent_collaboration",
                "Coordinating Agents",
                "Coordinated Agents",
            )
            detail = cls._clean_visible_text(item.get("tool") or item.get("kind"))
        elif item_type == "contextCompaction":
            code, started_title, completed_title = (
                "context_compaction",
                "Compacting context",
                "Compacted context",
            )
        else:
            return None

        title = completed_title if completed else started_title
        if status == "failed":
            title = f"{completed_title} with an error"
        metadata["code"] = code
        return {
            "event_id": cls._progress_event_id(item_id, code),
            "kind": "tool",
            "code": code,
            "title": title,
            "status": status,
            "detail": detail[:MAX_VISIBLE_TOOL_DETAIL],
            "metadata": metadata,
        }

    @classmethod
    def _reasoning_summary(cls, run: CodexRun, item_id: str, item: dict) -> str:
        raw_summary = item.get("summary") if isinstance(item.get("summary"), list) else []
        summaries = [cls._clean_visible_text(value) for value in raw_summary]
        summaries = [value for value in summaries if value]
        if not summaries:
            buffered = run.reasoning_summary_deltas.get(item_id, {})
            summaries = [
                cls._clean_visible_text(buffered[index])
                for index in sorted(buffered)
                if cls._clean_visible_text(buffered[index])
            ]
        run.reasoning_summary_deltas.pop(item_id, None)
        return "\n\n".join(summaries)[:MAX_VISIBLE_PROGRESS_TEXT]

    @staticmethod
    def _progress_event_id(item_id: str, code: str) -> str:
        clean_item = str(item_id or "").strip()[:160]
        return f"codex:{code}:{clean_item}" if clean_item else f"codex:{code}"

    @staticmethod
    def _clean_visible_text(value: object) -> str:
        text = str(value or "").replace("\x00", "").strip()
        return text[:MAX_VISIBLE_PROGRESS_TEXT]

    @staticmethod
    def _request_label(method: str) -> str:
        return "Waiting for approval" if "approval" in method.lower() else "Waiting for user input"

    @staticmethod
    def _contains_chinese(value: str) -> bool:
        return any("\u4e00" <= character <= "\u9fff" for character in str(value or ""))

    @staticmethod
    def _is_not_steerable_error(error: Exception) -> bool:
        normalized = str(error or "").lower()
        return any(marker in normalized for marker in (
            "expectedturnid",
            "expected turn",
            "no active turn",
            "not active",
            "not in progress",
            "turn not found",
            "thread is idle",
        ))

    def _drain_stderr(self) -> None:
        process = self.process
        if process is not None and process.stderr is not None:
            for _line in process.stderr:
                pass
