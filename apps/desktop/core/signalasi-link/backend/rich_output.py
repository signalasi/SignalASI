"""Build bounded SignalASI rich-output documents from Agent results."""

from __future__ import annotations

import json
import mimetypes
import re
from pathlib import PurePosixPath
from typing import Any
from urllib.parse import quote, urlparse


MAX_BLOCKS = 100
MAX_TEXT = 32_000
MAX_ROWS = 500
MAX_COLUMNS = 24
ALLOWED_TYPES = {
    "text", "heading", "quote", "list", "divider", "code", "json", "key_value",
    "table", "image", "gallery", "video", "audio",
    "file", "link", "citation", "status", "progress", "metric", "actions",
    "approval", "form", "tool", "diff", "chart", "timeline", "notice", "html",
    "webpage", "unknown",
}
RICH_FENCE = re.compile(r"```signalasi-rich\s*(.*?)```", re.IGNORECASE | re.DOTALL)


def build_rich_output(content: str, output_files: list[dict] | None = None, task_id: str = "") -> tuple[str, dict | None]:
    """Return accessible fallback text and an optional validated rich document."""
    source = str(content or "")
    blocks: list[dict] = []

    for match in RICH_FENCE.finditer(source):
        parsed = _load_json(match.group(1))
        if isinstance(parsed, dict):
            candidate = parsed.get("blocks", [])
        else:
            candidate = parsed
        if isinstance(candidate, list):
            blocks.extend(_normalize_block(item) for item in candidate if isinstance(item, dict))

    clean_content = RICH_FENCE.sub("", source).strip()
    blocks = [item for item in blocks if item]
    from response_policy import is_input_artifact
    blocks.extend(
        _artifact_block(item, task_id)
        for item in (output_files or [])
        if isinstance(item, dict) and not is_input_artifact(item)
    )
    blocks = [item for item in blocks if item][:MAX_BLOCKS]

    if not blocks:
        return source.strip(), None
    if not clean_content:
        clean_content = _fallback_text(blocks)
    return clean_content[:MAX_TEXT], {"version": 1, "blocks": blocks}


def _load_json(raw: str) -> Any:
    try:
        return json.loads(raw)
    except (TypeError, ValueError):
        return None


def _normalize_block(raw: dict) -> dict:
    block_type = str(raw.get("type") or "").strip().lower()
    if block_type not in ALLOWED_TYPES:
        block_type = "text"
    block = {
        "id": str(raw.get("id") or f"block-{abs(hash(json.dumps(raw, sort_keys=True, default=str)))}")[:120],
        "type": block_type,
    }
    for key, limit in (
        ("title", 500), ("text", MAX_TEXT), ("uri", 4096), ("mime_type", 160),
        ("language", 80), ("fallback_text", MAX_TEXT),
    ):
        value = str(raw.get(key) or "").strip()[:limit]
        if value:
            block[key] = value
    if block_type == "webpage" and _is_image_uri(block.get("uri", ""), block.get("mime_type", "")):
        block["type"] = "image"
        block_type = "image"
    if block_type in {"table", "chart"}:
        block["columns"] = [str(value)[:2000] for value in list(raw.get("columns") or [])[:MAX_COLUMNS]]
    if block_type in {"table", "chart", "key_value", "list", "timeline", "gallery"}:
        block["rows"] = [
            [str(value)[:2000] for value in list(row or [])[:MAX_COLUMNS]]
            for row in list(raw.get("rows") or [])[:MAX_ROWS]
            if isinstance(row, (list, tuple))
        ]
    metadata = raw.get("metadata")
    if isinstance(metadata, dict):
        block["metadata"] = {
            str(key)[:80]: str(value)[:2000]
            for key, value in list(metadata.items())[:32]
        }
    if block_type == "progress":
        block["value"] = _bounded_int(raw.get("value"), -1, 1_000_000, 0)
        block["maximum"] = _bounded_int(raw.get("maximum"), 1, 1_000_000, 100)
    if block_type in {"actions", "approval", "form"}:
        block["actions"] = [
            {
                "id": str(item.get("id") or f"action-{index}")[:120],
                "label": str(item.get("label") or "")[:120],
                "verb": str(item.get("verb") or "")[:80].lower(),
                "value": str(item.get("value") or "")[:8_000],
                "style": str(item.get("style") or "default")[:40],
            }
            for index, item in enumerate(list(raw.get("actions") or [])[:12])
            if isinstance(item, dict) and item.get("label") and item.get("verb")
        ]
    if block_type == "form":
        block["fields"] = [
            {
                "id": str(item.get("id") or "")[:120],
                "label": str(item.get("label") or "")[:200],
                "input_type": str(item.get("input_type") or "text")[:40].lower(),
                "value": str(item.get("value") or "")[:4_000],
                "required": bool(item.get("required")),
                "options": [str(value)[:500] for value in list(item.get("options") or [])[:50]],
            }
            for item in list(raw.get("fields") or [])[:24]
            if isinstance(item, dict) and item.get("id") and item.get("label")
        ]
    return block


def _artifact_block(raw: dict, task_id: str) -> dict:
    relative = str(raw.get("relative_path") or "").replace("\\", "/").strip("/")
    name = str(raw.get("name") or PurePosixPath(relative).name or "Artifact")[:500]
    if not relative:
        return {}
    mime_type = str(raw.get("mime_type") or mimetypes.guess_type(name)[0] or "application/octet-stream")
    if mime_type.startswith("image/"):
        block_type = "image"
    elif mime_type.startswith("video/"):
        block_type = "video"
    elif mime_type.startswith("audio/"):
        block_type = "audio"
    else:
        block_type = "file"
    safe_task = quote(str(task_id or "task"), safe="")
    safe_path = quote(relative, safe="/")
    return {
        "id": f"artifact-{abs(hash((task_id, relative)))}",
        "type": block_type,
        "title": name,
        "text": f"{raw.get('category') or 'output'} · {int(raw.get('size') or 0)} bytes",
        "uri": f"signalasi-artifact://{safe_task}/{safe_path}",
        "mime_type": mime_type,
        "fallback_text": relative,
        "metadata": {
            "size": _human_size(int(raw.get("size") or 0)),
            "category": str(raw.get("category") or "output")[:80],
        },
    }


def _fallback_text(blocks: list[dict]) -> str:
    values = []
    for block in blocks:
        value = str(block.get("text") or block.get("title") or block.get("fallback_text") or block.get("uri") or "").strip()
        if value:
            values.append(value)
    return "\n\n".join(values)[:MAX_TEXT] or "Rich result"


def _bounded_int(value: Any, minimum: int, maximum: int, fallback: int) -> int:
    try:
        return max(minimum, min(maximum, int(value)))
    except (TypeError, ValueError):
        return fallback


def _is_image_uri(uri: str, mime_type: str) -> bool:
    if str(mime_type or "").lower().startswith("image/"):
        return True
    path = urlparse(str(uri or "")).path.lower()
    return path.endswith((".gif", ".png", ".jpg", ".jpeg", ".webp", ".avif"))


def _human_size(size: int) -> str:
    value = max(0, int(size or 0))
    for unit in ("B", "KB", "MB", "GB"):
        if value < 1024 or unit == "GB":
            return f"{value} {unit}" if unit == "B" else f"{value:.1f} {unit}"
        value /= 1024
    return "0 B"
