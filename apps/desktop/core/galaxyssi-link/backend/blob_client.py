"""Blocking worker-side HTTPS transfer with resumable ciphertext staging."""
from __future__ import annotations

import json
from pathlib import Path
import ssl
from typing import Callable
import urllib.parse
import secrets

import httpx
from cryptography.exceptions import InvalidTag

from blob_crypto import STATE_FILE, StagedBlob
from blob_protocol import (
    BlobError, CHUNK_BYTES, MAX_MANIFEST_BYTES, TAG_BYTES, VERSION, canonical,
    checked_hex, expand_bitmap, manifest, sha256,
)

_RELAY_ERRORS = {
    "authentication_required", "blob_not_found", "blob_expired", "blob_creation_conflict",
    "relay_session_capacity", "relay_storage_capacity", "ciphertext_hash_mismatch",
    "chunk_not_ready", "chunk_not_found", "corrupt_chunk_requires_repair", "invalid_json",
    "invalid_creation_request", "invalid_identifier", "invalid_manifest", "unsupported_blob_version",
    "invalid_chunk_count", "invalid_chunk_descriptor", "invalid_chunk_size", "invalid_chunk_layout",
    "file_too_large", "capabilities_must_differ", "invalid_chunk_index", "body_too_large",
    "body_timeout", "content_encoding_not_supported",
}


class BlobClient:
    def __init__(self, base_url: str, *, provisioning_token: str | None = None,
                 timeout: float = 60, allow_loopback_http: bool = False,
                 tls_context: ssl.SSLContext | None = None, trust_env: bool = True):
        if not isinstance(base_url, str) or len(base_url) > 2048:
            raise BlobError("invalid_relay_origin")
        parts = urllib.parse.urlsplit(base_url)
        loopback = parts.hostname in {"127.0.0.1", "::1"}
        if (parts.scheme != "https" and not (allow_loopback_http and loopback and parts.scheme == "http")):
            raise BlobError("relay_requires_https")
        if (not parts.hostname or parts.username or parts.password or parts.query or parts.fragment
                or parts.path not in {"", "/"}):
            raise BlobError("invalid_relay_origin")
        if timeout <= 0:
            raise ValueError("Transfer timeout must be positive")
        self.base_url = base_url.rstrip("/")
        self.provisioning_token = checked_hex(provisioning_token) if provisioning_token else None
        self.timeout = timeout
        context = tls_context or ssl.create_default_context()
        if not context.check_hostname or context.verify_mode != ssl.CERT_REQUIRED:
            raise BlobError("relay_tls_verification_required")
        self.session = httpx.Client(verify=context, timeout=timeout, trust_env=trust_env,
                                    follow_redirects=False,
                                    limits=httpx.Limits(max_connections=4, max_keepalive_connections=4))

    def close(self):
        self.session.close()

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        self.close()

    def _request(self, method: str, path: str, token: str, *, data: bytes | None = None,
                 binary: bool = False, maximum: int = MAX_MANIFEST_BYTES) -> bytes:
        headers = {"Authorization": "Bearer " + checked_hex(token),
                   "Content-Type": "application/octet-stream" if binary else "application/json",
                   "Accept-Encoding": "identity"}
        try:
            with self.session.stream(method, self.base_url + path, content=data, headers=headers) as response:
                if 300 <= response.status_code < 400:
                    raise BlobError("relay_redirect_rejected", 502)
                if response.headers.get("Content-Encoding", "identity").lower() != "identity":
                    raise BlobError("relay_response_encoding_rejected", 502)
                good = 200 <= response.status_code < 300
                limit = maximum if good else 4096
                value = bytearray()
                for part in response.iter_bytes(chunk_size=min(65536, limit + 1)):
                    if len(value) + len(part) > limit:
                        raise BlobError("relay_response_too_large", 502)
                    value.extend(part)
                if not good:
                    try:
                        error = json.loads(value).get("error", "")
                    except (ValueError, AttributeError):
                        error = ""
                    # Never surface arbitrary relay HTML, credentials or response bodies.
                    if not isinstance(error, str) or error not in _RELAY_ERRORS:
                        error = f"relay_http_{response.status_code}"
                    raise BlobError(error, response.status_code)
                return bytes(value)
        except httpx.TimeoutException:
            raise BlobError("relay_timeout", 504) from None
        except httpx.TransportError as exc:
            causes, visited = [exc], set()
            while causes:
                cause = causes.pop()
                if id(cause) in visited:
                    continue
                visited.add(id(cause))
                if isinstance(cause, ssl.SSLCertVerificationError):
                    raise BlobError("relay_tls_verification_failed", 502) from None
                causes.extend(item for item in (cause.__cause__, cause.__context__) if item is not None)
            raise BlobError("relay_connection_failed", 503) from None

    def _json(self, method: str, path: str, token: str, body: dict | None = None) -> dict:
        raw = self._request(method, path, token, data=canonical(body) if body is not None else None)
        try:
            value = json.loads(raw)
        except ValueError:
            raise BlobError("invalid_relay_response", 502) from None
        if not isinstance(value, dict):
            raise BlobError("invalid_relay_response", 502)
        return value

    @staticmethod
    def _cancelled(cancel: Callable[[], bool] | None):
        if cancel is not None and cancel():
            raise BlobError("transfer_cancelled", 499)

    def upload(self, staged: StagedBlob, *, progress: Callable[[int, int], None] | None = None,
               cancel: Callable[[], bool] | None = None,
               on_offer: Callable[[dict], None] | None = None) -> dict:
        with staged.exclusive():
            return self._upload(staged, progress=progress, cancel=cancel, on_offer=on_offer)

    def _upload(self, staged: StagedBlob, *, progress, cancel, on_offer) -> dict:
        self._cancelled(cancel)
        remote = staged.remote
        if remote and (remote.get("relay") != self.base_url or remote.get("role") != "sender"):
            raise BlobError("relay_checkpoint_mismatch", 409)
        if not remote:
            remote.update({"relay": self.base_url, "role": "sender", "read_token": secrets.token_hex(32),
                           "write_token": secrets.token_hex(32), "created": False})
            staged.save()
        path = "/v1/blobs/" + staged.private["blob_id"]
        expected_root = staged.private["manifest_sha256"]
        if not remote.get("created"):
            if self.provisioning_token is None:
                raise BlobError("relay_provisioning_required", 401)
            result = self._json("PUT", path, self.provisioning_token,
                                {"manifest": staged.public, "read_token": remote["read_token"],
                                 "write_token": remote["write_token"]})
            if result.get("root") != expected_root:
                raise BlobError("manifest_hash_mismatch", 409)
            remote["created"] = True
            staged.save()
        status = self._json("GET", path + "/missing", remote["write_token"])
        count = len(staged.public["chunks"])
        if status.get("root") != expected_root or status.get("chunk_count") != count:
            raise BlobError("relay_checkpoint_mismatch", 409)
        missing = expand_bitmap(status.get("missing_bitmap"), count)
        if on_offer:
            on_offer(self._offer(staged))
        done = staged.private["size"] - sum(staged.public["chunks"][i]["size"] - TAG_BYTES for i in missing)
        if progress:
            progress(done, staged.private["size"])
        for index in missing:
            self._cancelled(cancel)
            data = staged.read_chunk(index)
            result = self._request("PUT", path + f"/chunks/{index}", remote["write_token"],
                                   data=data, binary=True)
            try:
                if json.loads(result).get("stored") is not True:
                    raise ValueError()
            except (ValueError, AttributeError):
                raise BlobError("invalid_chunk_receipt", 502) from None
            done += len(data) - TAG_BYTES
            if progress:
                progress(done, staged.private["size"])
        final = self._json("GET", path + "/missing", remote["write_token"])
        if (final.get("root") != expected_root or final.get("chunk_count") != count
                or expand_bitmap(final.get("missing_bitmap"), count) or final.get("complete") is not True):
            raise BlobError("relay_upload_incomplete", 409)
        remote["uploaded"] = True
        staged.save()
        # This private offer must travel inside the existing authenticated Signal envelope.
        # Relay completion is not a recipient persistence or user-read acknowledgement.
        return self._offer(staged)

    def _offer(self, staged: StagedBlob) -> dict:
        return {"version": VERSION, "relay": self.base_url, "private": dict(staged.private),
                "read_token": staged.remote["read_token"]}

    def download(self, offer: dict, directory: Path, binding: dict, *,
                 progress: Callable[[int, int], None] | None = None,
                 cancel: Callable[[], bool] | None = None) -> StagedBlob:
        self._cancelled(cancel)
        if (not isinstance(offer, dict) or set(offer) != {"version", "relay", "private", "read_token"}
                or type(offer["version"]) is not int or offer["version"] != VERSION
                or offer["relay"] != self.base_url):
            raise BlobError("invalid_blob_offer")
        if not isinstance(offer["private"], dict):
            raise BlobError("invalid_private_descriptor")
        private = dict(offer["private"])
        from blob_protocol import SESSION_PATTERN
        blob_id = checked_hex(private.get("blob_id"), SESSION_PATTERN)
        token = checked_hex(offer["read_token"])
        path = "/v1/blobs/" + blob_id
        remote = {"relay": self.base_url, "role": "receiver", "read_token": token}
        directory = Path(directory)
        if (directory / STATE_FILE).exists():
            staged = StagedBlob.open(directory, binding)
            expected = StagedBlob(directory, private, staged.public, remote)
            if staged.private != expected.private or staged.remote != remote:
                raise BlobError("relay_checkpoint_mismatch", 409)
        else:
            public = manifest(self._json("GET", path, token))
            if sha256(canonical(public)) != private.get("manifest_sha256"):
                raise BlobError("manifest_hash_mismatch", 409)
            try:
                staged = StagedBlob.receive(directory, private, public, binding, remote)
            except FileExistsError:
                raise BlobError("transfer_busy", 409) from None
        with staged.exclusive():
            if staged.remote != remote:
                raise BlobError("relay_checkpoint_mismatch", 409)
            return self._download(staged, path, token, binding, progress=progress, cancel=cancel)

    def _download(self, staged: StagedBlob, path: str, token: str, binding: dict, *, progress, cancel):
        private = staged.private
        missing = [i for i in range(len(staged.public["chunks"])) if not staged.has_chunk(i)]
        done = private["size"] - sum(staged.public["chunks"][i]["size"] - TAG_BYTES for i in missing)
        if progress:
            progress(done, private["size"])
        for index in missing:
            self._cancelled(cancel)
            data = self._request("GET", path + f"/chunks/{index}", token, maximum=CHUNK_BYTES + TAG_BYTES)
            staged.store_chunk(index, data)
            done += len(data) - TAG_BYTES
            if progress:
                progress(done, private["size"])
        # Authenticate before reporting a verified download. Plaintext remains in memory.
        try:
            for _chunk in staged.plaintext(binding):
                self._cancelled(cancel)
        except InvalidTag:
            raise BlobError("chunk_authentication_failed", 409) from None
        return staged

    def revoke(self, staged: StagedBlob):
        with staged.exclusive():
            if staged.remote.get("role") != "sender" or staged.remote.get("relay") != self.base_url:
                raise BlobError("relay_checkpoint_mismatch", 409)
            self._request("DELETE", "/v1/blobs/" + staged.private["blob_id"], staged.remote["write_token"])
