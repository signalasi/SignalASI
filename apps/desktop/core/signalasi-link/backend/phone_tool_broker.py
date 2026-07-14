"""Transport-neutral broker for phone-owned tool calls.

The broker only correlates logical envelopes carried by an already encrypted
SignalASI Link (or another authenticated transport). It intentionally has no
tool executor and accepts no Android credentials or permission grant objects.
"""
from __future__ import annotations

import copy
import hashlib
import json
import re
import threading
import time
import uuid
from collections import OrderedDict
from dataclasses import dataclass, field
from typing import Any, Callable, Iterable, Mapping


PROTOCOL_NAME = "signalasi.phone-native-tool-session"
PROTOCOL_VERSION = 1
REQUEST_TYPE = "phone_tool_request"
RESPONSE_TYPE = "phone_tool_response"
CANCEL_TYPE = "phone_tool_cancel"

DEFAULT_TIMEOUT_MS = 30_000
DEFAULT_CONTROL_TTL_MS = 10_000
DEFAULT_MAX_RESULT_BYTES = 64 * 1024
DEFAULT_REPLAY_CAPACITY = 4096
MAX_ENVELOPE_BYTES = 512 * 1024
MAX_CLOCK_SKEW_MS = 5 * 60 * 1000
MAX_ID_CHARS = 160
MAX_APPROVAL_HANDLE_CHARS = 512
MAX_TOOL_ID_CHARS = 256
MAX_CANCEL_REASON_CHARS = 512

TERMINAL_STATUSES = {
    "succeeded",
    "failed",
    "verification_failed",
    "rejected",
    "unavailable",
    "cancelled",
    "timed_out",
}

_MANIFEST_HASH_RE = re.compile(r"^(?:sha256:)?[0-9a-fA-F]{64}$")
_AUTH_VALUE_RE = re.compile(r"(?i)\b(bearer|basic)\s+[^\s,;]+")
_PRIVATE_KEY_RE = re.compile(
    r"-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?-----END [A-Z0-9 ]*PRIVATE KEY-----",
    re.DOTALL,
)
_SENSITIVE_KEYS = {
    "access_token",
    "accessibility_handle",
    "android_credential",
    "android_credentials",
    "android_permission_token",
    "api_key",
    "authorization",
    "cookie",
    "credential",
    "credentials",
    "media_projection_data",
    "media_projection_grant",
    "media_projection_token",
    "notification_listener_handle",
    "password",
    "permission_grant",
    "permission_token",
    "refresh_token",
    "remote_input_handle",
    "secret",
    "token",
}
_SENSITIVE_SUFFIXES = (
    "_access_token",
    "_api_key",
    "_auth_token",
    "_credential",
    "_credentials",
    "_password",
    "_private_key",
    "_refresh_token",
    "_secret",
)

EventCallback = Callable[[dict[str, Any]], None]
SendEnvelope = Callable[[dict[str, Any]], None]
Clock = Callable[[], int]


class PhoneToolBrokerError(RuntimeError):
    """Base class for broker contract failures."""


class EnvelopeValidationError(PhoneToolBrokerError, ValueError):
    """Raised when an envelope violates the broker contract."""


class ReplayRejectedError(PhoneToolBrokerError):
    """Raised when a duplicate or stale call/message is observed."""


class UnknownCallError(PhoneToolBrokerError, KeyError):
    """Raised when a response cannot be correlated to a pending call."""


class PhoneToolTimeoutError(PhoneToolBrokerError, TimeoutError):
    """Raised when a pending phone call reaches its deadline."""


class PhoneToolCancelledError(PhoneToolBrokerError):
    """Raised when a pending phone call is cancelled by the Desktop."""


class BrokerDispatchError(PhoneToolBrokerError):
    """Raised when the configured transport rejects an outbound envelope."""


@dataclass
class _PendingCall:
    request: dict[str, Any]
    deadline_ms: int
    completion: threading.Event = field(default_factory=threading.Event)
    response: dict[str, Any] | None = None
    error: BaseException | None = None


@dataclass(frozen=True)
class _TerminalCall:
    response: dict[str, Any] | None = None
    error: BaseException | None = None


def _epoch_ms() -> int:
    return int(time.time() * 1000)


def _canonical_json(value: Any) -> str:
    try:
        return json.dumps(
            value,
            ensure_ascii=False,
            allow_nan=False,
            separators=(",", ":"),
            sort_keys=True,
        )
    except (TypeError, ValueError, RecursionError) as exc:
        raise EnvelopeValidationError("value must be bounded JSON") from exc


def _json_size(value: Any) -> int:
    return len(_canonical_json(value).encode("utf-8"))


def _clone_json(value: dict[str, Any]) -> dict[str, Any]:
    return copy.deepcopy(value)


def compute_manifest_hash(manifest: Any) -> str:
    """Return the canonical SHA-256 digest used to pin a phone manifest."""
    return hashlib.sha256(_canonical_json(manifest).encode("utf-8")).hexdigest()


def _normalized_key(value: object) -> str:
    return re.sub(r"[^a-z0-9]+", "_", str(value).strip().lower()).strip("_")


def _is_sensitive_key(value: object) -> bool:
    key = _normalized_key(value)
    return key in _SENSITIVE_KEYS or key.endswith(_SENSITIVE_SUFFIXES)


def _credential_path(value: Any, path: str = "payload") -> str:
    if isinstance(value, Mapping):
        for key, child in value.items():
            child_path = f"{path}.{key}"
            if _is_sensitive_key(key):
                return child_path
            found = _credential_path(child, child_path)
            if found:
                return found
    elif isinstance(value, (list, tuple)):
        for index, child in enumerate(value):
            found = _credential_path(child, f"{path}[{index}]")
            if found:
                return found
    return ""


def _redact_value(value: Any, depth: int = 0) -> Any:
    if depth > 32:
        return "[REDACTED: depth limit]"
    if isinstance(value, Mapping):
        return {
            str(key): "[REDACTED]" if _is_sensitive_key(key) else _redact_value(child, depth + 1)
            for key, child in value.items()
        }
    if isinstance(value, (list, tuple)):
        return [_redact_value(child, depth + 1) for child in value]
    if isinstance(value, str):
        redacted = _AUTH_VALUE_RE.sub(lambda match: f"{match.group(1)} [REDACTED]", value)
        return _PRIVATE_KEY_RE.sub("[REDACTED PRIVATE KEY]", redacted)
    return value


def redact_and_bound_result(payload: Mapping[str, Any], max_bytes: int = DEFAULT_MAX_RESULT_BYTES) -> dict[str, Any]:
    """Redact secret-shaped fields and return a JSON result within ``max_bytes``."""
    if not isinstance(payload, Mapping):
        raise EnvelopeValidationError("response payload must be an object")
    if max_bytes < 64:
        raise ValueError("max result bytes must be at least 64")

    redacted = _redact_value(payload)
    if _json_size(redacted) <= max_bytes:
        return redacted

    status = str(redacted.get("status") or "failed")
    original_size = _json_size(redacted)
    candidates = [
        {
            "status": status,
            "result": {"truncated": True, "original_size_bytes": original_size},
        },
        {"status": status, "result": {"truncated": True}},
    ]
    for candidate in candidates:
        if _json_size(candidate) <= max_bytes:
            return candidate
    raise ValueError("max result bytes is too small for a terminal result")


def _validate_identifier(
    name: str,
    value: object,
    *,
    required: bool = True,
    max_chars: int = MAX_ID_CHARS,
) -> str:
    if not isinstance(value, str):
        raise EnvelopeValidationError(f"{name} must be a string")
    if required and not value:
        raise EnvelopeValidationError(f"{name} is required")
    if len(value) > max_chars or any(ord(char) < 0x20 for char in value):
        raise EnvelopeValidationError(f"invalid {name}")
    return value


def _validate_manifest_hash(value: object) -> str:
    if not isinstance(value, str) or not _MANIFEST_HASH_RE.fullmatch(value):
        raise EnvelopeValidationError("manifest_hash must be a SHA-256 digest")
    return value.removeprefix("sha256:").lower()


def _validate_uuid(name: str, value: object) -> str:
    try:
        return str(uuid.UUID(str(value)))
    except (ValueError, TypeError, AttributeError) as exc:
        raise EnvelopeValidationError(f"invalid {name}") from exc


def validate_phone_tool_envelope(
    envelope: object,
    *,
    now_ms: int | None = None,
    expected_type: str | None = None,
    max_envelope_bytes: int = MAX_ENVELOPE_BYTES,
) -> dict[str, Any]:
    """Validate a logical broker envelope without decrypting or executing it."""
    if not isinstance(envelope, dict):
        raise EnvelopeValidationError("envelope must be an object")
    version = envelope.get("version")
    if (
        envelope.get("protocol") != PROTOCOL_NAME
        or isinstance(version, bool)
        or not isinstance(version, int)
        or version != PROTOCOL_VERSION
    ):
        raise EnvelopeValidationError("unsupported phone tool protocol")

    envelope_type = envelope.get("type")
    if envelope_type not in {REQUEST_TYPE, RESPONSE_TYPE, CANCEL_TYPE}:
        raise EnvelopeValidationError("unsupported phone tool envelope type")
    if expected_type is not None and envelope_type != expected_type:
        raise EnvelopeValidationError(f"expected {expected_type} envelope")

    _validate_uuid("message_id", envelope.get("message_id"))
    for name in ("session_id", "task_id", "turn_id", "tool_call_id"):
        _validate_identifier(name, envelope.get(name))
    _validate_identifier("parent_tool_call_id", envelope.get("parent_tool_call_id", ""), required=False)
    _validate_manifest_hash(envelope.get("manifest_hash"))
    approval_handle = _validate_identifier(
        "approval_handle",
        envelope.get("approval_handle", ""),
        required=False,
        max_chars=MAX_APPROVAL_HANDLE_CHARS,
    )

    sequence = envelope.get("sequence")
    if isinstance(sequence, bool) or not isinstance(sequence, int) or sequence <= 0:
        raise EnvelopeValidationError("sequence must be a positive integer")
    sent_at = envelope.get("sent_at")
    expires_at = envelope.get("expires_at")
    if (
        isinstance(sent_at, bool)
        or not isinstance(sent_at, int)
        or isinstance(expires_at, bool)
        or not isinstance(expires_at, int)
    ):
        raise EnvelopeValidationError("invalid envelope timestamps")
    reference = int(_epoch_ms() if now_ms is None else now_ms)
    if sent_at <= 0 or sent_at - reference > MAX_CLOCK_SKEW_MS:
        raise EnvelopeValidationError("message timestamp is in the future")
    if expires_at <= sent_at:
        raise EnvelopeValidationError("expires_at must follow sent_at")
    if reference >= expires_at:
        raise EnvelopeValidationError("message expired")

    payload = envelope.get("payload")
    if not isinstance(payload, dict):
        raise EnvelopeValidationError("payload must be an object")
    if envelope_type == REQUEST_TYPE:
        tool_id = payload.get("tool_id")
        if not isinstance(tool_id, str) or not tool_id or len(tool_id) > MAX_TOOL_ID_CHARS:
            raise EnvelopeValidationError("request tool_id is invalid")
        if not isinstance(payload.get("arguments"), dict):
            raise EnvelopeValidationError("request arguments must be an object")
        forbidden = _credential_path(payload)
        if forbidden:
            raise EnvelopeValidationError(f"Android credentials are forbidden at {forbidden}")
    elif envelope_type == RESPONSE_TYPE:
        if payload.get("status") not in TERMINAL_STATUSES:
            raise EnvelopeValidationError("response status is not terminal")
    else:
        reason = payload.get("reason", "")
        if not isinstance(reason, str) or len(reason) > MAX_CANCEL_REASON_CHARS:
            raise EnvelopeValidationError("cancel reason is invalid")
        forbidden = _credential_path(payload)
        if forbidden:
            raise EnvelopeValidationError(f"Android credentials are forbidden at {forbidden}")

    if _json_size(envelope) > max_envelope_bytes:
        raise EnvelopeValidationError("envelope exceeds size limit")
    return envelope


def make_request_envelope(
    *,
    session_id: str,
    task_id: str,
    turn_id: str,
    call_id: str,
    manifest_hash: str,
    sequence: int,
    tool_id: str,
    arguments: Mapping[str, Any],
    parent_call_id: str = "",
    approval_handle: str = "",
    message_id: str = "",
    now_ms: int | None = None,
    expires_at: int | None = None,
    timeout_ms: int = DEFAULT_TIMEOUT_MS,
) -> dict[str, Any]:
    now = int(_epoch_ms() if now_ms is None else now_ms)
    if timeout_ms <= 0:
        raise ValueError("timeout_ms must be positive")
    envelope = {
        "protocol": PROTOCOL_NAME,
        "version": PROTOCOL_VERSION,
        "type": REQUEST_TYPE,
        "message_id": message_id or str(uuid.uuid4()),
        "session_id": session_id,
        "task_id": task_id,
        "turn_id": turn_id,
        "tool_call_id": call_id,
        "parent_tool_call_id": parent_call_id,
        "manifest_hash": _validate_manifest_hash(manifest_hash),
        "sequence": sequence,
        "sent_at": now,
        "expires_at": int(expires_at if expires_at is not None else now + timeout_ms),
        "approval_handle": approval_handle,
        "payload": {"tool_id": tool_id, "arguments": dict(arguments)},
    }
    return validate_phone_tool_envelope(envelope, now_ms=now)


def make_response_envelope(
    request: Mapping[str, Any],
    *,
    status: str,
    result: Any = None,
    error: Any = None,
    sequence: int | None = None,
    message_id: str = "",
    now_ms: int | None = None,
    expires_at: int | None = None,
    max_result_bytes: int = DEFAULT_MAX_RESULT_BYTES,
) -> dict[str, Any]:
    request_dict = dict(request)
    request_reference = int(request_dict.get("sent_at") or 0)
    validate_phone_tool_envelope(request_dict, now_ms=request_reference, expected_type=REQUEST_TYPE)
    now = int(_epoch_ms() if now_ms is None else now_ms)
    payload = redact_and_bound_result(
        {"status": status, "result": result, "error": error}, max_result_bytes
    )
    response_expiry = int(
        expires_at
        if expires_at is not None
        else max(now + 1, int(request_dict["expires_at"]))
    )
    envelope = {
        "protocol": PROTOCOL_NAME,
        "version": PROTOCOL_VERSION,
        "type": RESPONSE_TYPE,
        "message_id": message_id or str(uuid.uuid4()),
        "session_id": request_dict["session_id"],
        "task_id": request_dict["task_id"],
        "turn_id": request_dict["turn_id"],
        "tool_call_id": request_dict["tool_call_id"],
        "parent_tool_call_id": request_dict.get("parent_tool_call_id", ""),
        "manifest_hash": request_dict["manifest_hash"],
        "sequence": int(sequence if sequence is not None else request_dict["sequence"]),
        "sent_at": now,
        "expires_at": response_expiry,
        "approval_handle": request_dict.get("approval_handle", ""),
        "payload": payload,
    }
    return validate_phone_tool_envelope(envelope, now_ms=now, expected_type=RESPONSE_TYPE)


def make_cancel_envelope(
    request: Mapping[str, Any],
    *,
    sequence: int,
    reason: str,
    message_id: str = "",
    now_ms: int | None = None,
    ttl_ms: int = DEFAULT_CONTROL_TTL_MS,
) -> dict[str, Any]:
    request_dict = dict(request)
    request_reference = int(request_dict.get("sent_at") or 0)
    validate_phone_tool_envelope(request_dict, now_ms=request_reference, expected_type=REQUEST_TYPE)
    if ttl_ms <= 0:
        raise ValueError("ttl_ms must be positive")
    now = int(_epoch_ms() if now_ms is None else now_ms)
    envelope = {
        "protocol": PROTOCOL_NAME,
        "version": PROTOCOL_VERSION,
        "type": CANCEL_TYPE,
        "message_id": message_id or str(uuid.uuid4()),
        "session_id": request_dict["session_id"],
        "task_id": request_dict["task_id"],
        "turn_id": request_dict["turn_id"],
        "tool_call_id": request_dict["tool_call_id"],
        "parent_tool_call_id": request_dict.get("parent_tool_call_id", ""),
        "manifest_hash": request_dict["manifest_hash"],
        "sequence": sequence,
        "sent_at": now,
        "expires_at": now + ttl_ms,
        "approval_handle": request_dict.get("approval_handle", ""),
        "payload": {"reason": reason},
    }
    return validate_phone_tool_envelope(envelope, now_ms=now, expected_type=CANCEL_TYPE)


class PhoneToolBroker:
    """Correlate Desktop requests with results executed exclusively by a phone."""

    def __init__(
        self,
        send_envelope: SendEnvelope,
        *,
        on_event: EventCallback | None = None,
        event_callbacks: Iterable[EventCallback] = (),
        clock_ms: Clock | None = None,
        default_timeout_ms: int = DEFAULT_TIMEOUT_MS,
        max_result_bytes: int = DEFAULT_MAX_RESULT_BYTES,
        replay_capacity: int = DEFAULT_REPLAY_CAPACITY,
    ) -> None:
        if not callable(send_envelope):
            raise TypeError("send_envelope must be callable")
        if default_timeout_ms <= 0:
            raise ValueError("default_timeout_ms must be positive")
        if max_result_bytes < 64:
            raise ValueError("max_result_bytes must be at least 64")
        if replay_capacity <= 0:
            raise ValueError("replay_capacity must be positive")

        callbacks = list(event_callbacks)
        if on_event is not None:
            callbacks.append(on_event)
        if any(not callable(callback) for callback in callbacks):
            raise TypeError("event callbacks must be callable")

        self._send_envelope = send_envelope
        self._callbacks = callbacks
        self._clock_ms = clock_ms or _epoch_ms
        self._default_timeout_ms = default_timeout_ms
        self._max_result_bytes = max_result_bytes
        self._replay_capacity = replay_capacity
        self._lock = threading.RLock()
        self._pending: dict[str, _PendingCall] = {}
        self._terminal: OrderedDict[str, _TerminalCall] = OrderedDict()
        self._seen_calls: OrderedDict[str, str] = OrderedDict()
        self._seen_inbound_messages: OrderedDict[str, str] = OrderedDict()
        self._last_outbound_sequence: dict[tuple[str, str, str], int] = {}
        self._last_inbound_sequence: dict[tuple[str, str, str], int] = {}

    @property
    def pending_calls(self) -> dict[str, dict[str, Any]]:
        """Return a metadata-only snapshot of the pending-call map."""
        with self._lock:
            return {
                call_id: {
                    "session_id": pending.request["session_id"],
                    "task_id": pending.request["task_id"],
                    "turn_id": pending.request["turn_id"],
                    "parent_tool_call_id": pending.request["parent_tool_call_id"],
                    "manifest_hash": pending.request["manifest_hash"],
                    "sequence": pending.request["sequence"],
                    "expires_at": pending.deadline_ms,
                    "approval_handle": pending.request["approval_handle"],
                }
                for call_id, pending in self._pending.items()
            }

    @property
    def pending_count(self) -> int:
        with self._lock:
            return len(self._pending)

    def add_event_callback(self, callback: EventCallback) -> None:
        if not callable(callback):
            raise TypeError("callback must be callable")
        with self._lock:
            self._callbacks.append(callback)

    def remove_event_callback(self, callback: EventCallback) -> None:
        with self._lock:
            self._callbacks = [item for item in self._callbacks if item is not callback]

    def start_call(
        self,
        *,
        session_id: str,
        task_id: str,
        turn_id: str,
        call_id: str,
        manifest_hash: str,
        sequence: int,
        tool_id: str,
        arguments: Mapping[str, Any],
        parent_call_id: str = "",
        approval_handle: str = "",
        timeout_ms: int | None = None,
        expires_at: int | None = None,
        message_id: str = "",
    ) -> dict[str, Any]:
        timeout = self._default_timeout_ms if timeout_ms is None else timeout_ms
        now = int(self._clock_ms())
        request = make_request_envelope(
            session_id=session_id,
            task_id=task_id,
            turn_id=turn_id,
            call_id=call_id,
            manifest_hash=manifest_hash,
            sequence=sequence,
            tool_id=tool_id,
            arguments=arguments,
            parent_call_id=parent_call_id,
            approval_handle=approval_handle,
            message_id=message_id,
            now_ms=now,
            expires_at=expires_at,
            timeout_ms=timeout,
        )
        fingerprint = hashlib.sha256(_canonical_json(request).encode("utf-8")).hexdigest()
        scope = self._sequence_scope(request)
        pending = _PendingCall(request=_clone_json(request), deadline_ms=request["expires_at"])

        with self._lock:
            if call_id in self._pending or call_id in self._seen_calls:
                raise ReplayRejectedError(f"tool call {call_id!r} was already dispatched")
            previous_sequence = self._last_outbound_sequence.get(scope, 0)
            if sequence <= previous_sequence:
                raise ReplayRejectedError("outbound sequence is stale or duplicated")
            self._pending[call_id] = pending
            self._last_outbound_sequence[scope] = sequence
            self._remember(self._seen_calls, call_id, fingerprint)

        try:
            self._send_envelope(_clone_json(request))
        except Exception as exc:
            error = BrokerDispatchError(f"phone tool request dispatch failed: {exc}")
            with self._lock:
                self._pending.pop(call_id, None)
                pending.error = error
                pending.completion.set()
                self._remember_terminal(call_id, _TerminalCall(error=error))
            self._emit("dispatch_failed", request, error=str(error))
            raise error from exc

        self._emit("call_requested", request)
        return _clone_json(request)

    def receive_response(self, envelope: object) -> dict[str, Any]:
        now = int(self._clock_ms())
        response = validate_phone_tool_envelope(
            envelope, now_ms=now, expected_type=RESPONSE_TYPE
        )
        message_id = str(response["message_id"])
        call_id = str(response["tool_call_id"])
        expired_call_ids = self.expire_pending(now_ms=now)
        if call_id in expired_call_ids:
            raise PhoneToolTimeoutError("phone tool response arrived after the call deadline")
        wire_digest = hashlib.sha256(_canonical_json(response).encode("utf-8")).hexdigest()
        safe_response = _clone_json(response)
        safe_response["payload"] = redact_and_bound_result(
            response["payload"], self._max_result_bytes
        )
        scope = self._sequence_scope(response)

        with self._lock:
            if message_id in self._seen_inbound_messages:
                raise ReplayRejectedError(f"duplicate response message {message_id!r}")
            pending = self._pending.get(call_id)
            if pending is None:
                if call_id in self._seen_calls:
                    raise ReplayRejectedError(f"tool call {call_id!r} is no longer pending")
                raise UnknownCallError(call_id)
            self._validate_correlation(pending.request, response)
            previous_sequence = self._last_inbound_sequence.get(scope, 0)
            if response["sequence"] <= previous_sequence:
                raise ReplayRejectedError("inbound sequence is stale or duplicated")

            self._pending.pop(call_id, None)
            pending.response = safe_response
            self._last_inbound_sequence[scope] = response["sequence"]
            self._remember(self._seen_inbound_messages, message_id, wire_digest)
            self._remember_terminal(call_id, _TerminalCall(response=safe_response))
            pending.completion.set()

        self._emit("call_completed", safe_response, response=_clone_json(safe_response))
        return _clone_json(safe_response)

    def wait_for_result(self, call_id: str, timeout_ms: int | None = None) -> dict[str, Any]:
        with self._lock:
            terminal = self._terminal.get(call_id)
            pending = self._pending.get(call_id)
        if terminal is not None:
            return self._resolve_terminal(terminal)
        if pending is None:
            raise UnknownCallError(call_id)

        remaining_ms = max(0, pending.deadline_ms - int(self._clock_ms()))
        wait_ms = remaining_ms if timeout_ms is None else min(remaining_ms, max(0, timeout_ms))
        if pending.completion.wait(wait_ms / 1000):
            if pending.error is not None:
                raise pending.error
            if pending.response is not None:
                return _clone_json(pending.response)

        with self._lock:
            terminal = self._terminal.get(call_id)
        if terminal is not None:
            return self._resolve_terminal(terminal)

        self._timeout_call(call_id, "phone tool response deadline elapsed")
        with self._lock:
            terminal = self._terminal.get(call_id)
        if terminal is None:
            raise UnknownCallError(call_id)
        return self._resolve_terminal(terminal)

    def cancel_call(self, call_id: str, reason: str = "cancelled by Desktop") -> dict[str, Any] | None:
        error = PhoneToolCancelledError(reason)
        return self._terminate_pending(call_id, error, "call_cancelled", reason)

    def expire_pending(self, now_ms: int | None = None) -> list[str]:
        reference = int(self._clock_ms() if now_ms is None else now_ms)
        with self._lock:
            expired = [
                call_id
                for call_id, pending in self._pending.items()
                if reference >= pending.deadline_ms
            ]
        for call_id in expired:
            self._timeout_call(call_id, "phone tool response deadline elapsed")
        return expired

    def close(self, reason: str = "broker closed") -> list[str]:
        with self._lock:
            call_ids = list(self._pending)
        for call_id in call_ids:
            self.cancel_call(call_id, reason)
        return call_ids

    receive = receive_response
    wait = wait_for_result
    cancel = cancel_call

    def _timeout_call(self, call_id: str, reason: str) -> dict[str, Any] | None:
        return self._terminate_pending(
            call_id, PhoneToolTimeoutError(reason), "call_timed_out", reason
        )

    def _terminate_pending(
        self,
        call_id: str,
        error: BaseException,
        event_type: str,
        reason: str,
    ) -> dict[str, Any] | None:
        now = int(self._clock_ms())
        with self._lock:
            pending = self._pending.pop(call_id, None)
            if pending is None:
                return None
            scope = self._sequence_scope(pending.request)
            sequence = self._last_outbound_sequence.get(scope, pending.request["sequence"]) + 1
            self._last_outbound_sequence[scope] = sequence
            pending.error = error
            self._remember_terminal(call_id, _TerminalCall(error=error))
            pending.completion.set()

        cancel_envelope = make_cancel_envelope(
            pending.request,
            sequence=sequence,
            reason=reason[:MAX_CANCEL_REASON_CHARS],
            now_ms=now,
        )
        delivery_error = ""
        try:
            self._send_envelope(_clone_json(cancel_envelope))
        except Exception as exc:
            delivery_error = str(exc)[:256]
        self._emit(event_type, pending.request, delivery_error=delivery_error)
        return _clone_json(cancel_envelope)

    def _remember(self, cache: OrderedDict[str, str], key: str, value: str) -> None:
        cache[key] = value
        cache.move_to_end(key)
        while len(cache) > self._replay_capacity:
            cache.popitem(last=False)

    def _remember_terminal(self, call_id: str, terminal: _TerminalCall) -> None:
        self._terminal[call_id] = terminal
        self._terminal.move_to_end(call_id)
        while len(self._terminal) > self._replay_capacity:
            self._terminal.popitem(last=False)

    @staticmethod
    def _sequence_scope(envelope: Mapping[str, Any]) -> tuple[str, str, str]:
        return (
            str(envelope["session_id"]),
            str(envelope["task_id"]),
            str(envelope["turn_id"]),
        )

    @staticmethod
    def _validate_correlation(request: Mapping[str, Any], response: Mapping[str, Any]) -> None:
        for field_name in (
            "session_id",
            "task_id",
            "turn_id",
            "tool_call_id",
            "parent_tool_call_id",
            "approval_handle",
        ):
            if str(request.get(field_name, "")) != str(response.get(field_name, "")):
                raise ReplayRejectedError(f"response {field_name} does not match request")
        if _validate_manifest_hash(request.get("manifest_hash")) != _validate_manifest_hash(
            response.get("manifest_hash")
        ):
            raise ReplayRejectedError("response manifest_hash does not match request")

    def _resolve_terminal(self, terminal: _TerminalCall) -> dict[str, Any]:
        if terminal.error is not None:
            raise terminal.error
        if terminal.response is None:
            raise PhoneToolBrokerError("terminal call has no result")
        return _clone_json(terminal.response)

    def _emit(self, event_type: str, envelope: Mapping[str, Any], **extra: Any) -> None:
        event = {
            "type": f"phone_tool_broker.{event_type}",
            "at": int(self._clock_ms()),
            "session_id": str(envelope.get("session_id", "")),
            "task_id": str(envelope.get("task_id", "")),
            "turn_id": str(envelope.get("turn_id", "")),
            "tool_call_id": str(envelope.get("tool_call_id", "")),
            "parent_tool_call_id": str(envelope.get("parent_tool_call_id", "")),
            "sequence": int(envelope.get("sequence") or 0),
        }
        event.update(_redact_value(extra))
        with self._lock:
            callbacks = list(self._callbacks)
        for callback in callbacks:
            try:
                callback(copy.deepcopy(event))
            except Exception:
                continue


# Readable aliases for callers that prefer "build"/"validate" terminology.
build_request_envelope = make_request_envelope
build_response_envelope = make_response_envelope
build_cancel_envelope = make_cancel_envelope
validate_envelope = validate_phone_tool_envelope


__all__ = [
    "BrokerDispatchError",
    "CANCEL_TYPE",
    "EnvelopeValidationError",
    "PhoneToolBroker",
    "PhoneToolBrokerError",
    "PhoneToolCancelledError",
    "PhoneToolTimeoutError",
    "PROTOCOL_NAME",
    "PROTOCOL_VERSION",
    "REQUEST_TYPE",
    "RESPONSE_TYPE",
    "ReplayRejectedError",
    "UnknownCallError",
    "build_cancel_envelope",
    "build_request_envelope",
    "build_response_envelope",
    "compute_manifest_hash",
    "make_cancel_envelope",
    "make_request_envelope",
    "make_response_envelope",
    "redact_and_bound_result",
    "validate_envelope",
    "validate_phone_tool_envelope",
]
