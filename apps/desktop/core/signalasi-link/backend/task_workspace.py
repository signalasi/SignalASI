"""Isolated filesystem workspaces for user-initiated Agent tasks."""
from __future__ import annotations

import json
import os
import re
import time
import uuid
from pathlib import Path


BACKEND_DIR = Path(__file__).resolve().parent
DEFAULT_WORKSPACE_ROOT = Path.home() / "SignalASIWorkspace"
TASK_SUBDIRECTORIES = ("outputs", "scripts", "downloads", "screenshots", "logs", "temp")


def workspace_root() -> Path:
    configured = os.environ.get("SIGNALASI_WORKSPACE_ROOT", "").strip()
    candidate = Path(configured).expanduser() if configured else DEFAULT_WORKSPACE_ROOT
    candidate = candidate.resolve()
    if _inside_protected_tree(candidate):
        candidate = DEFAULT_WORKSPACE_ROOT.resolve()
    candidate.mkdir(parents=True, exist_ok=True)
    return candidate


def task_workspace(task_id: str = "", agent_id: str = "") -> Path:
    safe_id = _safe_component(task_id) or f"adhoc-{uuid.uuid4()}"
    directory = (workspace_root() / "tasks" / safe_id).resolve()
    tasks_root = (workspace_root() / "tasks").resolve()
    if not _is_within(directory, tasks_root):
        raise ValueError("Task workspace escaped the configured workspace root")
    directory.mkdir(parents=True, exist_ok=True)
    for name in TASK_SUBDIRECTORIES:
        (directory / name).mkdir(exist_ok=True)
    metadata = directory / ".signalasi-task.json"
    if not metadata.exists():
        metadata.write_text(
            json.dumps(
                {
                    "task_id": task_id or safe_id,
                    "agent_id": agent_id,
                    "created_at": int(time.time() * 1000),
                },
                ensure_ascii=True,
                indent=2,
            ),
            encoding="utf-8",
        )
    return directory


def _safe_component(value: str) -> str:
    normalized = re.sub(r"[^A-Za-z0-9._-]+", "-", str(value or "").strip())
    normalized = normalized.strip(".-")[:96]
    return normalized if normalized not in {"", ".", ".."} else ""


def _inside_protected_tree(candidate: Path) -> bool:
    protected = [BACKEND_DIR]
    current = BACKEND_DIR
    for parent in (current, *current.parents):
        if (parent / ".git").exists():
            protected.append(parent.resolve())
            break
    return any(_is_within(candidate, root) for root in protected)


def _is_within(candidate: Path, root: Path) -> bool:
    try:
        candidate.relative_to(root)
        return True
    except ValueError:
        return False
