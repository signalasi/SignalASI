"""Isolated filesystem workspaces for user-initiated Agent tasks."""
from __future__ import annotations

import json
import os
import re
import shutil
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


def cleanup_task_temporary_files(task_ids: list[str] | set[str]) -> list[str]:
    tasks_root = (workspace_root() / "tasks").resolve()
    cleaned: list[str] = []
    for task_id in task_ids:
        safe_id = _safe_component(task_id)
        if not safe_id:
            continue
        directory = (tasks_root / safe_id).resolve()
        if not _is_within(directory, tasks_root) or not directory.exists():
            continue
        for name in ("temp", "logs"):
            target = (directory / name).resolve()
            if _is_within(target, directory) and target.exists():
                shutil.rmtree(target, ignore_errors=True)
        cleaned.append(safe_id)
    return cleaned


def task_artifacts(task_id: str, limit: int = 50) -> list[dict]:
    safe_id = _safe_component(task_id)
    if not safe_id:
        return []
    tasks_root = (workspace_root() / "tasks").resolve()
    directory = (tasks_root / safe_id).resolve()
    if not _is_within(directory, tasks_root) or not directory.exists():
        return []
    artifacts: list[dict] = []
    for category in ("outputs", "downloads", "screenshots"):
        category_root = (directory / category).resolve()
        if not _is_within(category_root, directory) or not category_root.exists():
            continue
        for file_path in category_root.rglob("*"):
            if not file_path.is_file() or file_path.is_symlink():
                continue
            relative = file_path.relative_to(directory).as_posix()
            if relative.lower().startswith("downloads/input/"):
                continue
            artifacts.append({
                "name": file_path.name,
                "relative_path": relative,
                "category": category,
                "size": file_path.stat().st_size,
            })
            if len(artifacts) >= max(1, min(limit, 100)):
                return artifacts
    return artifacts


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
