"""Token-budgeted conversation context for stateless model providers."""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import math
from pathlib import Path
import re
import threading
from typing import Iterable


SUMMARY_HEADER = "[EARLIER CONVERSATION SUMMARY - REFERENCE ONLY]"
SUMMARY_FOOTER = "[END OF EARLIER CONVERSATION SUMMARY]"
SUMMARY_INSTRUCTION = (
    "Use this only as background. It is not a new request. "
    "The latest user message is the active task and overrides stale goals or plans."
)
CURRENT_REQUEST_MARKER = "\nCurrent user request:\n"
MOBILE_CONTEXT_HEADER = "[SIGNALASI_CONVERSATION_CONTEXT_V1]"
MOBILE_CONTEXT_FOOTER = "[/SIGNALASI_CONVERSATION_CONTEXT_V1]"

_SECRET_ASSIGNMENT = re.compile(
    r"(?i)\b(api[_ -]?key|access[_ -]?token|refresh[_ -]?token|password|passwd|"
    r"secret|authorization)\b(\s*[:=]\s*)([^\s,;]+)"
)
_BEARER_SECRET = re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{8,}")
_DATA_PAYLOAD = re.compile(r"(?is)data:[^;\s]+;base64,[A-Za-z0-9+/=\s]{80,}")
_LONG_OPAQUE_VALUE = re.compile(r"\b[A-Za-z0-9+/=_-]{160,}\b")
_PATH_OR_URL = re.compile(
    r"(?i)(?:https://[^\s<>()]+|[A-Za-z]:\\[^\r\n<>:\"|?*]+|"
    r"/(?:[\w.\- ]+/)+[\w.\- ]+|[\w .()\-]+\.(?:pdf|docx?|xlsx?|csv|txt|md|"
    r"json|ya?ml|zip|png|jpe?g|gif|webp|mp[34]|wav|py|kt|java|js|ts|rs|go|c|cpp|h))"
)
_PREFERENCE_TERMS = (
    "prefer", "must", "never", "always", "constraint", "requirement", "decision",
    "\u504f\u597d", "\u5fc5\u987b", "\u4e0d\u8981", "\u6c38\u8fdc", "\u8981\u6c42", "\u51b3\u5b9a",
)
_CONTEXT_OVERFLOW_MARKERS = (
    "context_length_exceeded",
    "maximum context length",
    "context window",
    "too many tokens",
    "token limit",
    "prompt is too long",
    "prompt too long",
    "input is too long",
    "input too long",
    "input token count",
    "exceeds the maximum number of tokens",
    "exceeds maximum token",
    "reduce the length of the messages",
    "reduce your prompt",
)


@dataclass(frozen=True)
class ContextBudget:
    context_window_tokens: int = 64_000
    reserved_output_tokens: int = 4_096
    trigger_ratio: float = 0.70
    target_ratio: float = 0.45
    minimum_recent_groups: int = 4
    maximum_summary_tokens: int = 4_096
    maximum_message_characters: int = 16_000

    def __post_init__(self) -> None:
        if self.context_window_tokens < 4_096:
            raise ValueError("context_window_tokens must be at least 4096")
        if not 0 <= self.reserved_output_tokens < self.context_window_tokens:
            raise ValueError("reserved_output_tokens must fit the context window")
        if not 0.25 <= self.trigger_ratio <= 0.95:
            raise ValueError("trigger_ratio must be between 0.25 and 0.95")
        if not 0.20 <= self.target_ratio <= self.trigger_ratio:
            raise ValueError("target_ratio must not exceed trigger_ratio")
        if self.minimum_recent_groups <= 0:
            raise ValueError("minimum_recent_groups must be positive")

    @property
    def input_budget_tokens(self) -> int:
        return max(2_048, self.context_window_tokens - self.reserved_output_tokens)


def is_context_overflow(status_code: int, detail: str) -> bool:
    if int(status_code or 0) not in {400, 413, 422}:
        return False
    normalized = str(detail or "").lower()
    if any(marker in normalized for marker in _CONTEXT_OVERFLOW_MARKERS):
        return True
    return int(status_code or 0) == 413 and (
        "request too large" in normalized or "payload too large" in normalized
    )


def retry_context_windows(
    configured_window_tokens: int,
    *,
    minimum_window_tokens: int = 4_096,
    maximum_attempts: int = 4,
) -> tuple[int, ...]:
    minimum = max(4_096, int(minimum_window_tokens or 4_096))
    candidate = max(minimum, int(configured_window_tokens or minimum))
    result: list[int] = []
    for _ in range(max(1, int(maximum_attempts or 1))):
        if candidate not in result:
            result.append(candidate)
        candidate = max(minimum, candidate // 2)
    return tuple(result)


@dataclass(frozen=True)
class ContextMessage:
    role: str
    content: str
    message_id: str = ""
    group_id: str = ""


@dataclass(frozen=True)
class MobileConversationContext:
    conversation_id: str = ""
    summary: str = ""
    global_context: str = ""
    messages: tuple[ContextMessage, ...] = ()
    turn_ids: frozenset[str] = frozenset()
    entry_ids: frozenset[str] = frozenset()
    summary_digest: str = ""

    @property
    def reference_summary(self) -> str:
        return "\n".join(
            value
            for value in (self.summary.strip(), self.global_context.strip())
            if value
        )

    @property
    def present(self) -> bool:
        return bool(
            self.conversation_id
            or self.summary
            or self.global_context
            or self.messages
        )

    def delta(
        self,
        *,
        synced_turn_ids: Iterable[str] = (),
        synced_entry_ids: Iterable[str] = (),
    ) -> tuple[ContextMessage, ...]:
        known_turns = {str(value or "") for value in synced_turn_ids if str(value or "")}
        known_entries = {str(value or "") for value in synced_entry_ids if str(value or "")}
        return tuple(
            item
            for item in self.messages
            if item.message_id not in known_entries
            and item.group_id not in known_turns
        )


def embedded_mobile_context(prompt: str) -> MobileConversationContext:
    value = str(prompt or "")
    start = value.find(MOBILE_CONTEXT_HEADER)
    if start < 0:
        return MobileConversationContext()
    start += len(MOBILE_CONTEXT_HEADER)
    end = value.find(MOBILE_CONTEXT_FOOTER, start)
    if end < 0:
        return MobileConversationContext()
    raw = value[start:end].strip()
    try:
        payload = json.loads(raw)
    except (json.JSONDecodeError, TypeError, ValueError):
        return MobileConversationContext()
    if not isinstance(payload, dict):
        return MobileConversationContext()
    try:
        version = int(payload.get("version") or 0)
    except (TypeError, ValueError):
        return MobileConversationContext()
    if version != 1:
        return MobileConversationContext()

    messages: list[ContextMessage] = []
    turn_ids: set[str] = set()
    entry_ids: set[str] = set()
    turns = payload.get("turns")
    if isinstance(turns, list):
        for index, item in enumerate(turns):
            if not isinstance(item, dict):
                continue
            role = str(item.get("role") or "").strip().lower()
            if role not in {"user", "assistant"}:
                continue
            content = _sanitize(str(item.get("content") or ""), 32_000)
            if not content:
                continue
            entry_id = str(item.get("entry_id") or "").strip()[:240]
            turn_id = str(item.get("turn_id") or "").strip()[:240]
            if entry_id:
                entry_ids.add(entry_id)
            if turn_id:
                turn_ids.add(turn_id)
            messages.append(
                ContextMessage(
                    role=role,
                    content=content,
                    message_id=entry_id or f"mobile:{index}",
                    group_id=turn_id or entry_id or f"mobile:{index}",
                )
            )

    summary = _sanitize(str(payload.get("summary") or ""), 32_000)
    global_context = _sanitize(str(payload.get("global_context") or ""), 32_000)
    summary_digest = hashlib.sha256(
        "\n".join((summary, global_context)).encode("utf-8")
    ).hexdigest() if summary or global_context else ""
    return MobileConversationContext(
        conversation_id=str(payload.get("conversation_id") or "").strip()[:240],
        summary=summary,
        global_context=global_context,
        messages=tuple(messages),
        turn_ids=frozenset(turn_ids),
        entry_ids=frozenset(entry_ids),
        summary_digest=summary_digest,
    )


@dataclass(frozen=True)
class CompiledContext:
    summary: str
    messages: tuple[ContextMessage, ...]
    original_estimated_tokens: int
    compacted_estimated_tokens: int
    compacted: bool
    compacted_group_count: int
    compacted_message_ids: frozenset[str] = frozenset()
    compacted_group_ids: frozenset[str] = frozenset()
    compacted_messages: tuple[ContextMessage, ...] = ()

    def wire_messages(self, system_prompt: str = "") -> list[dict[str, str]]:
        result: list[dict[str, str]] = []
        system_parts = [str(system_prompt or "").strip()]
        reference = reference_block(self.summary)
        if reference:
            system_parts.append(reference)
        system_text = "\n\n".join(part for part in system_parts if part)
        if system_text:
            result.append({"role": "system", "content": system_text})
        result.extend(
            {"role": item.role, "content": item.content}
            for item in self.messages
            if item.role != "system"
        )
        return result


def estimate_tokens(value: str) -> int:
    text = str(value or "")
    if not text:
        return 0
    cjk = sum(
        1
        for character in text
        if "\u3400" <= character <= "\u9fff"
        or "\u3040" <= character <= "\u30ff"
        or "\uac00" <= character <= "\ud7af"
    )
    return math.ceil(cjk * 1.15 + (len(text) - cjk) / 4.0) + 6


def compile_context(
    messages: Iterable[ContextMessage],
    *,
    previous_summary: str = "",
    fixed_prompt: str = "",
    budget: ContextBudget | None = None,
) -> CompiledContext:
    policy = budget or ContextBudget()
    normalized = tuple(
        item.__class__(
            role=item.role,
            content=clean,
            message_id=item.message_id,
            group_id=item.group_id,
        )
        for item in messages
        if (clean := _sanitize(item.content, policy.maximum_message_characters))
    )
    system = tuple(item for item in normalized if item.role == "system")
    dialogue = tuple(item for item in normalized if item.role != "system")
    groups = _groups(dialogue)
    prior = _normalize_previous_summary(previous_summary, policy.maximum_summary_tokens)
    fixed_tokens = estimate_tokens(fixed_prompt) + sum(_message_tokens(item) for item in system)
    original_tokens = fixed_tokens + estimate_tokens(prior) + sum(
        _message_tokens(item) for item in dialogue
    )
    trigger_tokens = int(policy.input_budget_tokens * policy.trigger_ratio)
    if original_tokens <= trigger_tokens:
        return CompiledContext(
            prior,
            normalized,
            original_tokens,
            original_tokens,
            False,
            0,
            frozenset(),
            frozenset(),
            (),
        )

    target_tokens = int(policy.input_budget_tokens * policy.target_ratio)
    summary_allowance = min(
        policy.maximum_summary_tokens,
        int(policy.input_budget_tokens * 0.20),
    )
    recent_allowance = max(512, target_tokens - fixed_tokens - summary_allowance)
    retained: set[str] = set()
    retained_tokens = 0
    retained_groups = 0
    for key, items in reversed(groups):
        group_tokens = sum(_message_tokens(item) for item in items)
        must_keep = retained_groups < policy.minimum_recent_groups
        if must_keep or retained_tokens + group_tokens <= recent_allowance:
            retained.add(key)
            retained_tokens += group_tokens
            retained_groups += 1
            continue
        # Durable cursors can only advance across a contiguous compacted prefix.
        # Stopping here keeps the retained region as one complete recent suffix.
        break
    if not retained and groups:
        retained.add(groups[-1][0])

    older = tuple(item for key, items in groups if key not in retained for item in items)
    recent = tuple(item for key, items in groups if key in retained for item in items)
    summary = _summarize(older, prior, summary_allowance)
    compiled_messages = system + recent
    compacted_tokens = fixed_tokens + estimate_tokens(summary) + sum(
        _message_tokens(item) for item in recent
    )
    return CompiledContext(
        summary,
        compiled_messages,
        original_tokens,
        compacted_tokens,
        bool(older),
        len(groups) - len(retained),
        frozenset(item.message_id for item in older if item.message_id),
        frozenset(key for key, _items in groups if key not in retained),
        older,
    )


def task_history_messages(
    history: Iterable[dict],
    current_prompt: str,
    *,
    current_task_id: str = "",
    after_cursor: tuple[int, str] = (0, ""),
) -> list[ContextMessage]:
    messages: list[ContextMessage] = []
    for item in history:
        task_id = str(item.get("task_id") or "")
        if current_task_id and task_id == current_task_id:
            continue
        if history_cursor(item) <= after_cursor:
            continue
        prompt = current_request(str(item.get("prompt") or ""))
        if not prompt:
            continue
        group_id = str(item.get("client_turn_id") or "").strip()
        group_id = group_id or task_id or f"history:{len(messages)}"
        messages.append(ContextMessage("user", prompt, f"{group_id}:user", group_id))
        result = str(item.get("result") or "").strip()
        if result and str(item.get("status") or "") == "completed":
            messages.append(ContextMessage("assistant", result, f"{group_id}:assistant", group_id))
    current = current_request(current_prompt)
    if current:
        messages.append(ContextMessage("user", current, "current:user", "current"))
    return messages


def merge_context_messages(
    *groups: Iterable[ContextMessage],
) -> list[ContextMessage]:
    result: list[ContextMessage] = []
    seen_message_ids: set[tuple[str, str]] = set()
    seen_turn_roles: set[tuple[str, str]] = set()
    for group in groups:
        for item in group:
            role = str(item.role or "").strip().lower()
            content = str(item.content or "").strip()
            message_key = (role, str(item.message_id or "").strip())
            turn_key = (role, str(item.group_id or "").strip())
            if not role or not content:
                continue
            if message_key[1] and message_key in seen_message_ids:
                continue
            if turn_key[1] and turn_key in seen_turn_roles:
                continue
            if message_key[1]:
                seen_message_ids.add(message_key)
            if turn_key[1]:
                seen_turn_roles.add(turn_key)
            result.append(item)
    return result


def history_cursor(item: dict) -> tuple[int, str]:
    return (
        max(0, int(item.get("created_at") or 0)),
        str(item.get("task_id") or ""),
    )


def compacted_history_cursor(
    history: Iterable[dict],
    compacted_group_ids: Iterable[str],
    previous: tuple[int, str] = (0, ""),
) -> tuple[int, str]:
    compacted = {str(value or "") for value in compacted_group_ids if str(value or "")}
    return max(
        (history_cursor(item) for item in history if str(item.get("task_id") or "") in compacted),
        default=previous,
    )


def render_prompt(compiled: CompiledContext, current_prompt: str, *, preamble: str = "") -> str:
    parts = [str(preamble or "").strip()]
    reference = reference_block(compiled.summary)
    if reference:
        parts.append(reference)
    recent = [
        f"{'User' if item.role == 'user' else 'Assistant'}: {item.content}"
        for item in compiled.messages
        if item.role in {"user", "assistant"} and item.message_id != "current:user"
    ]
    if recent:
        parts.append("Recent conversation turns:\n" + "\n".join(recent))
    parts.append(f"Current user request:\n{current_request(current_prompt)}")
    return "\n\n".join(part for part in parts if part)


def current_request(prompt: str) -> str:
    value = str(prompt or "").strip()
    if value.lower().startswith("current user request:"):
        value = value.split(":", 1)[1].strip()
    elif CURRENT_REQUEST_MARKER in value:
        value = value.rsplit(CURRENT_REQUEST_MARKER, 1)[1].strip()
    for marker in ("\n\nAttached input:\n", "\n\nSignalASI can render optional rich output."):
        if marker in value:
            value = value.split(marker, 1)[0].strip()
    return value


def reference_block(summary: str) -> str:
    clean = str(summary or "").strip()
    if not clean:
        return ""
    return f"{SUMMARY_HEADER}\n{SUMMARY_INSTRUCTION}\n{clean}\n{SUMMARY_FOOTER}"


@dataclass(frozen=True)
class ConversationSummaryState:
    summary: str = ""
    through_created_at: int = 0
    through_task_id: str = ""

    @property
    def cursor(self) -> tuple[int, str]:
        return (self.through_created_at, self.through_task_id)


class ConversationSummaryStore:
    def __init__(self, path: Path | None = None) -> None:
        self.path = path or (Path.home() / ".signalasi" / "conversation_context_summaries.json")
        self._lock = threading.RLock()

    def get(self, conversation_id: str) -> str:
        return self.state(conversation_id).summary

    def state(self, conversation_id: str) -> ConversationSummaryState:
        key = str(conversation_id or "").strip()
        if not key:
            return ConversationSummaryState()
        with self._lock:
            item = self._load().get(key) or {}
            return ConversationSummaryState(
                summary=str(item.get("summary") or ""),
                through_created_at=max(0, int(item.get("through_created_at") or 0)),
                through_task_id=str(item.get("through_task_id") or ""),
            )

    def put(
        self,
        conversation_id: str,
        summary: str,
        *,
        through_created_at: int = 0,
        through_task_id: str = "",
    ) -> None:
        key = str(conversation_id or "").strip()
        clean = _sanitize(summary, 32_000)
        if not key or not clean:
            return
        with self._lock:
            state = self._load()
            previous = state.get(key) or {}
            cursor = max(
                (
                    max(0, int(previous.get("through_created_at") or 0)),
                    str(previous.get("through_task_id") or ""),
                ),
                (max(0, int(through_created_at or 0)), str(through_task_id or "")),
            )
            state[key] = {
                "summary": clean,
                "through_created_at": cursor[0],
                "through_task_id": cursor[1],
            }
            self._save(state)

    def delete(self, conversation_id: str) -> bool:
        key = str(conversation_id or "").strip()
        if not key:
            return False
        with self._lock:
            state = self._load()
            if key not in state:
                return False
            state.pop(key, None)
            self._save(state)
            return True

    def delete_conversation(self, conversation_id: str) -> bool:
        clean = str(conversation_id or "").strip()
        if not clean:
            return False
        with self._lock:
            state = self._load()
            keys = [
                key
                for key in state
                if key == clean or key.endswith(f":{clean}")
            ]
            if not keys:
                return False
            for key in keys:
                state.pop(key, None)
            self._save(state)
            return True

    def _load(self) -> dict[str, dict]:
        try:
            value = json.loads(self.path.read_text(encoding="utf-8"))
            if isinstance(value, dict):
                result: dict[str, dict] = {}
                for key, item in value.items():
                    clean_key = str(key).strip()
                    if not clean_key:
                        continue
                    if isinstance(item, str):
                        if item.strip():
                            result[clean_key] = {"summary": item}
                    elif isinstance(item, dict) and str(item.get("summary") or "").strip():
                        result[clean_key] = dict(item)
                return result
        except (OSError, ValueError, TypeError):
            pass
        return {}

    def _save(self, value: dict[str, dict]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(self.path.suffix + ".tmp")
        temporary.write_text(
            json.dumps(value, ensure_ascii=True, sort_keys=True),
            encoding="utf-8",
        )
        temporary.replace(self.path)


_summary_store: ConversationSummaryStore | None = None
_summary_store_lock = threading.Lock()


def conversation_summary_store() -> ConversationSummaryStore:
    global _summary_store
    with _summary_store_lock:
        if _summary_store is None:
            _summary_store = ConversationSummaryStore()
        return _summary_store


def _groups(messages: tuple[ContextMessage, ...]) -> list[tuple[str, tuple[ContextMessage, ...]]]:
    result: list[tuple[str, tuple[ContextMessage, ...]]] = []
    active_generated = ""
    generated = 0
    for item in messages:
        key = item.group_id.strip()
        if not key:
            if item.role == "user" or not active_generated:
                generated += 1
                active_generated = f"turn:{generated}"
            key = active_generated
        if result and result[-1][0] == key:
            result[-1] = (key, result[-1][1] + (item,))
        else:
            result.append((key, (item,)))
    return result


def _summarize(
    older: tuple[ContextMessage, ...],
    previous_summary: str,
    maximum_tokens: int,
) -> str:
    if not older:
        return previous_summary
    prior = _last_within_token_budget(
        _summary_bullets(previous_summary),
        max(64, maximum_tokens // 4),
    )
    if not prior and str(previous_summary or "").strip():
        prior = [_one_line(previous_summary, 800)]
    goals = _last_within_token_budget(
        (_one_line(item.content, 360) for item in older if item.role == "user"),
        max(64, maximum_tokens // 4),
    )
    outcomes = _last_within_token_budget(
        (_one_line(item.content, 420) for item in older if item.role == "assistant"),
        max(64, maximum_tokens // 3),
    )
    constraints = _last_within_token_budget(
        (
            _one_line(item.content, 320)
            for item in older
            if any(term.lower() in item.content.lower() for term in _PREFERENCE_TERMS)
        ),
        max(64, maximum_tokens // 5),
    )
    artifacts = _last_within_token_budget(
        (
            match.group(0).rstrip(".,;:)")
            for item in older
            for match in _PATH_OR_URL.finditer(item.content)
        ),
        max(64, maximum_tokens // 6),
    )
    sections = (
        ("Prior durable facts", prior),
        ("Earlier user goals", goals),
        ("Verified outcomes and decisions", outcomes),
        ("Preferences and constraints", constraints),
        ("Referenced files, artifacts, and URLs", artifacts),
    )
    lines: list[str] = []
    for title, items in sections:
        if not items:
            continue
        lines.append(f"{title}:")
        lines.extend(f"- {item}" for item in items)
    return _fit_text_to_token_budget("\n".join(lines), maximum_tokens)


def _normalize_previous_summary(summary: str, maximum_tokens: int) -> str:
    value = str(summary or "").replace(SUMMARY_HEADER, "").replace(SUMMARY_FOOTER, "").strip()
    return _sanitize(value, max(1_000, maximum_tokens * 4))


def _summary_bullets(summary: str) -> list[str]:
    return _first_unique(
        (
            _one_line(line.strip()[2:], 420)
            for line in str(summary or "").splitlines()
            if line.strip().startswith("- ")
        ),
        100_000,
    )


def _last_within_token_budget(values: Iterable[str], maximum_tokens: int) -> list[str]:
    items = _first_unique(values, 100_000)
    retained: list[str] = []
    used_tokens = 0
    for value in reversed(items):
        tokens = estimate_tokens(value) + 2
        if not retained or used_tokens + tokens <= maximum_tokens:
            retained.append(value)
            used_tokens += tokens
    retained.reverse()
    return retained


def _first_unique(values: Iterable[str], limit: int) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        clean = str(value or "").strip()
        key = clean.casefold()
        if not clean or key in seen:
            continue
        seen.add(key)
        result.append(clean)
        if len(result) >= limit:
            break
    return result


def _message_tokens(item: ContextMessage) -> int:
    return estimate_tokens(item.content) + 4


def _one_line(value: str, limit: int) -> str:
    clean = re.sub(r"\s+", " ", str(value or "")).strip()
    return clean if len(clean) <= limit else clean[: max(1, limit - 1)] + "\u2026"


def _sanitize(value: str, maximum_characters: int) -> str:
    clean = _DATA_PAYLOAD.sub("[embedded payload removed]", str(value or ""))
    clean = _SECRET_ASSIGNMENT.sub(lambda match: f"{match.group(1)}{match.group(2)}[redacted]", clean)
    clean = _BEARER_SECRET.sub("Bearer [redacted]", clean)
    clean = _LONG_OPAQUE_VALUE.sub("[opaque payload removed]", clean).strip()
    if len(clean) <= maximum_characters:
        return clean
    marker = "\n...[content compacted]...\n"
    remaining = max(2, maximum_characters - len(marker))
    head = remaining * 2 // 3
    return clean[:head] + marker + clean[-(remaining - head):]


def _fit_text_to_token_budget(value: str, maximum_tokens: int) -> str:
    clean = _sanitize(value, max(1_000, len(str(value or ""))))
    if maximum_tokens <= 0 or not clean:
        return ""
    if estimate_tokens(clean) <= maximum_tokens:
        return clean
    marker = "\n...[content compacted]...\n"
    low = 1
    high = len(clean)
    best = ""
    while low <= high:
        retained = (low + high) // 2
        head = retained * 2 // 3
        candidate = clean[:head] + marker + clean[-(retained - head):]
        if estimate_tokens(candidate) <= maximum_tokens:
            best = candidate
            low = retained + 1
        else:
            high = retained - 1
    return best or clean[:1]
