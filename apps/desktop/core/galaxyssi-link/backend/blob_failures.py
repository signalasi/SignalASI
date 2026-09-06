"""Bounded terminal Blob observations; transient transport failures keep checkpoints."""
from __future__ import annotations


TERMINAL_BLOB_ERRORS = frozenset({
    "blob_expired", "blob_not_found", "blob_source_missing", "source_changed",
    "chunk_authentication_failed", "ciphertext_hash_mismatch", "plaintext_hash_mismatch",
    "manifest_hash_mismatch", "file_size_mismatch", "transfer_binding_mismatch",
    "local_chunk_missing_or_corrupt", "blob_outgoing_identity_mismatch",
})


def failure_observation(code: str) -> str:
    if code not in TERMINAL_BLOB_ERRORS:
        raise ValueError("Unknown terminal Blob error")
    return (f"Attachment transfer failed: {code}. No verified attachment was delivered. "
            "Inspect the failure and request a fresh attachment transfer if needed; "
            "do not assume the image or file was available.")


def failed_input_receipt(manifest: dict, code: str) -> dict:
    from input_attachment_transfer import AttachmentTransferReceipt

    failure_observation(code)
    return AttachmentTransferReceipt(
        **{key: manifest[key] for key in (
            "transfer_id", "sha256", "attachment_id", "name", "mime_type", "size_bytes",
            "client_route_id", "conversation_id", "task_id", "turn_id", "contact_id")},
        attachment_request_id=manifest.get("attachment_request_id", ""),
        source_message_id=str(manifest.get("client_message_id", "")),
        status="failed", error_code=code,
    ).payload()
