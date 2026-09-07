"""Build bounded GalaxySSI rich-output documents from Agent results."""

from __future__ import annotations

import base64
import hashlib
import json
import mimetypes
import re
from pathlib import Path
from pathlib import PurePosixPath
from typing import Any
from urllib.parse import quote, unquote, urlparse

from image_transport import MAX_IMAGE_TRANSPORT_BYTES, compress_image_file


MAX_BLOCKS = 100
MAX_TEXT = 32_000
MAX_ROWS = 500
MAX_COLUMNS = 24
MAX_INLINE_ARTIFACT_BYTES = MAX_IMAGE_TRANSPORT_BYTES
MAX_INLINE_ARTIFACT_B64 = ((MAX_INLINE_ARTIFACT_BYTES + 2) // 3) * 4
MAX_TOTAL_INLINE_ARTIFACT_B64 = MAX_INLINE_ARTIFACT_B64
ALLOWED_TYPES = {
    "text", "heading", "quote", "list", "divider", "code", "json", "key_value",
    "table", "image", "gallery", "video", "audio",
    "file", "link", "citation", "status", "progress", "metric", "actions",
    "approval", "form", "tool", "diff", "chart", "timeline", "notice", "html",
    "webpage", "unknown",
}
RICH_FENCE = re.compile(r"```galaxyssi-rich\s*(.*?)```", re.IGNORECASE | re.DOTALL)
SANDBOX_ARTIFACT_LINK = re.compile(
    r"!?\[[^\]]*]\(\s*<?(?:sandbox:)?/?(?:outputs|downloads|screenshots)/[^)\r\n>]+>?\s*\)",
    re.IGNORECASE,
)
ARTIFACT_TYPES = {"image", "gallery", "video", "audio", "file"}
ARTIFACT_CATEGORIES = {"outputs", "downloads", "screenshots"}
IMAGE_MIME_TYPES = {
    ".avif": "image/avif",
    ".gif": "image/gif",
    ".jpeg": "image/jpeg",
    ".jpg": "image/jpeg",
    ".png": "image/png",
    ".webp": "image/webp",
}
MIME_TYPE_OVERRIDES = {
    ".apk": "application/vnd.android.package-archive",
    ".apks": "application/zip",
    ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
}


def build_rich_output(
    content: str,
    output_files: list[dict] | None = None,
    task_id: str = "",
    *,
    inline_artifacts: bool = True,
) -> tuple[str, dict | None]:
    """Return accessible fallback text and an optional validated rich document."""
    source = str(content or "")
    blocks: list[dict] = []
    had_explicit_document = bool(RICH_FENCE.search(source))

    for match in RICH_FENCE.finditer(source):
        parsed = _load_json(match.group(1))
        if isinstance(parsed, dict):
            candidate = parsed.get("blocks", [])
        else:
            candidate = parsed
        if isinstance(candidate, list):
            blocks.extend(_normalize_block(item) for item in candidate if isinstance(item, dict))

    clean_content = _clean_visible_content(RICH_FENCE.sub("", source))
    blocks = [
        hydrated
        for item in blocks
        if item
        for hydrated in [_hydrate_explicit_artifact(item, task_id, inline_artifacts)]
        if hydrated
    ]
    from response_policy import is_input_artifact
    blocks.extend(
        _artifact_block(item, task_id, inline_artifacts)
        for item in (output_files or [])
        if isinstance(item, dict) and not is_input_artifact(item)
    )
    if clean_content and not _contains_equivalent_text(blocks, clean_content):
        blocks.insert(0, {
            "id": f"text-{hashlib.sha256(clean_content.encode('utf-8')).hexdigest()[:24]}",
            "type": "text",
            "text": clean_content[:MAX_TEXT],
        })
    blocks = _dedupe_blocks(item for item in blocks if item)[:MAX_BLOCKS]
    blocks = _limit_inline_artifact_payload(blocks)

    if not blocks:
        if had_explicit_document:
            if clean_content:
                return clean_content[:MAX_TEXT], None
            prefers_chinese = any("\u4e00" <= character <= "\u9fff" for character in source)
            unavailable = (
                "\u751f\u6210\u7684\u6587\u4ef6\u5f53\u524d\u4e0d\u53ef\u7528\uff0c\u8bf7\u91cd\u8bd5\u3002"
                if prefers_chinese else
                "The generated file is unavailable. Please try again."
            )
            return unavailable, None
        return source.strip(), None
    if not clean_content:
        clean_content = _fallback_text(blocks)
    return clean_content[:MAX_TEXT], {"version": 1, "blocks": blocks}


def _clean_visible_content(content: str) -> str:
    text = SANDBOX_ARTIFACT_LINK.sub("", str(content or ""))
    text = re.sub(r"[ \t]+\n", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def _contains_equivalent_text(blocks: list[dict], content: str) -> bool:
    normalized = re.sub(r"\s+", " ", content).strip().casefold()
    if not normalized:
        return True
    return any(
        str(block.get("type") or "").lower() in {"text", "heading", "notice"} and
        re.sub(r"\s+", " ", str(block.get("text") or block.get("title") or "")).strip().casefold() == normalized
        for block in blocks
    )


def _load_json(raw: str) -> Any:
    try:
        return json.loads(raw)
    except (TypeError, ValueError):
        return None


def _bounded_metadata(metadata: dict, artifact_uris=()) -> dict:
    # JSON canonicalization can reorder keys. Reserve identity fields explicitly,
    # then spend the remaining existing 32 slots on model-provided presentation.
    preferred = ["transport", "artifact_source_uri", "artifact_id", "transfer_id", "blob_transfer_id",
                 "size", "size_bytes", "sha256", "original_size_bytes", "original_sha256",
                 "blob_client_route_id", "blob_desktop_id", "blob_conversation_id", "blob_task_id",
                 "blob_turn_id", "blob_execution_generation"]
    preferred += ["blob_item_" + uri.rsplit("/", 1)[1] for uri in list(artifact_uris)[:10]
                  if isinstance(uri, str) and uri.startswith("galaxyssi-artifact://blob/")]
    keys = list(dict.fromkeys([key for key in preferred if key in metadata] + list(metadata)))[:32]
    return {str(key)[:80]: str(metadata[key])[:2000] for key in keys}


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
    encoded = str(raw.get("data_b64") or "").strip()
    if encoded and len(encoded) <= MAX_INLINE_ARTIFACT_B64:
        block["data_b64"] = encoded
    if block_type in {"file", "webpage"} and _is_image_uri(block.get("uri", ""), block.get("mime_type", "")):
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
        uris = ([block["uri"]] if block.get("uri") else []) + [row[0] for row in block.get("rows", []) if row]
        block["metadata"] = _bounded_metadata(metadata, uris)
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


def _artifact_block(raw: dict, task_id: str, inline_artifacts: bool = True) -> dict:
    relative = _safe_relative_artifact_path(str(raw.get("relative_path") or ""))
    source = _artifact_source(task_id, relative)
    if source is None:
        return {}
    name = str(raw.get("name") or source.name or "Artifact")[:500]
    mime_type = str(raw.get("mime_type") or _guess_mime_type(name))
    if mime_type.startswith("image/"):
        block_type = "image"
    elif mime_type.startswith("video/"):
        block_type = "video"
    elif mime_type.startswith("audio/"):
        block_type = "audio"
    else:
        block_type = "file"
    original_size = source.stat().st_size
    original_digest = _file_sha256(source)
    category = PurePosixPath(relative).parts[0].lower()
    safe_task = quote(str(task_id or "task"), safe="")
    safe_path = quote(relative, safe="/")
    block = {
        "id": f"artifact-{original_digest[:24]}",
        "type": block_type,
        "title": name,
        "text": f"{category} · {_human_size(original_size)}",
        "uri": f"galaxyssi-artifact://{safe_task}/{safe_path}",
        "mime_type": mime_type,
        "fallback_text": relative,
        "metadata": {
            "size": _human_size(original_size),
            "size_bytes": str(original_size),
            "category": category,
            "sha256": original_digest,
        },
    }
    if not inline_artifacts:
        block["metadata"]["transport"] = "encrypted-fragmented"
    inline = _inline_artifact(task_id, relative, mime_type) if inline_artifacts else None
    if inline is not None:
        encoded, inline_mime, transport_size, transport_digest = inline
        block["data_b64"] = encoded
        block["mime_type"] = inline_mime
        block["text"] = f"{category} · {_human_size(transport_size)}"
        block["metadata"].update({
            "size": _human_size(transport_size),
            "size_bytes": str(transport_size),
            "sha256": transport_digest,
            "original_size": _human_size(original_size),
            "original_size_bytes": str(original_size),
            "original_sha256": original_digest,
            "transport": "encrypted-inline",
        })
    return block


def _hydrate_explicit_artifact(
    block: dict,
    task_id: str,
    inline_artifacts: bool = True,
) -> dict | None:
    if str(block.get("type") or "") not in ARTIFACT_TYPES:
        return block
    if str(block.get("type") or "") == "gallery":
        return block

    encoded = str(block.get("data_b64") or "").strip()
    if encoded:
        metadata = dict(block.get("metadata") or {})
        try:
            raw = base64.b64decode(encoded, validate=True)
            if len(raw) > MAX_INLINE_ARTIFACT_BYTES:
                block.pop("data_b64", None)
                return block
            metadata.setdefault("sha256", hashlib.sha256(raw).hexdigest())
            metadata.setdefault("size", _human_size(len(raw)))
            metadata.setdefault("size_bytes", str(len(raw)))
        except (TypeError, ValueError):
            block.pop("data_b64", None)
        if metadata:
            block["metadata"] = metadata
        return block

    uri = str(block.get("uri") or "").strip()
    relative = _artifact_relative_from_uri(uri)
    if relative:
        canonical = _artifact_block(
            {
                "name": block.get("title") or PurePosixPath(relative).name,
                "relative_path": relative,
                "mime_type": block.get("mime_type"),
            },
            task_id,
            inline_artifacts,
        )
        if not canonical:
            return None
        for key in ("title", "text", "fallback_text"):
            value = str(block.get(key) or "").strip()
            if value:
                canonical[key] = value
        metadata = dict(block.get("metadata") or {})
        metadata.update(canonical.get("metadata") or {})
        canonical["metadata"] = metadata
        return canonical

    scheme = urlparse(uri).scheme.lower()
    if scheme in {"http", "https", "content", "android.resource", "data"}:
        return block
    return None


def _dedupe_blocks(blocks) -> list[dict]:
    result: list[dict] = []
    artifact_indexes: dict[str, int] = {}
    for block in blocks:
        if str(block.get("type") or "") not in ARTIFACT_TYPES:
            result.append(block)
            continue
        identity = _block_identity(block)
        if not identity:
            result.append(block)
            continue
        previous_index = artifact_indexes.get(identity)
        if previous_index is None:
            artifact_indexes[identity] = len(result)
            result.append(block)
        elif _block_quality(block) > _block_quality(result[previous_index]):
            result[previous_index] = block
    return result


def _limit_inline_artifact_payload(blocks: list[dict]) -> list[dict]:
    remaining = MAX_TOTAL_INLINE_ARTIFACT_B64
    bounded: list[dict] = []
    for raw in blocks:
        block = dict(raw)
        encoded = str(block.get("data_b64") or "").strip()
        if encoded and len(encoded) > remaining:
            block.pop("data_b64", None)
            metadata = dict(block.get("metadata") or {})
            metadata.pop("transport", None)
            if metadata:
                block["metadata"] = metadata
            else:
                block.pop("metadata", None)
        elif encoded:
            remaining -= len(encoded)
        bounded.append(block)
    return bounded


def _block_identity(block: dict) -> str:
    title = str(block.get("title") or "").strip().casefold()
    fallback = str(block.get("fallback_text") or "").replace("\\", "/").strip().casefold()
    artifact_name = fallback or title
    metadata = block.get("metadata")
    if isinstance(metadata, dict):
        digest = str(metadata.get("sha256") or "").strip().lower()
        if re.fullmatch(r"[0-9a-f]{64}", digest):
            return f"sha256:{digest}:{artifact_name}"
    encoded = str(block.get("data_b64") or "").strip()
    if encoded:
        try:
            digest = hashlib.sha256(base64.b64decode(encoded, validate=True)).hexdigest()
            return f"sha256:{digest}:{artifact_name}"
        except (TypeError, ValueError):
            pass
    uri = unquote(str(block.get("uri") or "")).replace("\\", "/").strip()
    if uri:
        return f"uri:{uri.casefold()}"
    if fallback or title:
        return f"name:{fallback}:{title}"
    return ""


def _block_quality(block: dict) -> int:
    metadata = block.get("metadata")
    return (
        (8 if str(block.get("data_b64") or "").strip() else 0)
        + (4 if str(block.get("mime_type") or "").strip() else 0)
        + (2 if isinstance(metadata, dict) and metadata.get("sha256") else 0)
        + (1 if str(block.get("uri") or "").startswith("galaxyssi-artifact://") else 0)
    )


def _safe_relative_artifact_path(value: str) -> str:
    normalized = unquote(str(value or "").strip().strip("<>")).replace("\\", "/").strip("/")
    candidate = PurePosixPath(normalized)
    if len(candidate.parts) < 2 or any(part in {"", ".", ".."} for part in candidate.parts):
        return ""
    category = candidate.parts[0].lower()
    if category not in ARTIFACT_CATEGORIES:
        return ""
    if category == "downloads" and candidate.parts[1].lower() in {"input", "context"}:
        return ""
    return candidate.as_posix()


def _artifact_relative_from_uri(uri: str) -> str:
    value = str(uri or "").strip()
    if not value:
        return ""
    parsed = urlparse(value)
    if parsed.scheme.lower() == "galaxyssi-artifact":
        return _safe_relative_artifact_path(parsed.path)
    if not parsed.scheme:
        return _safe_relative_artifact_path(value)
    return ""


def _artifact_source(task_id: str, relative: str) -> Path | None:
    safe_relative = _safe_relative_artifact_path(relative)
    if not task_id or not safe_relative:
        return None
    try:
        from task_workspace import task_workspace
        root = task_workspace(task_id).resolve()
        source = (root / Path(*PurePosixPath(safe_relative).parts)).resolve()
        source.relative_to(root)
        if not source.is_file() or source.is_symlink():
            return None
        return source
    except (OSError, ValueError):
        return None


def _file_sha256(source: Path) -> str:
    digest = hashlib.sha256()
    with source.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _inline_artifact(task_id: str, relative: str, mime_type: str) -> tuple[str, str, int, str] | None:
    if not str(mime_type or "").lower().startswith("image/"):
        return None
    try:
        source = _artifact_source(task_id, relative)
        if source is None:
            return None
        raw = source.read_bytes()
        output_mime = mime_type
        if len(raw) > MAX_INLINE_ARTIFACT_BYTES:
            compressed = compress_image_file(source, MAX_INLINE_ARTIFACT_BYTES)
            if compressed is None:
                return None
            raw = compressed.data
            output_mime = compressed.mime_type
        if not raw or len(raw) > MAX_INLINE_ARTIFACT_BYTES:
            return None
        digest = hashlib.sha256(raw).hexdigest()
        return base64.b64encode(raw).decode("ascii"), output_mime, len(raw), digest
    except Exception:
        return None


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


def _guess_mime_type(name: str) -> str:
    suffix = Path(str(name or "")).suffix.lower()
    return (
        MIME_TYPE_OVERRIDES.get(suffix)
        or IMAGE_MIME_TYPES.get(suffix)
        or mimetypes.guess_type(str(name or ""))[0]
        or "application/octet-stream"
    )


def _human_size(size: int) -> str:
    value = max(0, int(size or 0))
    for unit in ("B", "KB", "MB", "GB"):
        if value < 1024 or unit == "GB":
            return f"{value} {unit}" if unit == "B" else f"{value:.1f} {unit}"
        value /= 1024
    return "0 B"
