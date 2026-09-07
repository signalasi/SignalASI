"""Per-transfer AEAD staging. Only ciphertext and device-encrypted state touch disk."""
from __future__ import annotations

import hashlib
from contextlib import contextmanager
import os
from pathlib import Path
import re
import secrets
import struct
from typing import Iterator

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from blob_protocol import (
    BlobError, CHUNK_BYTES, MAX_FILE_BYTES, SESSION_PATTERN, TAG_BYTES, VERSION,
    canonical, checked_hex, manifest, sha256,
)
from secure_state import read_secure_json, write_secure_json

PURPOSE = "blob.transfer-checkpoint.v1"
STATE_FILE = "transfer.secure.json"
DOMAIN = b"GalaxySSI-Blob-AEAD-v1\x00"


def binding_hash(binding: dict) -> str:
    if not isinstance(binding, dict) or not 1 <= len(binding) <= 16:
        raise BlobError("missing_transfer_binding")
    if any(not isinstance(key, str) or not re.fullmatch(r"[a-z][a-z0-9_]{0,63}", key)
           or not isinstance(value, str) or not 1 <= len(value) <= 256
           for key, value in binding.items()):
        raise BlobError("invalid_transfer_binding")
    raw = canonical(binding)
    if len(raw) > 16384:
        raise BlobError("transfer_binding_too_large")
    return sha256(raw)


def associated_data(private: dict, index: int, size: int) -> bytes:
    return (DOMAIN + bytes.fromhex(private["blob_id"])
            + bytes.fromhex(private["binding_sha256"])
            + struct.pack(">QI", private["size"], CHUNK_BYTES)
            + bytes.fromhex(private["sha256"])
            + struct.pack(">II", index, size))


def _write(path: Path, data: bytes):
    temporary = path.with_name(path.name + "." + secrets.token_hex(8) + ".tmp")
    try:
        with temporary.open("xb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def validate_private_descriptor(value: dict) -> dict:
    fields = {"version", "blob_id", "key", "nonce_prefix", "size", "sha256",
              "binding_sha256", "manifest_sha256"}
    if not isinstance(value, dict) or set(value) != fields:
        raise BlobError("invalid_private_descriptor")
    if type(value["version"]) is not int or value["version"] != VERSION:
        raise BlobError("unsupported_blob_version")
    checked_hex(value["blob_id"], SESSION_PATTERN)
    for name in ("key", "sha256", "binding_sha256", "manifest_sha256"):
        checked_hex(value[name])
    prefix = value["nonce_prefix"]
    try:
        if (not isinstance(prefix, str) or len(prefix) != 16 or len(bytes.fromhex(prefix)) != 8
                or bytes.fromhex(prefix).hex() != prefix):
            raise ValueError()
    except ValueError:
        raise BlobError("invalid_nonce_prefix") from None
    size = value["size"]
    if type(size) is not int or not 0 <= size <= MAX_FILE_BYTES:
        raise BlobError("invalid_file_size")
    return dict(value)


def _private(value: dict, public: dict) -> dict:
    value = validate_private_descriptor(value)
    size = value["size"]
    if sum(chunk["size"] - TAG_BYTES for chunk in public["chunks"]) != size:
        raise BlobError("file_size_mismatch")
    if sha256(canonical(public)) != value["manifest_sha256"]:
        raise BlobError("manifest_hash_mismatch")
    return dict(value)


class StagedBlob:
    def __init__(self, directory: Path, private: dict, public: dict, remote: dict | None = None):
        self.directory = Path(directory)
        self.public = manifest(public)
        self.private = _private(private, self.public)
        self.remote = dict(remote or {})

    @classmethod
    def prepare(cls, source: Path, directory: Path, binding: dict) -> "StagedBlob":
        source, directory = Path(source), Path(directory)
        if source.is_symlink() or not source.is_file():
            raise BlobError("invalid_source_file")
        size = source.stat().st_size
        if not 0 <= size <= MAX_FILE_BYTES:
            raise BlobError("file_too_large", 413)
        binding_hash(binding)
        first_hash = hashlib.sha256()
        with source.open("rb") as stream:
            read_size = 0
            while block := stream.read(CHUNK_BYTES):
                read_size += len(block)
                if read_size > size:
                    raise BlobError("source_changed")
                first_hash.update(block)
        if read_size != size:
            raise BlobError("source_changed")
        return cls.prepare_stream(lambda: source.open("rb"), directory, binding,
                                  size=size, digest=first_hash.hexdigest())

    @classmethod
    def prepare_stream(cls, open_source, directory: Path, binding: dict, *, size: int, digest: str,
                       cancel=None) -> "StagedBlob":
        """Stage a known artifact or decrypted stream without a plaintext disk copy."""
        directory = Path(directory)
        if type(size) is not int or not 0 <= size <= MAX_FILE_BYTES:
            raise BlobError("file_too_large", 413)
        checked_hex(digest)
        binding_digest = binding_hash(binding)
        def check_cancelled():
            if cancel is not None and cancel():
                raise BlobError("transfer_cancelled", 499)
        check_cancelled()
        private = {"version": VERSION, "blob_id": secrets.token_hex(16),
                   "key": secrets.token_hex(32), "nonce_prefix": secrets.token_hex(8),
                   "size": size, "sha256": digest,
                   "binding_sha256": binding_digest, "manifest_sha256": ""}
        # A new directory and key are mandatory. Interrupted preparation is never
        # resumed by encrypting changed source bytes with an old nonce/key pair.
        directory.mkdir(parents=True, exist_ok=False)
        cipher = AESGCM(bytes.fromhex(private["key"]))
        prefix = bytes.fromhex(private["nonce_prefix"])
        second_hash = hashlib.sha256()
        chunks = []
        with open_source() as stream:
            count = max(1, (size + CHUNK_BYTES - 1) // CHUNK_BYTES)
            for index in range(count):
                expected = min(CHUNK_BYTES, size - index * CHUNK_BYTES)
                plain = bytearray()
                try:
                    while len(plain) < expected:
                        check_cancelled()
                        part = stream.read(expected - len(plain))
                        if not part or len(part) > expected - len(plain):
                            raise BlobError("source_changed")
                        plain.extend(part)
                    check_cancelled()
                    second_hash.update(plain)
                    encrypted = cipher.encrypt(prefix + struct.pack(">I", index), bytes(plain),
                                               associated_data(private, index, expected))
                    _write(directory / f"{index:08d}.blob", encrypted)
                    chunks.append({"sha256": sha256(encrypted), "size": len(encrypted)})
                finally:
                    plain[:] = b"\x00" * len(plain)
            if stream.read(1) or second_hash.hexdigest() != private["sha256"]:
                raise BlobError("source_changed")
        public = {"version": VERSION, "chunks": chunks}
        private["manifest_sha256"] = sha256(canonical(public))
        staged = cls(directory, private, public)
        check_cancelled()
        staged.save()
        return staged

    @classmethod
    def open(cls, directory: Path, binding: dict) -> "StagedBlob":
        doc = read_secure_json(Path(directory) / STATE_FILE, purpose=PURPOSE).value
        staged = cls(directory, doc["private"], doc["manifest"], doc.get("remote"))
        staged.check_binding(binding)
        return staged

    @classmethod
    def receive(cls, directory: Path, private: dict, public: dict, binding: dict,
                remote: dict) -> "StagedBlob":
        staged = cls(directory, private, public, remote)
        staged.check_binding(binding)
        staged.directory.mkdir(parents=True, exist_ok=False)
        staged.save()
        return staged

    def check_binding(self, binding: dict):
        if not secrets.compare_digest(self.private["binding_sha256"], binding_hash(binding)):
            raise BlobError("transfer_binding_mismatch", 409)

    def save(self):
        write_secure_json(self.directory / STATE_FILE,
                          {"private": self.private, "manifest": self.public, "remote": self.remote},
                          purpose=PURPOSE)

    @contextmanager
    def exclusive(self):
        """Cross-process ownership; process death releases the OS lock, not the data."""
        path = self.directory / "transfer.lock"
        if path.is_symlink():
            raise BlobError("invalid_transfer_lock")
        descriptor = os.open(path, os.O_RDWR | os.O_CREAT, 0o600)
        locked = False
        try:
            if os.fstat(descriptor).st_size == 0:
                os.write(descriptor, b"\x00")
            os.lseek(descriptor, 0, os.SEEK_SET)
            try:
                if os.name == "nt":
                    import msvcrt
                    msvcrt.locking(descriptor, msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError:
                raise BlobError("transfer_busy", 409) from None
            locked = True
            doc = read_secure_json(self.directory / STATE_FILE, purpose=PURPOSE).value
            current = StagedBlob(self.directory, doc["private"], doc["manifest"], doc.get("remote"))
            if current.private != self.private or current.public != self.public:
                raise BlobError("transfer_checkpoint_changed", 409)
            self.remote = current.remote
            yield
        finally:
            try:
                if locked:
                    if os.name == "nt":
                        import msvcrt
                        os.lseek(descriptor, 0, os.SEEK_SET)
                        msvcrt.locking(descriptor, msvcrt.LK_UNLCK, 1)
                    else:
                        import fcntl
                        fcntl.flock(descriptor, fcntl.LOCK_UN)
            finally:
                os.close(descriptor)

    def _chunk_path(self, index: int) -> Path:
        if type(index) is not int or not 0 <= index < len(self.public["chunks"]):
            raise BlobError("invalid_chunk_index")
        return self.directory / f"{index:08d}.blob"

    def read_chunk(self, index: int) -> bytes:
        path = self._chunk_path(index)
        chunk = self.public["chunks"][index]
        if path.is_symlink() or not path.is_file() or path.stat().st_size != chunk["size"]:
            raise BlobError("local_chunk_missing_or_corrupt", 409)
        with path.open("rb") as stream:
            data = stream.read(chunk["size"] + 1)
        if len(data) != chunk["size"] or sha256(data) != chunk["sha256"]:
            raise BlobError("local_chunk_missing_or_corrupt", 409)
        return data

    def has_chunk(self, index: int) -> bool:
        try:
            self.read_chunk(index)
            return True
        except (BlobError, OSError):
            return False

    def store_chunk(self, index: int, data: bytes):
        path = self._chunk_path(index)
        chunk = self.public["chunks"][index]
        if len(data) != chunk["size"] or sha256(data) != chunk["sha256"]:
            raise BlobError("ciphertext_hash_mismatch", 409)
        _write(path, data)

    def plaintext(self, binding: dict) -> Iterator[bytes]:
        self.check_binding(binding)
        cipher = AESGCM(bytes.fromhex(self.private["key"]))
        prefix = bytes.fromhex(self.private["nonce_prefix"])
        digest = hashlib.sha256()
        for index, chunk in enumerate(self.public["chunks"]):
            plain = cipher.decrypt(prefix + struct.pack(">I", index), self.read_chunk(index),
                                   associated_data(self.private, index, chunk["size"] - TAG_BYTES))
            digest.update(plain)
            yield plain
        if digest.hexdigest() != self.private["sha256"]:
            raise BlobError("plaintext_hash_mismatch", 409)
