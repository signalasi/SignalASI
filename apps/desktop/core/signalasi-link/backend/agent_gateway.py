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
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from agent_config import cloud_model_config, command_for, custom_agent_config, custom_agent_configs, local_model_config

EXECUTION_LOG_PATH = Path(__file__).with_name("signalasi_agent_execution.jsonl")
EXECUTION_LOG_MAX_BYTES = 512 * 1024

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


BASE_AGENTS: dict[str, AgentSpec] = {
    "hermes": AgentSpec(
        id="hermes",
        name="Hermes Agent",
        kind="local-cli",
        command=["hermes", "chat", "-q", "{prompt}"],
        timeout=60,
        note="Hermes CLI",
        output_cleaner="hermes",
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
    ),
    "claude": AgentSpec(
        id="claude",
        name="Claude Code",
        kind="local-cli",
        command=["claude", "-p"],
        timeout=120,
        env_key="SIGNALASI_CLAUDE_CMD",
        note="Claude Code CLI wrapped by SignalASI Desktop",
    ),
    "local-llm": AgentSpec(
        id="local-llm",
        name="Local LLM",
        kind="local-model",
        command=None,
        timeout=120,
        note="Ollama or local OpenAI-compatible endpoint",
    ),
    "cloud-model": AgentSpec(
        id="cloud-model",
        name="Cloud Model",
        kind="cloud-model",
        command=None,
        timeout=120,
        note="Cloud API endpoint configured by the user",
    ),
    "custom-agent": AgentSpec(
        id="custom-agent",
        name="Custom Agent",
        kind="custom-cli",
        command=None,
        timeout=120,
        env_key="SIGNALASI_CUSTOM_AGENT_CMD",
        note="Any CLI or MCP wrapper command exposed as a SignalASI contact",
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


def clean_hermes_output(raw: str) -> str:
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
    return "\n".join(cleaned_lines).strip()


def clean_agent_output(spec: AgentSpec, raw: str) -> str:
    if spec.output_cleaner == "hermes":
        return clean_hermes_output(raw)
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
        )
    return specs


def visible_agent_specs() -> dict[str, AgentSpec]:
    return {
        agent_id: spec
        for agent_id, spec in all_agent_specs().items()
        if agent_id not in DESKTOP_HIDDEN_AGENT_IDS
    }


def list_agents() -> list[dict]:
    return [agent_status(spec) for spec in visible_agent_specs().values()]


def connector_diagnostics() -> dict:
    agents = []
    for spec in visible_agent_specs().values():
        status = agent_status(spec)
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
        ],
        "ready": ready,
        "needs_setup": needs_setup,
        "agents": agents,
    }


def agent_status(spec: AgentSpec) -> dict:
    display_name = spec.name
    if spec.id == "local-llm":
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
    detail_code = _agent_detail_code(spec, ok, detail)
    return {
        "id": spec.id,
        "name": display_name,
        "kind": spec.kind,
        "status": "ready" if ok else "needs_setup",
        "detail": detail,
        "detail_code": detail_code,
        "detail_params": _agent_params(spec, detail=detail, display_name=display_name),
        "note": spec.note,
    }


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
    ok, detail = _command_available("ollama")
    if ok:
        return True, detail
    return _ollama_api_available("http://127.0.0.1:11434/api/tags", fallback=detail)


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
def ask_agent_sync(contact_id: str, text: str, task_id: str = "") -> str:
    spec = all_agent_specs().get(contact_id)
    start = time.perf_counter()
    try:
        reply = _ask_agent_sync_inner(contact_id, text, spec, task_id=task_id)
        _append_execution_log(
            spec=spec,
            contact_id=contact_id,
            prompt=text,
            reply=reply,
            duration_ms=int((time.perf_counter() - start) * 1000),
            ok=bool(reply and not reply.startswith("[")),
        )
        return reply
    except Exception as exc:
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
        if EXECUTION_LOG_PATH.exists() and EXECUTION_LOG_PATH.stat().st_size > EXECUTION_LOG_MAX_BYTES:
            backup = EXECUTION_LOG_PATH.with_suffix(".jsonl.1")
            if backup.exists():
                backup.unlink()
            EXECUTION_LOG_PATH.replace(backup)
        with EXECUTION_LOG_PATH.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(entry, ensure_ascii=False, separators=(",", ":")) + "\n")
    except Exception:
        pass


def recent_agent_execution_log(limit: int = 50) -> dict:
    limit = max(1, min(int(limit or 50), 200))
    if not EXECUTION_LOG_PATH.exists():
        return {"path": str(EXECUTION_LOG_PATH), "entries": []}
    try:
        lines = EXECUTION_LOG_PATH.read_text(encoding="utf-8-sig").splitlines()[-limit:]
        entries = []
        for line in lines:
            try:
                entries.append(json.loads(line))
            except Exception:
                continue
        entries.reverse()
        return {"path": str(EXECUTION_LOG_PATH), "entries": entries}
    except Exception as exc:
        return {"path": str(EXECUTION_LOG_PATH), "entries": [], "error": str(exc)[:200]}


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
        return clean_agent_output(spec, raw)
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


