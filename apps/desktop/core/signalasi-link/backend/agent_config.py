"""Persistent SignalASI Agent connector configuration."""
from __future__ import annotations

import json
import os
import re
from copy import deepcopy
from pathlib import Path
from typing import Any


def _config_path() -> Path:
    configured = os.environ.get("SIGNALASI_CONFIG_PATH", "").strip()
    if configured:
        return Path(configured)
    state_directory = os.environ.get("SIGNALASI_STATE_DIR", "").strip()
    root = Path(state_directory) if state_directory else Path(os.environ.get("APPDATA") or Path.home()) / "SignalASI"
    return root / "agents.json"
MASK = "********"

DEFAULT_CONFIG: dict[str, Any] = {
    "commands": {
        "hermes": "hermes chat -q",
        "codex": "codex exec --skip-git-repo-check --ephemeral --model gpt-5.6-sol -c model_reasoning_effort=\"low\" -",
        "claude": "claude -p",
        "openclaw": "openclaw agent --agent main --message {prompt} --json",
        "custom-agent": "",
    },
    "local_model": {
        "name": "Local LLM",
        "provider": "auto",
        "url": "",
        "model": "qwen2.5:7b",
        "api_key": "",
        "context_window_tokens": 64_000,
        "max_output_tokens": 4_096,
        "context_model_summary": True,
    },
    "cloud_model": {
        "name": "Cloud Model",
        "url": "",
        "model": "",
        "api_key": "",
        "context_window_tokens": 64_000,
        "max_output_tokens": 4_096,
        "context_model_summary": True,
    },
    "custom_agent": {
        "name": "Custom Agent",
    },
    "custom_agents": [],
}


def load_config(mask_secrets: bool = False) -> dict[str, Any]:
    config = deepcopy(DEFAULT_CONFIG)
    config_path = _config_path()
    if config_path.exists():
        try:
            with config_path.open("r", encoding="utf-8-sig") as handle:
                saved = json.load(handle)
            _merge(config, saved)
        except Exception:
            pass
    if mask_secrets:
        for section in ("local_model", "cloud_model"):
            key = config.get(section, {}).get("api_key", "")
            if key:
                config[section]["api_key"] = MASK
    return config


def save_config(incoming: dict[str, Any]) -> dict[str, Any]:
    current = load_config(mask_secrets=False)
    updated = deepcopy(DEFAULT_CONFIG)
    _merge(updated, current)
    sanitized = _sanitize(incoming)

    for section in ("local_model", "cloud_model"):
        if sanitized.get(section, {}).get("api_key") == MASK:
            sanitized[section]["api_key"] = current.get(section, {}).get("api_key", "")

    _merge(updated, sanitized)
    _write_config(_config_path(), updated)
    return load_config(mask_secrets=True)


def _write_config(config_path: Path, config: dict[str, Any]) -> None:
    config_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = config_path.with_suffix(f"{config_path.suffix}.tmp")
    with temporary.open("w", encoding="utf-8") as handle:
        json.dump(config, handle, ensure_ascii=False, indent=2)
    temporary.replace(config_path)


def command_for(agent_id: str) -> str:
    config = load_config()
    command = str(config.get("commands", {}).get(agent_id, "")).strip()
    if command:
        return command
    for agent in custom_agent_configs(config):
        if agent["id"] == agent_id:
            return agent["command"]
    return ""


def local_model_config() -> dict[str, Any]:
    data = load_config().get("local_model", {})
    name = str(data.get("name", "")).strip() or "Local LLM"
    return {
        "name": name[:48],
        "provider": str(data.get("provider", "auto")).strip() or "auto",
        "url": str(data.get("url", "")).strip(),
        "model": str(data.get("model", "")).strip(),
        "api_key": str(data.get("api_key", "")).strip(),
        "context_window_tokens": _bounded_int(data.get("context_window_tokens"), 64_000, 4_096, 1_000_000),
        "max_output_tokens": _bounded_int(data.get("max_output_tokens"), 4_096, 512, 128_000),
        "context_model_summary": _as_bool(data.get("context_model_summary"), True),
    }


def cloud_model_config() -> dict[str, Any]:
    data = load_config().get("cloud_model", {})
    name = str(data.get("name", "")).strip() or "Cloud Model"
    return {
        "name": name[:48],
        "url": str(data.get("url", "")).strip(),
        "model": str(data.get("model", "")).strip(),
        "api_key": str(data.get("api_key", "")).strip(),
        "context_window_tokens": _bounded_int(data.get("context_window_tokens"), 64_000, 4_096, 1_000_000),
        "max_output_tokens": _bounded_int(data.get("max_output_tokens"), 4_096, 512, 128_000),
        "context_model_summary": _as_bool(data.get("context_model_summary"), True),
    }


def custom_agent_config() -> dict[str, str]:
    data = load_config().get("custom_agent", {})
    name = str(data.get("name", "")).strip() or "Custom Agent"
    return {"name": name[:48]}


def custom_agent_configs(config: dict[str, Any] | None = None) -> list[dict[str, str]]:
    data = (config or load_config()).get("custom_agents", [])
    if not isinstance(data, list):
        return []
    agents: list[dict[str, str]] = []
    seen: set[str] = set()
    reserved = {"hermes", "codex", "claude", "openclaw", "local-llm", "cloud-model", "custom-agent"}
    for item in data[:12]:
        if not isinstance(item, dict):
            continue
        agent_id = _normalize_agent_id(str(item.get("id", "")))
        if not agent_id or agent_id in reserved or agent_id in seen:
            continue
        command = str(item.get("command", "")).strip()
        if not command:
            continue
        name = str(item.get("name", "")).strip() or agent_id.replace("-", " ").title()
        kind = str(item.get("kind", "custom-cli")).strip() or "custom-cli"
        agents.append({
            "id": agent_id[:48],
            "name": name[:48],
            "kind": kind[:32],
            "command": command,
        })
        seen.add(agent_id)
    return agents


def _merge(target: dict[str, Any], source: dict[str, Any]) -> None:
    for key, value in source.items():
        if isinstance(value, dict) and isinstance(target.get(key), dict):
            _merge(target[key], value)
        else:
            target[key] = value


def _sanitize(value: Any) -> Any:
    if isinstance(value, dict):
        return {str(k): _sanitize(v) for k, v in value.items()}
    if isinstance(value, list):
        return [_sanitize(v) for v in value]
    if value is None:
        return ""
    return value


def _normalize_agent_id(value: str) -> str:
    cleaned = re.sub(r"[^a-z0-9_-]+", "-", value.strip().lower())
    cleaned = re.sub(r"-+", "-", cleaned).strip("-_")
    return cleaned[:48]


def _bounded_int(value: Any, default: int, minimum: int, maximum: int) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        parsed = default
    return max(minimum, min(maximum, parsed))


def _as_bool(value: Any, default: bool) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    normalized = str(value).strip().lower()
    if normalized in {"1", "true", "yes", "on"}:
        return True
    if normalized in {"", "0", "false", "no", "off"}:
        return False
    return default
