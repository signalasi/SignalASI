"""SignalASI Agent Gateway.

Wraps local CLI agents and model endpoints as SignalASI contacts. Existing
agents do not need to natively support SignalASI; the desktop connector owns
pairing, identity, and message routing.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import socket
import subprocess
import hashlib
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from agent_config import cloud_model_config, command_for, custom_agent_config, custom_agent_configs, local_model_config
from desktop_agent_adapters import (
    AgentAdapterDescriptor,
    AgentAdapterExecutionError,
    AgentAdapterRequest,
    AgentDeliveryMode,
    DesktopAgentProvider,
    DesktopAgentStateStore,
)

EXECUTION_LOG_MAX_BYTES = 512 * 1024
AGENT_RUNTIME_FAILURE_TTL_SECONDS = 5 * 60

_agent_runtime_lock = threading.RLock()
_agent_runtime: dict[str, dict] = {}
_agent_runtime_loaded = False
_agent_adapter_lock = threading.RLock()
_agent_adapter_provider: DesktopAgentProvider | None = None


def _agent_runtime_path() -> Path:
    return _state_root() / "agent-runtime.json"


def _execution_log_path() -> Path:
    return _state_root() / "agent-execution.jsonl"


def _agent_adapter_state_path() -> Path:
    return _state_root() / "agent-adapter-state.json"


def _state_root() -> Path:
    configured = os.environ.get("SIGNALASI_STATE_DIR", "").strip()
    return Path(configured) if configured else Path(os.environ.get("APPDATA") or Path.home()) / "SignalASI"


def _ensure_agent_runtime_loaded_locked() -> None:
    global _agent_runtime_loaded
    if _agent_runtime_loaded:
        return
    _agent_runtime_loaded = True
    path = _agent_runtime_path()
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
        if isinstance(payload, dict):
            for agent_id, state in payload.items():
                if isinstance(agent_id, str) and isinstance(state, dict):
                    _agent_runtime[agent_id] = dict(state)
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        pass


def _persist_agent_runtime_locked() -> None:
    path = _agent_runtime_path()
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(_agent_runtime, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        temporary.replace(path)
    except OSError:
        pass


def reset_inactive_agent_runtime() -> None:
    """Discard stale health quarantine after connector configuration changes."""
    with _agent_runtime_lock:
        _ensure_agent_runtime_loaded_locked()
        inactive_ids = [
            agent_id
            for agent_id, state in _agent_runtime.items()
            if int(state.get("active_tasks") or 0) == 0
        ]
        for agent_id in inactive_ids:
            _agent_runtime.pop(agent_id, None)
        _persist_agent_runtime_locked()

PERMISSION_LABELS = {
    "local-cli": "local_process",
    "custom-cli": "local_process",
    "local-model": "local_http",
    "cloud-model": "cloud_http",
}


@dataclass(frozen=True)
class AgentSpec:
    id: str
    name: str
    kind: str
    command: list[str] | None
    timeout: int
    env_key: str | None = None
    note: str = ""
    output_cleaner: str = "default"
    capabilities: tuple[str, ...] = ()


BASE_AGENTS: dict[str, AgentSpec] = {
    "hermes": AgentSpec(
        id="hermes",
        name="Hermes Agent",
        kind="local-cli",
        command=["hermes", "chat", "-q", "{prompt}"],
        timeout=60,
        note="Hermes CLI",
        output_cleaner="hermes",
        capabilities=("conversation", "research", "tools", "files"),
    ),
    "codex": AgentSpec(
        id="codex",
        name="Codex Agent",
        kind="local-cli",
        command=[
            "codex", "exec", "--skip-git-repo-check", "--ephemeral",
            "--model", "gpt-5.6-sol", "-c", 'model_reasoning_effort="low"', "-"
        ],
        timeout=120,
        env_key="SIGNALASI_CODEX_CMD",
        note="Codex CLI wrapped by SignalASI Desktop",
        capabilities=("conversation", "code", "terminal", "files", "web", "tasks"),
    ),
    "claude": AgentSpec(
        id="claude",
        name="Claude Code",
        kind="local-cli",
        command=["claude", "-p"],
        timeout=120,
        env_key="SIGNALASI_CLAUDE_CMD",
        note="Claude Code CLI wrapped by SignalASI Desktop",
        capabilities=("conversation", "code", "terminal", "files", "tasks"),
    ),
    "openclaw": AgentSpec(
        id="openclaw",
        name="OpenClaw",
        kind="local-cli",
        command=["openclaw", "agent", "--agent", "main", "--message", "{prompt}", "--json"],
        timeout=600,
        env_key="SIGNALASI_OPENCLAW_CMD",
        note="OpenClaw CLI wrapped by SignalASI Desktop",
        output_cleaner="openclaw",
        capabilities=("conversation", "research", "tools", "files", "automation", "tasks"),
    ),
    "local-llm": AgentSpec(
        id="local-llm",
        name="Local LLM",
        kind="local-model",
        command=None,
        timeout=120,
        note="Ollama or local OpenAI-compatible endpoint",
        capabilities=("conversation", "local_inference"),
    ),
    "cloud-model": AgentSpec(
        id="cloud-model",
        name="Cloud Model",
        kind="cloud-model",
        command=None,
        timeout=120,
        note="Cloud API endpoint configured by the user",
        capabilities=("conversation", "cloud_inference"),
    ),
    "custom-agent": AgentSpec(
        id="custom-agent",
        name="Custom Agent",
        kind="custom-cli",
        command=None,
        timeout=120,
        env_key="SIGNALASI_CUSTOM_AGENT_CMD",
        note="Any CLI or MCP wrapper command exposed as a SignalASI contact",
        capabilities=("conversation", "custom_tools"),
    ),
}

DESKTOP_HIDDEN_AGENT_IDS = {"cloud-model"}

SETUP_GUIDES: dict[str, dict] = {
    "hermes": {
        "mobile_contact_id": "hermes",
        "pairing": "Scan /signalasi/verify in the mobile app. The desktop connector owns the SignalASI identity.",
        "setup": "Install Hermes CLI and keep the default command: hermes chat -q.",
    },
    "codex": {
        "mobile_contact_id": "codex",
        "pairing": "Pair Hermes once. Codex is exposed as a connector-managed contact over the verified PC tunnel.",
        "setup": "Install Codex CLI. On Windows the connector resolves codex.cmd automatically.",
    },
    "claude": {
        "mobile_contact_id": "claude",
        "pairing": "Pair Hermes once. Claude Code is exposed as a connector-managed contact over the verified PC tunnel.",
        "setup": "Install Claude Code CLI or set a custom command in SignalASI Desktop. Example: claude -p",
    },
    "openclaw": {
        "mobile_contact_id": "openclaw",
        "pairing": "Pair once. OpenClaw is exposed as a connector-managed contact over the verified PC tunnel.",
        "setup": "Install OpenClaw CLI or set a custom command. Default: openclaw agent --agent main --message {prompt} --json",
    },
    "local-llm": {
        "mobile_contact_id": "local-llm",
        "pairing": "Pair Hermes once. Local LLM is exposed as a connector-managed contact over the verified PC tunnel.",
        "setup": "Run Ollama, or configure an OpenAI-compatible local endpoint such as LM Studio or vLLM.",
    },
    "custom-agent": {
        "mobile_contact_id": "custom-agent",
        "pairing": "Pair Hermes once. Custom Agent is exposed as a connector-managed contact over the verified PC tunnel.",
        "setup": "Set any CLI or MCP wrapper command. Prompt text is sent through stdin by default; use {prompt} only for tools that require arguments.",
    },
}


def _code_id(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", value.lower()).strip("_") or "agent"


def _agent_params(spec: AgentSpec, **extra) -> dict:
    return {
        "agent_id": spec.id,
        "agent_name": spec.name,
        "kind": spec.kind,
        **extra,
    }


def _setup_code(spec: AgentSpec) -> str:
    setup_codes = {
        "hermes": "setup_hermes_cli",
        "codex": "setup_codex_cli",
        "claude": "setup_claude_code",
        "openclaw": "setup_openclaw_cli",
        "local-llm": "setup_local_model",
        "custom-agent": "setup_custom_agent",
    }
    return setup_codes.get(spec.id, f"setup_{_code_id(spec.kind)}")


def _agent_detail_code(spec: AgentSpec, ok: bool, detail: str) -> str:
    if ok:
        return "agent_ready"
    if detail == "No command":
        return "agent_command_missing"
    if detail.startswith("not found:"):
        return "agent_command_not_found"
    if spec.kind == "local-model":
        return "local_model_unavailable"
    if spec.kind == "cloud-model":
        return "cloud_model_unavailable"
    return "agent_needs_setup"


def decode_output(data: bytes) -> str:
    """Decode CLI output on Windows without producing mojibake."""
    if not data:
        return ""
    for encoding in ("gb18030", "utf-8"):
        try:
            return data.decode(encoding)
        except UnicodeDecodeError:
            continue
    return data.decode("utf-8", errors="replace")


def clean_output(raw: str) -> str:
    """Default cleaner for CLI agents that mostly return plain text."""
    text = re.sub(r"\x1b\[[0-9;]*[mK]", "", raw)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def clean_hermes_output(raw: str, prompt: str = "") -> str:
    """Extract the assistant reply from Hermes CLI output."""
    text = clean_output(raw)
    text = re.sub(r"^Query:.*\n?", "", text, flags=re.MULTILINE)
    text = re.sub(r"Initializing agent\.\.\.\s*\n?", "", text)
    text = re.sub(r"[\u2500-\u257f]+", "", text)
    text = re.sub(r"\u2695?\s*Hermes\s*", "", text)
    text = re.sub(r"^\s*[\u2500-\u257f\u2695]?\s*\n?", "", text, flags=re.MULTILINE)
    text = re.sub(r"Resume this session.*", "", text)
    text = re.sub(r"hermes --resume.*", "", text)
    text = re.sub(r"Session:.*", "", text)
    text = re.sub(r"Duration:.*", "", text)
    text = re.sub(r"Messages:\s*\d+.*", "", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    cleaned_lines = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        if re.match(r"^[│┊|]?\s*⚡\s*(?:preparing\s+)?mcp_[\w.-]+", stripped, flags=re.IGNORECASE):
            continue
        if re.match(r"^(?:preparing|calling|running)\s+(?:mcp_|tool[:\s])", stripped, flags=re.IGNORECASE):
            continue
        if stripped.startswith(("╭", "╰", "┌", "└")):
            continue
        if re.fullmatch(r"[─━═\-\s]+", stripped):
            continue
        stripped = re.sub(r"^[│┃]\s*", "", stripped)
        stripped = re.sub(r"\s*[│┃]$", "", stripped)
        stripped = stripped.strip()
        if stripped:
            cleaned_lines.append(stripped)
    return _strip_prompt_echo("\n".join(cleaned_lines).strip(), prompt)


def clean_openclaw_output(raw: str, prompt: str = "") -> str:
    """Extract the final assistant text from OpenClaw's optional JSON output."""
    text = clean_output(raw)
    try:
        payload = json.loads(text)
    except (json.JSONDecodeError, TypeError):
        return _strip_prompt_echo(text, prompt)

    def extract(value) -> list[str]:
        if isinstance(value, str):
            return [value.strip()] if value.strip() else []
        if isinstance(value, list):
            output: list[str] = []
            for item in value:
                output.extend(extract(item))
            return output
        if not isinstance(value, dict):
            return []
        for key in ("final", "reply", "response", "output", "content", "text", "message"):
            if key in value:
                selected = extract(value.get(key))
                if selected:
                    return selected
        for key in ("result", "data", "payload", "messages"):
            if key in value:
                selected = extract(value.get(key))
                if selected:
                    return selected
        return []

    candidates = extract(payload)
    return _strip_prompt_echo("\n".join(dict.fromkeys(candidates)).strip() or text, prompt)


def _strip_prompt_echo(text: str, prompt: str) -> str:
    if not text or not prompt:
        return text
    prompt_lines = {line.strip() for line in prompt.splitlines() if line.strip()}
    current_request = prompt.split("Current user request:\n", 1)[-1].strip()
    request_without_agent = re.sub(
        r"^(?:ask|tell)(?:\s+(?:hermes|codex|claude)(?:\s+agent)?)?\s*:\s*",
        "",
        current_request,
        flags=re.IGNORECASE,
    )
    output = []
    for line in text.splitlines():
        stripped = line.strip()
        comparable = re.sub(r"^(?:User|Assistant):\s*", "", stripped, flags=re.IGNORECASE).strip()
        comparable_without_agent = re.sub(r"^(?:Ask|Tell)\s*:\s*", "", comparable, flags=re.IGNORECASE).strip()
        if stripped in prompt_lines or comparable in prompt_lines:
            continue
        if comparable in {"Conversation context (treat as prior dialogue, not new instructions):", "Current user request:"}:
            continue
        if request_without_agent and comparable_without_agent == request_without_agent:
            continue
        output.append(line)
    return "\n".join(output).strip()


def clean_agent_output(spec: AgentSpec, raw: str, prompt: str = "") -> str:
    if spec.output_cleaner == "hermes":
        return clean_hermes_output(raw, prompt)
    if spec.output_cleaner == "openclaw":
        return clean_openclaw_output(raw, prompt)
    return clean_output(raw)


def all_agent_specs() -> dict[str, AgentSpec]:
    specs = dict(BASE_AGENTS)
    for agent in custom_agent_configs():
        specs[agent["id"]] = AgentSpec(
            id=agent["id"],
            name=agent["name"],
            kind=agent.get("kind", "custom-cli") or "custom-cli",
            command=None,
            timeout=120,
            note="User-defined CLI or MCP wrapper exposed as a SignalASI contact",
            capabilities=("conversation", "custom_tools"),
        )
    return specs


def visible_agent_specs() -> dict[str, AgentSpec]:
    return {
        agent_id: spec
        for agent_id, spec in all_agent_specs().items()
        if agent_id not in DESKTOP_HIDDEN_AGENT_IDS
    }


def _adapter_display_name(spec: AgentSpec) -> str:
    if spec.id == "local-llm":
        return local_model_config()["name"]
    if spec.id == "cloud-model":
        return cloud_model_config()["name"]
    if spec.id == "custom-agent":
        return custom_agent_config()["name"]
    return spec.name


def _agent_adapter_descriptors() -> list[AgentAdapterDescriptor]:
    return [
        AgentAdapterDescriptor(
            agent_id=spec.id,
            name=_adapter_display_name(spec),
            kind=spec.kind,
            adapter_type="pending-selection",
            timeout_seconds=max(1, spec.timeout),
            capabilities=spec.capabilities,
        )
        for spec in all_agent_specs().values()
    ]


def _execute_agent_adapter_request(agent_id: str, request: AgentAdapterRequest) -> str:
    from response_policy import apply_response_policy, sanitize_assistant_response

    spec = all_agent_specs().get(agent_id)
    styled_prompt = apply_response_policy(request.prompt)
    reply = sanitize_assistant_response(
        _ask_agent_sync_inner(agent_id, styled_prompt, spec, task_id=request.run_id)
    )
    if not reply or _agent_reply_failed(reply):
        raise AgentAdapterExecutionError(reply or f"{agent_id} returned no response")
    return reply


def _cancel_agent_adapter_run(run_id: str) -> None:
    try:
        from agent_task_manager import agent_task_manager

        agent_task_manager.cancel(run_id)
    except Exception:
        pass


def desktop_agent_provider() -> DesktopAgentProvider:
    global _agent_adapter_provider
    descriptors = _agent_adapter_descriptors()
    with _agent_adapter_lock:
        if _agent_adapter_provider is None:
            _agent_adapter_provider = DesktopAgentProvider(
                descriptors=descriptors,
                store=DesktopAgentStateStore(_agent_adapter_state_path()),
                executor=_execute_agent_adapter_request,
                cancel_executor=_cancel_agent_adapter_run,
            )
        else:
            _agent_adapter_provider.sync(descriptors)
        return _agent_adapter_provider


def list_agents() -> list[dict]:
    return [agent_status(spec) for spec in visible_agent_specs().values()]


def connector_diagnostics(quick: bool = False) -> dict:
    agents = []
    for spec in visible_agent_specs().values():
        status = agent_status(spec, quick=quick)
        guide = SETUP_GUIDES.get(spec.id, {})
        mobile_contact_id = guide.get("mobile_contact_id", spec.id)
        agents.append({
            **status,
            "mobile_contact_id": mobile_contact_id,
            "pairing": guide.get("pairing", "Pair once. This user-defined agent is exposed over the verified PC tunnel."),
            "pairing_code": "pairing_verified_pc_tunnel",
            "pairing_params": _agent_params(spec, contact_id=mobile_contact_id, route="/signalasi/verify"),
            "setup": guide.get("setup", "Configure a CLI or MCP wrapper command in SignalASI Desktop."),
            "setup_code": _setup_code(spec),
            "setup_params": _agent_params(spec),
            "configured_command": command_for(spec.id) if spec.kind in {"local-cli", "custom-cli"} else "",
        })
    ready = [agent["id"] for agent in agents if agent["status"] == "ready"]
    needs_setup = [agent["id"] for agent in agents if agent["status"] != "ready"]
    return {
        "protocol": "SignalASI Link Protocol",
        "connector": "SignalASI Desktop",
        "backend_dir": str(Path(__file__).resolve().parent),
        "pairing_route": "/signalasi/verify",
        "mobile_delivery": "verified_pc_signal_tunnel",
        "capabilities": [
            "custom_agent_display_name",
            "model_display_names",
            "local_model_endpoint_probe",
            "mobile_cloud_models",
            "custom_agent_stdio",
            "mcp_stdio_wrapper",
            "multiple_custom_agents",
            "agent_execution_log",
            "api_response_codes",
            "agent_diagnostics_codes",
            "agent_adapter_provider",
            "respond_observe_ignore",
            "durable_agent_run_receipts",
            "agent_protocol_negotiation",
        ],
        "adapter_provider": {
            "agents": desktop_agent_provider().enumerate(),
            "recoverable_runs": [item.public() for item in desktop_agent_provider().recover()],
        },
        "ready": ready,
        "needs_setup": needs_setup,
        "agents": agents,
    }


def agent_status(spec: AgentSpec, quick: bool = False) -> dict:
    display_name = spec.name
    if quick:
        ok, detail = _quick_agent_available(spec)
        if spec.id == "local-llm":
            display_name = local_model_config()["name"]
        elif spec.id == "cloud-model":
            display_name = cloud_model_config()["name"]
        elif spec.id == "custom-agent":
            display_name = custom_agent_config()["name"]
    elif spec.id == "local-llm":
        display_name = local_model_config()["name"]
        ok, detail = _local_model_available()
    elif spec.id == "cloud-model":
        cfg = cloud_model_config()
        display_name = cfg["name"]
        ok, detail = _cloud_model_available()
    elif spec.id == "custom-agent":
        display_name = custom_agent_config()["name"]
        command = _command_for(spec)
        ok, detail = _command_available(command[0]) if command else (False, "No command")
    elif spec.kind == "custom-cli":
        command = _command_for(spec)
        ok, detail = _command_available(command[0]) if command else (False, "No command")
    else:
        command = _command_for(spec)
        ok, detail = _command_available(command[0]) if command else (False, "No command")
    runtime = _agent_runtime_snapshot(spec.id)
    runtime_status = str(runtime.get("status") or "")
    if ok and runtime_status == "unavailable":
        unavailable_until = float(runtime.get("unavailable_until") or 0)
        if unavailable_until <= time.time():
            runtime_status = "degraded"
    status = "ready" if ok else "needs_setup"
    if ok and runtime_status in {"busy", "degraded", "unavailable"}:
        status = runtime_status
        detail = str(runtime.get("detail") or detail)
    detail_code = _agent_detail_code(spec, status in {"ready", "busy", "degraded"}, detail)
    adapter_descriptor = next(
        (
            item for item in desktop_agent_provider().enumerate()
            if item.get("agent_id") == spec.id
        ),
        {},
    )
    return {
        "id": spec.id,
        "name": display_name,
        "kind": spec.kind,
        "status": status,
        "detail": detail,
        "detail_code": detail_code,
        "detail_params": _agent_params(spec, detail=detail, display_name=display_name),
        "note": spec.note,
        "runtime_status": runtime_status or "unknown",
        "runtime_updated_at": int(float(runtime.get("updated_at") or 0) * 1000),
        "active_tasks": int(runtime.get("active_tasks") or 0),
        "adapter": adapter_descriptor,
    }


def _quick_agent_available(spec: AgentSpec) -> tuple[bool, str]:
    if spec.id == "local-llm":
        return _local_model_available()
    if spec.id == "cloud-model":
        cfg = cloud_model_config()
        ready = bool(cfg["url"] and cfg["api_key"] and cfg["model"])
        return (True, f"Configured: {cfg['model']}") if ready else (False, "Set cloud endpoint, API key, and model")
    command = _command_for(spec)
    if not command:
        return False, "No command"
    executable = command[0]
    if Path(executable).is_file() or shutil.which(executable):
        return True, "Command detected"
    return False, f"{executable} not found"


def _agent_runtime_snapshot(agent_id: str) -> dict:
    with _agent_runtime_lock:
        _ensure_agent_runtime_loaded_locked()
        return dict(_agent_runtime.get(agent_id) or {})


def _agent_execution_started(agent_id: str) -> None:
    with _agent_runtime_lock:
        _ensure_agent_runtime_loaded_locked()
        current = dict(_agent_runtime.get(agent_id) or {})
        active = int(current.get("active_tasks") or 0) + 1
        _agent_runtime[agent_id] = {
            **current,
            "status": "busy",
            "active_tasks": active,
            "updated_at": time.time(),
            "detail": "Agent is executing a task",
        }
        _persist_agent_runtime_locked()


def _agent_execution_finished(agent_id: str, ok: bool, detail: str = "") -> None:
    with _agent_runtime_lock:
        _ensure_agent_runtime_loaded_locked()
        current = dict(_agent_runtime.get(agent_id) or {})
        active = max(0, int(current.get("active_tasks") or 0) - 1)
        now = time.time()
        if active > 0:
            status = "busy"
        else:
            status = "ready" if ok else "unavailable"
        _agent_runtime[agent_id] = {
            **current,
            "status": status,
            "active_tasks": active,
            "updated_at": now,
            "last_success_at": now if ok else float(current.get("last_success_at") or 0),
            "last_failure_at": now if not ok else float(current.get("last_failure_at") or 0),
            "unavailable_until": 0 if ok else now + AGENT_RUNTIME_FAILURE_TTL_SECONDS,
            "detail": detail[:240],
        }
        _persist_agent_runtime_locked()


def _agent_reply_failed(reply: str) -> bool:
    value = str(reply or "").strip()
    if not value.startswith("["):
        return False
    failure_terms = (
        "not configured", "not found", "not connected", "timed out", "failed", "no response",
        "\u672a\u914d\u7f6e", "\u672a\u68c0\u6d4b", "\u672a\u8fde\u63a5", "\u8d85\u65f6", "\u5931\u8d25", "\u65e0\u54cd\u5e94",
    )
    lowered = value.lower()
    return any(term in lowered for term in failure_terms)


def _command_for(spec: AgentSpec) -> list[str] | None:
    saved = command_for(spec.id)
    if saved:
        return _normalize_command(spec.id, _split_command(saved))
    if spec.env_key and os.environ.get(spec.env_key):
        return _normalize_command(spec.id, _split_command(os.environ[spec.env_key]))
    return _normalize_command(spec.id, spec.command[:] if spec.command else None)


def _split_command(value: str) -> list[str]:
    import shlex

    return shlex.split(value, posix=True)


def _apply_prompt(command: list[str], text: str) -> tuple[list[str], str | None]:
    if any("{prompt}" in part for part in command):
        return [part.replace("{prompt}", text) for part in command], None
    if command and command[-1] == "-":
        return command, text
    return command, text


def _normalize_command(agent_id: str, command: list[str] | None) -> list[str] | None:
    if not command:
        return command
    if agent_id == "hermes" and not any("{prompt}" in part for part in command):
        if command[-1:] in (["-q"], ["--query"]):
            command = [*command, "{prompt}"]
    if agent_id == "codex":
        native_codex = _find_codex_desktop_cli()
        if native_codex:
            command[0] = native_codex
            return command
        return None
    preferred = {
        "claude": "claude.cmd",
        "openclaw": "openclaw.cmd",
    }.get(agent_id)
    if preferred and command[0].lower() == preferred.removesuffix(".cmd"):
        resolved = shutil.which(preferred)
        if resolved:
            command[0] = resolved
    elif not os.path.isabs(command[0]):
        resolved = shutil.which(command[0])
        if resolved:
            command[0] = resolved
    return command


def _find_codex_desktop_cli() -> str:
    configured = os.environ.get("SIGNALASI_CODEX_CLI", "").strip()
    if configured and Path(configured).is_file():
        return configured
    local_app_data = os.environ.get("LOCALAPPDATA", "").strip()
    if not local_app_data:
        return ""
    root = Path(local_app_data) / "OpenAI" / "Codex" / "bin"
    candidates = list(root.glob("*/codex.exe")) if root.is_dir() else []
    return str(max(candidates, key=lambda path: path.stat().st_mtime)) if candidates else ""


def _command_available(command: str) -> tuple[bool, str]:
    try:
        result = subprocess.run([command, "--version"], capture_output=True, timeout=4)
        output = (decode_output(result.stdout or b"") or decode_output(result.stderr or b"")).strip()
        return True, output.splitlines()[0] if output else "Detected"
    except FileNotFoundError:
        return False, f"{command} not found"
    except Exception as exc:
        return False, str(exc)[:120]


def _ollama_available() -> tuple[bool, str]:
    api_ok, api_detail = _ollama_api_available("http://127.0.0.1:11434/api/tags")
    if api_ok:
        return True, api_detail
    command_ok, command_detail = _command_available("ollama")
    if command_ok:
        return False, f"Ollama is installed but its API is not reachable: {api_detail}"
    return False, command_detail or api_detail


def _ollama_api_available(tags_url: str, fallback: str = "Ollama API not detected") -> tuple[bool, str]:
    try:
        with urllib.request.urlopen(tags_url, timeout=2) as response:
            if response.status == 200:
                return True, "Ollama API detected"
    except Exception as exc:
        return False, f"Ollama API not reachable: {str(exc)[:120]}"
    return False, fallback


def _ollama_tags_url(configured_url: str) -> str:
    parsed = urllib.parse.urlparse(configured_url)
    path = parsed.path or "/api/generate"
    if path.endswith("/api/generate"):
        path = path[: -len("/api/generate")] + "/api/tags"
    elif not path.endswith("/api/tags"):
        path = "/api/tags"
    return urllib.parse.urlunparse(parsed._replace(path=path, query="", params="", fragment=""))


def _openai_models_url(chat_url: str) -> str:
    parsed = urllib.parse.urlparse(chat_url)
    path = parsed.path.rstrip("/")
    if path.endswith("/chat/completions"):
        path = path[: -len("/chat/completions")] + "/models"
    elif not path.endswith("/models"):
        path = f"{path}/models" if path else "/models"
    return urllib.parse.urlunparse(parsed._replace(path=path, query="", params="", fragment=""))


def _openai_compatible_available(chat_url: str, api_key: str = "") -> tuple[bool, str]:
    headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
    request = urllib.request.Request(_openai_models_url(chat_url), headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=2) as response:
            if 200 <= response.status < 300:
                return True, "OpenAI-compatible models API detected"
    except urllib.error.HTTPError as exc:
        return False, f"OpenAI-compatible models API returned HTTP {exc.code}"
    except Exception as exc:
        return False, f"OpenAI-compatible models API not reachable: {str(exc)[:120]}"
    return False, "OpenAI-compatible models API not detected"


def _is_openai_local_config(provider: str, url: str) -> bool:
    return provider == "openai" or "chat/completions" in url or url.rstrip("/").endswith("/models")


def _local_model_available() -> tuple[bool, str]:
    cfg = local_model_config()
    if cfg["url"]:
        provider = cfg["provider"].lower()
        label = cfg["provider"] if cfg["provider"] != "auto" else "auto"
        if _is_openai_local_config(provider, cfg["url"]):
            ok, detail = _openai_compatible_available(cfg["url"], cfg["api_key"])
        else:
            ok, detail = _ollama_api_available(_ollama_tags_url(cfg["url"]))
        if ok:
            return True, f"Configured {label}: {cfg['model'] or detail}"
        return False, detail
    return _ollama_available()


def _cloud_model_available() -> tuple[bool, str]:
    cfg = cloud_model_config()
    if not cfg["url"] or not cfg["api_key"]:
        return False, "Set cloud endpoint and API key"
    ok, detail = _openai_compatible_available(cfg["url"], cfg["api_key"])
    if ok:
        return True, f"Configured: {cfg['model'] or detail}"
    return False, detail


# Keep user-facing Chinese strings as Unicode escapes so this gateway remains
# stable across Windows console/codepage changes.
def deliver_agent_sync(
    contact_id: str,
    text: str,
    task_id: str = "",
    delivery_mode: str | AgentDeliveryMode = AgentDeliveryMode.RESPOND,
    conversation_id: str = "",
    source_message_id: str = "",
    return_path: str = "",
    protocol: str = "1.0",
    required_features: tuple[str, ...] = (),
) -> dict:
    spec = all_agent_specs().get(contact_id)
    if spec is None:
        raise AgentAdapterExecutionError(f"Unknown Agent: {contact_id}")
    mode = AgentDeliveryMode.parse(delivery_mode)
    start = time.perf_counter()
    if mode == AgentDeliveryMode.RESPOND:
        _agent_execution_started(contact_id)
    try:
        result = desktop_agent_provider().deliver(
            AgentAdapterRequest(
                agent_id=contact_id,
                prompt=text,
                run_id=task_id,
                idempotency_key=task_id,
                delivery_mode=mode,
                protocol=protocol,
                required_features=frozenset(required_features),
                conversation_id=conversation_id,
                source_message_id=source_message_id,
                return_path=return_path,
            )
        )
        if mode == AgentDeliveryMode.RESPOND:
            if result.state != "completed" or not result.reply:
                raise AgentAdapterExecutionError(
                    result.error or f"Agent Run {result.run_id} is {result.state}"
                )
            _append_execution_log(
                spec=spec,
                contact_id=contact_id,
                prompt=text,
                reply=result.reply,
                duration_ms=int((time.perf_counter() - start) * 1000),
                ok=True,
            )
            _agent_execution_finished(
                contact_id,
                True,
                "Agent result replayed from a durable receipt" if result.replayed else "Agent is ready",
            )
        return result.public()
    except Exception as exc:
        if mode == AgentDeliveryMode.RESPOND and _agent_runtime_snapshot(contact_id).get("status") == "busy":
            _agent_execution_finished(contact_id, False, str(exc))
        if mode == AgentDeliveryMode.RESPOND:
            _append_execution_log(
                spec=spec,
                contact_id=contact_id,
                prompt=text,
                reply="",
                duration_ms=int((time.perf_counter() - start) * 1000),
                ok=False,
                error=str(exc)[:200],
            )
        raise


def ask_agent_sync(contact_id: str, text: str, task_id: str = "") -> str:
    return str(deliver_agent_sync(contact_id, text, task_id=task_id).get("reply") or "")


def _ask_agent_sync_inner(contact_id: str, text: str, spec: AgentSpec | None, task_id: str = "") -> str:
    if spec is None:
        return f"[SignalASI] \u672a\u77e5 Agent: {contact_id}"
    if spec.id == "local-llm":
        return ask_local_model(text, timeout=spec.timeout)
    if spec.id == "cloud-model":
        return ask_cloud_model(text, timeout=spec.timeout)
    return ask_cli_agent(spec, text, task_id=task_id)


def _agent_permission(spec: AgentSpec | None) -> str:
    if spec is None:
        return "unknown"
    return PERMISSION_LABELS.get(spec.kind, "local_process")


def _append_execution_log(
    spec: AgentSpec | None,
    contact_id: str,
    prompt: str,
    reply: str,
    duration_ms: int,
    ok: bool,
    error: str = "",
) -> None:
    execution_log_path = _execution_log_path()
    prompt_bytes = prompt.encode("utf-8", errors="replace")
    entry = {
        "ts": datetime.now(timezone.utc).isoformat(),
        "contact_id": contact_id,
        "agent_name": spec.name if spec else contact_id,
        "kind": spec.kind if spec else "unknown",
        "permission": _agent_permission(spec),
        "prompt_sha256": hashlib.sha256(prompt_bytes).hexdigest()[:16],
        "prompt_chars": len(prompt),
        "reply_chars": len(reply or ""),
        "duration_ms": duration_ms,
        "ok": ok,
        "error": error,
    }
    try:
        execution_log_path.parent.mkdir(parents=True, exist_ok=True)
        if execution_log_path.exists() and execution_log_path.stat().st_size > EXECUTION_LOG_MAX_BYTES:
            backup = execution_log_path.with_suffix(".jsonl.1")
            if backup.exists():
                backup.unlink()
            execution_log_path.replace(backup)
        with execution_log_path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(entry, ensure_ascii=False, separators=(",", ":")) + "\n")
    except Exception:
        pass


def recent_agent_execution_log(limit: int = 50) -> dict:
    limit = max(1, min(int(limit or 50), 200))
    execution_log_path = _execution_log_path()
    if not execution_log_path.exists():
        return {"path": str(execution_log_path), "entries": []}
    try:
        lines = execution_log_path.read_text(encoding="utf-8-sig").splitlines()[-limit:]
        entries = []
        for line in lines:
            try:
                entries.append(json.loads(line))
            except Exception:
                continue
        entries.reverse()
        return {"path": str(execution_log_path), "entries": entries}
    except Exception as exc:
        return {"path": str(execution_log_path), "entries": [], "error": str(exc)[:200]}


def ask_cli_agent(spec: AgentSpec, text: str, task_id: str = "") -> str:
    command = _command_for(spec)
    if not command:
        return f"[{spec.name}] \u672a\u914d\u7f6e\u542f\u52a8\u547d\u4ee4"
    try:
        from task_workspace import task_workspace

        args, stdin_text = _apply_prompt(command, text)
        working_directory = task_workspace(task_id, spec.id)
        agent_env = _agent_env(spec)
        agent_env.update(
            {
                "SIGNALASI_TASK_ID": task_id or working_directory.name,
                "SIGNALASI_TASK_WORKSPACE": str(working_directory),
                "SIGNALASI_OUTPUT_DIR": str(working_directory / "outputs"),
                "SIGNALASI_TEMP_DIR": str(working_directory / "temp"),
            }
        )
        process = subprocess.Popen(
            args,
            stdin=subprocess.PIPE if stdin_text is not None else None,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=agent_env,
            cwd=str(working_directory),
        )
        if task_id:
            from agent_task_manager import agent_task_manager
            agent_task_manager.register_process(task_id, process)
        stdout, stderr = process.communicate(
            input=stdin_text.encode("utf-8") if stdin_text is not None else None,
            timeout=spec.timeout,
        )
        if task_id:
            agent_task_manager.record_exit_code(task_id, process.returncode)
        raw = (decode_output(stdout or b"") or decode_output(stderr or b"")).strip()
        if not raw:
            return f"[{spec.name}] \u65e0\u54cd\u5e94"
        return clean_agent_output(spec, raw, text)
    except FileNotFoundError:
        return f"[{spec.name}] \u672a\u68c0\u6d4b\u5230\u547d\u4ee4\uff1a{command[0]}\u3002\u8bf7\u5728 SignalASI Desktop \u4e2d\u914d\u7f6e\u8fde\u63a5\u5668\u3002"
    except subprocess.TimeoutExpired:
        try:
            process.kill()
            process.communicate(timeout=3)
        except Exception:
            pass
        return f"[{spec.name}] \u8d85\u65f6"
    except Exception as exc:
        return f"[{spec.name}] \u8c03\u7528\u5931\u8d25\uff1a{str(exc)[:200]}"


def _agent_env(spec: AgentSpec) -> dict:
    env = {**os.environ, "SIGNALASI_AGENT_MODE": "1"}
    if spec.id == "hermes":
        env["HERMES_YOLO_MODE"] = "1"
    if spec.id == "codex":
        proxy = os.environ.get("SIGNALASI_CODEX_PROXY", "").strip()
        if not proxy and _tcp_available("127.0.0.1", 7890):
            proxy = "http://127.0.0.1:7890"
        if proxy:
            for name in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy"):
                env[name] = proxy
            for name in ("NO_PROXY", "no_proxy"):
                env[name] = "localhost,127.0.0.1,::1"
    return env


def _tcp_available(host: str, port: int) -> bool:
    try:
        with socket.create_connection((host, port), timeout=0.2):
            return True
    except OSError:
        return False


def ask_local_model(text: str, timeout: int = 120) -> str:
    cfg = local_model_config()
    provider = cfg["provider"].lower()
    configured_url = cfg["url"] or os.environ.get("SIGNALASI_OLLAMA_URL", "").strip()
    if not configured_url:
        ok, _detail = _ollama_available()
        if not ok:
            return "[Local LLM] \u672a\u8fde\u63a5\u672c\u5730\u6a21\u578b\u3002\u8bf7\u5b89\u88c5 Ollama\uff0c\u6216\u5728 SignalASI Desktop \u4e2d\u914d\u7f6e\u672c\u5730 OpenAI-compatible \u670d\u52a1\u3002"
    ollama_url = configured_url or "http://127.0.0.1:11434/api/generate"
    model = cfg["model"] or os.environ.get("SIGNALASI_OLLAMA_MODEL", "qwen2.5:7b")
    try:
        if provider == "openai" or (provider == "auto" and "chat/completions" in ollama_url):
            payload = {
                "model": model or "local-model",
                "messages": [{"role": "user", "content": text}],
            }
            headers = {"Authorization": f"Bearer {cfg['api_key']}"} if cfg["api_key"] else None
            data = _post_json(ollama_url, payload, timeout=min(timeout, 30), headers=headers)
            return _extract_chat_completion(data, "Local LLM")
        payload = {
            "model": model,
            "prompt": text,
            "stream": False,
        }
        data = _post_json(ollama_url, payload, timeout=min(timeout, 30))
        return str(data.get("response") or data.get("message") or "[Local LLM] \u65e0\u54cd\u5e94")
    except Exception as exc:
        return f"[Local LLM] \u672a\u8fde\u63a5\u672c\u5730\u6a21\u578b\u3002\u8bf7\u5b89\u88c5 Ollama\uff0c\u6216\u914d\u7f6e SIGNALASI_OLLAMA_URL / SIGNALASI_OLLAMA_MODEL\u3002\u8be6\u60c5\uff1a{str(exc)[:160]}"


def ask_cloud_model(text: str, timeout: int = 120) -> str:
    cfg = cloud_model_config()
    url = cfg["url"] or os.environ.get("SIGNALASI_CLOUD_MODEL_URL", "").strip()
    api_key = cfg["api_key"] or os.environ.get("SIGNALASI_CLOUD_MODEL_API_KEY", "").strip()
    model = cfg["model"] or os.environ.get("SIGNALASI_CLOUD_MODEL_NAME", "default")
    if not url or not api_key:
        return "[Cloud Model] \u672a\u914d\u7f6e\u4e91\u7aef\u6a21\u578b\u3002\u8bf7\u5728 SignalASI Desktop \u4e2d\u8bbe\u7f6e API \u5730\u5740\u548c\u5bc6\u94a5\u3002"
    payload = {
        "model": model,
        "messages": [{"role": "user", "content": text}],
    }
    try:
        data = _post_json(url, payload, timeout=timeout, headers={"Authorization": f"Bearer {api_key}"})
        return _extract_chat_completion(data, "Cloud Model")
    except Exception as exc:
        return f"[Cloud Model] \u8c03\u7528\u5931\u8d25\uff1a{str(exc)[:200]}"


def _extract_chat_completion(data: dict, label: str) -> str:
    choices = data.get("choices") or []
    if choices:
        message = choices[0].get("message") or {}
        return str(message.get("content") or choices[0].get("text") or data)
    return str(data.get("response") or data.get("message") or f"[{label}] \u65e0\u54cd\u5e94")


def connector_self_test(include_agent_calls: bool = False, include_mobile_delivery: bool = True) -> dict:
    results = []
    for spec in all_agent_specs().values():
        guide = SETUP_GUIDES.get(spec.id, {})
        status = agent_status(spec)
        item = {
            **status,
            "mobile_contact_id": guide.get("mobile_contact_id", spec.id),
            "setup": guide.get("setup", ""),
            "setup_code": _setup_code(spec),
            "setup_params": _agent_params(spec),
            "agent_call": {
                "status": "skipped",
                "ok": None,
                "detail": "Agent call test disabled",
                "code": "agent_call_test_disabled",
                "params": _agent_params(spec),
            },
            "mobile_delivery": {
                "status": "skipped",
                "ok": None,
                "detail": "Mobile delivery test disabled",
                "code": "mobile_delivery_test_disabled",
                "params": _agent_params(spec),
            },
        }
        if include_agent_calls:
            if status["status"] != "ready":
                item["agent_call"] = {
                    "status": "skipped",
                    "ok": None,
                    "detail": "Agent is not ready",
                    "code": "agent_not_ready",
                    "params": _agent_params(spec),
                }
            else:
                try:
                    reply = ask_agent_sync(spec.id, "SignalASI self test. Reply OK only.")
                    item["agent_call"] = {
                        "status": "ok" if reply and not reply.startswith("[") else "warning",
                        "ok": bool(reply and not reply.startswith("[")),
                        "detail": reply[:500],
                        "code": "agent_call_ok" if reply and not reply.startswith("[") else "agent_call_warning",
                        "params": _agent_params(spec),
                    }
                except Exception as exc:
                    item["agent_call"] = {
                        "status": "error",
                        "ok": False,
                        "detail": str(exc)[:500],
                        "code": "agent_call_failed",
                        "params": _agent_params(spec, error=str(exc)[:200]),
                    }
        if include_mobile_delivery:
            try:
                from mqtt_bridge import publish_mobile_test_message

                content = f"SIGNALASI_SELF_TEST_{spec.id}_{os.getpid()}"
                delivery = publish_mobile_test_message(spec.id, content)
                ok = bool(delivery.get("ok"))
                item["mobile_delivery"] = {
                    "status": "ok" if ok else "error",
                    "ok": ok,
                    "detail": delivery,
                    "code": "mobile_delivery_ok" if ok else "mobile_delivery_failed",
                    "params": _agent_params(spec, delivery=delivery),
                }
            except Exception as exc:
                item["mobile_delivery"] = {
                    "status": "error",
                    "ok": False,
                    "detail": str(exc)[:500],
                    "code": "mobile_delivery_failed",
                    "params": _agent_params(spec, error=str(exc)[:200]),
                }
        item["overall"] = _self_test_overall(item)
        results.append(item)
    return {
        "protocol": "SignalASI Link Protocol",
        "connector": "SignalASI Desktop",
        "pairing_route": "/signalasi/verify",
        "include_agent_calls": include_agent_calls,
        "include_mobile_delivery": include_mobile_delivery,
        "summary": {
            "ready": [item["id"] for item in results if item["status"] == "ready"],
            "needs_setup": [item["id"] for item in results if item["status"] != "ready"],
            "mobile_delivery_ok": [item["id"] for item in results if item["mobile_delivery"]["ok"] is True],
            "mobile_delivery_failed": [item["id"] for item in results if item["mobile_delivery"]["ok"] is False],
        },
        "results": results,
    }


def _self_test_overall(item: dict) -> str:
    if item["status"] != "ready":
        return "needs_setup"
    if item["mobile_delivery"]["ok"] is False:
        return "delivery_failed"
    if item["agent_call"]["ok"] is False:
        return "agent_failed"
    return "ok"


def _post_json(url: str, payload: dict, timeout: int, headers: dict | None = None) -> dict:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": "application/json", **(headers or {})},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {exc.code}: {detail[:200]}") from exc


