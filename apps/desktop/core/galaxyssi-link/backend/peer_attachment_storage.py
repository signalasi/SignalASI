"""Byte-preserving private attachment storage; Signal protects transport separately."""
from __future__ import annotations

import hashlib
import os
import uuid
from pathlib import Path
from typing import Iterator


CHUNK_BYTES = 1024 * 1024
MAX_ATTACHMENT_BYTES = 1024 * 1024 * 1024


class PeerAttachmentError(ValueError):
    pass


class PeerAttachmentStorage:
    def store_file(self, source: Path, target: Path, *, expected_sha256: str = "") -> tuple[int, str]:
        source, target = Path(source), Path(target)
        if not source.is_file() or source.is_symlink():
            raise PeerAttachmentError("Peer attachment source is unavailable")
        size = source.stat().st_size
        if not 0 < size <= MAX_ATTACHMENT_BYTES:
            raise PeerAttachmentError("Peer attachment size is outside the supported range")
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_name(f".{target.name}.{uuid.uuid4().hex}.tmp")
        digest = hashlib.sha256()
        copied = 0
        try:
            with source.open("rb") as reader, temporary.open("xb") as writer:
                while chunk := reader.read(CHUNK_BYTES):
                    copied += len(chunk)
                    if copied > size:
                        raise PeerAttachmentError("Peer attachment changed while storing")
                    digest.update(chunk)
                    writer.write(chunk)
                writer.flush()
                os.fsync(writer.fileno())
            if copied != size or (expected_sha256 and digest.hexdigest() != expected_sha256.lower()):
                raise PeerAttachmentError("Peer attachment SHA-256 or length does not match")
            os.replace(temporary, target)
        finally:
            temporary.unlink(missing_ok=True)
        return size, digest.hexdigest()

    def read_stream(self, path: Path, *, expected_size: int = 0, expected_sha256: str = "") -> Iterator[bytes]:
        path = Path(path)
        if not path.is_file() or path.is_symlink():
            raise PeerAttachmentError("Peer attachment is unavailable")
        size = path.stat().st_size
        if not 0 < size <= MAX_ATTACHMENT_BYTES or (expected_size and size != expected_size):
            raise PeerAttachmentError("Peer attachment size does not match")
        digest = hashlib.sha256()
        count = 0
        with path.open("rb") as stream:
            while chunk := stream.read(CHUNK_BYTES):
                digest.update(chunk)
                count += len(chunk)
                yield chunk
        if count != size or (expected_sha256 and digest.hexdigest() != expected_sha256.lower()):
            raise PeerAttachmentError("Peer attachment integrity verification failed")
