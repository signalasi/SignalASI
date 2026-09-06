"""Task-scoped requests for restoring prior phone attachments on demand."""
from __future__ import annotations

from dataclasses import dataclass, field
import threading
import time
import uuid
from typing import Callable, Iterable

from input_attachment_transfer import AttachmentTransferReceipt
from blob_failures import TERMINAL_BLOB_ERRORS, failure_observation
from link_protocol import valid_route_id


REQUEST_TYPE = "input_attachment_request"
RESULT_TYPE = "input_attachment_request_result"
MAX_REQUESTED_ATTACHMENTS = 10
DEFAULT_REQUEST_TIMEOUT_SECONDS = 120.0


class AttachmentRequestError(RuntimeError):
    pass


class AttachmentTransferFailed(AttachmentRequestError):
    def __init__(self, code: str):
        super().__init__(failure_observation(code))
        self.code = code


@dataclass
class _PendingRequest:
    request_id: str
    client_route_id: str
    conversation_id: str
    task_id: str
    turn_id: str
    contact_id: str
    source_message_id: str
    expected_ids: tuple[str, ...]
    created_at: float = field(default_factory=time.monotonic)
    event: threading.Event = field(default_factory=threading.Event)
    receipts: dict[str, dict] = field(default_factory=dict)
    available_ids: set[str] = field(default_factory=set)
    missing_ids: set[str] = field(default_factory=set)
    error: str = ""
    error_code: str = ""

    @property
    def complete(self) -> bool:
        return set(self.expected_ids).issubset(self.receipts)


class AttachmentRequestBroker:
    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._pending: dict[str, _PendingRequest] = {}

    def request(
        self,
        *,
        client_route_id: str,
        conversation_id: str,
        task_id: str,
        turn_id: str,
        contact_id: str,
        source_message_id: str,
        attachment_ids: Iterable[str],
        reason: str,
        publish: Callable[[dict], bool],
        timeout_seconds: float = DEFAULT_REQUEST_TIMEOUT_SECONDS,
    ) -> list[dict]:
        expected = _attachment_ids(attachment_ids)
        if not valid_route_id(client_route_id) or not expected:
            raise AttachmentRequestError("Attachment recovery identity is invalid")
        request_id = uuid.uuid4().hex
        pending = _PendingRequest(
            request_id=request_id,
            client_route_id=client_route_id,
            conversation_id=_identity(conversation_id),
            task_id=_identity(task_id),
            turn_id=_identity(turn_id),
            contact_id=_identity(contact_id),
            source_message_id=_identity(source_message_id),
            expected_ids=expected,
        )
        with self._lock:
            self._pending[request_id] = pending
        payload = {
            "type": REQUEST_TYPE,
            "request_id": request_id,
            "client_route_id": pending.client_route_id,
            "conversation_id": pending.conversation_id,
            "task_id": pending.task_id,
            "turn_id": pending.turn_id,
            "contact_id": pending.contact_id,
            "source_message_id": pending.source_message_id,
            "attachment_ids": list(pending.expected_ids),
            "reason": " ".join(str(reason or "").split())[:400],
            "time": int(time.time() * 1000),
        }
        try:
            if not publish(payload):
                raise AttachmentRequestError("Attachment recovery request could not be delivered")
            if not pending.event.wait(max(1.0, float(timeout_seconds))):
                raise AttachmentRequestError("Phone attachment recovery timed out")
            if pending.error_code:
                raise AttachmentTransferFailed(pending.error_code)
            if pending.error:
                raise AttachmentRequestError(pending.error)
            if pending.missing_ids:
                names = ", ".join(sorted(pending.missing_ids))
                raise AttachmentRequestError(f"Requested phone attachment is unavailable: {names}")
            if not pending.complete:
                raise AttachmentRequestError("Phone attachment recovery did not complete")
            return [pending.receipts[value] for value in pending.expected_ids]
        finally:
            with self._lock:
                self._pending.pop(request_id, None)

    def accept_result(self, payload: dict, *, client_route_id: str) -> bool:
        request_id = str(payload.get("request_id") or "").strip()
        with self._lock:
            pending = self._pending.get(request_id)
            if pending is None or not self._matches(pending, payload, client_route_id):
                return False
            if pending.event.is_set():
                return False
            status = str(payload.get("status") or "").strip().lower()
            error_code = payload.get("error_code", "")
            if error_code and (not isinstance(error_code, str) or error_code not in TERMINAL_BLOB_ERRORS):
                return False
            available = set(_attachment_ids(payload.get("available_attachment_ids") or ()))
            missing = set(_attachment_ids(payload.get("missing_attachment_ids") or ()))
            if not available.issubset(set(pending.expected_ids)):
                return False
            if not missing.issubset(set(pending.expected_ids)):
                return False
            pending.available_ids.update(available)
            pending.missing_ids.update(missing)
            if status == "failed":
                pending.error_code = error_code
                pending.error = (failure_observation(error_code) if error_code else
                                 _safe_error(payload.get("error")) or "Phone attachment recovery failed")
                pending.event.set()
            elif status == "missing" or pending.missing_ids:
                pending.event.set()
            elif status not in {"accepted", "transferring", "stored"}:
                return False
            elif pending.complete:
                pending.event.set()
            return True

    def accept_receipt(self, receipt: AttachmentTransferReceipt) -> bool:
        request_id = str(getattr(receipt, "attachment_request_id", "") or "").strip()
        attachment_id = str(getattr(receipt, "attachment_id", "") or "").strip()
        if not request_id or not attachment_id or receipt.status not in {"stored", "failed"}:
            return False
        if receipt.status == "failed" and receipt.error_code not in TERMINAL_BLOB_ERRORS:
            return False
        with self._lock:
            pending = self._pending.get(request_id)
            if pending is None:
                return False
            if any((
                receipt.client_route_id != pending.client_route_id,
                receipt.conversation_id != pending.conversation_id,
                receipt.task_id != pending.task_id,
                receipt.turn_id != pending.turn_id,
                receipt.contact_id != pending.contact_id,
                receipt.source_message_id != pending.source_message_id,
                attachment_id not in pending.expected_ids,
            )):
                return False
            if pending.event.is_set():
                return False
            if receipt.status == "failed":
                pending.error = failure_observation(receipt.error_code)
                pending.error_code = receipt.error_code
                pending.event.set()
                return True
            pending.receipts[attachment_id] = receipt.descriptor()
            if pending.complete:
                pending.event.set()
            return True

    @staticmethod
    def _matches(pending: _PendingRequest, payload: dict, client_route_id: str) -> bool:
        return all((
            client_route_id == pending.client_route_id,
            str(payload.get("client_route_id") or "") == pending.client_route_id,
            str(payload.get("conversation_id") or "") == pending.conversation_id,
            str(payload.get("task_id") or "") == pending.task_id,
            str(payload.get("turn_id") or "") == pending.turn_id,
            str(payload.get("contact_id") or "") == pending.contact_id,
            str(payload.get("source_message_id") or "") == pending.source_message_id,
        ))


def _attachment_ids(values: Iterable[object]) -> tuple[str, ...]:
    if isinstance(values, (str, bytes, dict)):
        return ()
    result: list[str] = []
    try:
        candidates = list(values)
    except TypeError:
        return ()
    for value in candidates[:MAX_REQUESTED_ATTACHMENTS]:
        candidate = str(value or "").strip()
        if candidate and len(candidate) <= 120 and candidate not in result:
            result.append(candidate)
    return tuple(result)


def _identity(value: object) -> str:
    result = str(value or "").strip()
    if not result or len(result) > 256 or any(ord(character) < 32 for character in result):
        raise AttachmentRequestError("Attachment recovery identity is invalid")
    return result


def _safe_error(value: object) -> str:
    return " ".join(str(value or "").split())[:300]


attachment_request_broker = AttachmentRequestBroker()
