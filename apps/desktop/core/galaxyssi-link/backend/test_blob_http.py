"""Real socket/TLS tests; no public relay, broker or operator database is used."""
from contextlib import closing
from datetime import datetime, timedelta, timezone
import hashlib
import ipaddress
import os
from pathlib import Path
import secrets
import socket
import sqlite3
import ssl
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import FastAPI, Request
from fastapi.responses import RedirectResponse, Response
import httpx
import uvicorn

from blob_client import BlobClient
from blob_crypto import StagedBlob
from blob_protocol import BlobError, CHUNK_BYTES, MAX_MANIFEST_BYTES, canonical
from blob_relay import create_app
from blob_store import BlobStore
from test_blob_crypto import BINDING


def resident_bytes():
    if os.name == "nt":
        import ctypes
        from ctypes import wintypes
        class Counters(ctypes.Structure):
            _fields_ = [("cb", wintypes.DWORD), ("faults", wintypes.DWORD)] + [
                (name, ctypes.c_size_t) for name in (
                    "peak_working_set", "working_set", "peak_paged", "paged",
                    "peak_nonpaged", "nonpaged", "pagefile", "peak_pagefile", "private")]
        result = Counters()
        result.cb = ctypes.sizeof(result)
        read = ctypes.windll.psapi.GetProcessMemoryInfo
        read.argtypes = [wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD]
        read.restype = wintypes.BOOL
        if not read(wintypes.HANDLE(-1), ctypes.byref(result), result.cb):
            raise ctypes.WinError()
        return result.working_set
    if Path("/proc/self/statm").exists():
        return int(Path("/proc/self/statm").read_text().split()[1]) * os.sysconf("SC_PAGE_SIZE")
    return 0


class MemorySampler:
    def __init__(self):
        self.before = self.peak = resident_bytes()
        self.done = threading.Event()
        def sample():
            while not self.done.wait(.005):
                self.peak = max(self.peak, resident_bytes())
        self.thread = threading.Thread(target=sample)
        self.thread.start()

    def stop(self):
        self.done.set()
        self.thread.join(3)
        if self.thread.is_alive():
            raise RuntimeError("Memory sampler did not stop")


class LocalRelay:
    def __init__(self, app, root: Path, *, tls=False, port=0):
        sock = socket.socket()
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("127.0.0.1", port))
        sock.listen(128)
        self.sock = sock
        self.closed = False
        self.port = sock.getsockname()[1]
        self.origin = ("https" if tls else "http") + f"://127.0.0.1:{self.port}"
        kwargs = {}
        self.context = None
        if tls:
            key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
            name = x509.Name([x509.NameAttribute(x509.NameOID.COMMON_NAME, "Blob test only")])
            now = datetime.now(timezone.utc)
            cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
                .public_key(key.public_key()).serial_number(x509.random_serial_number())
                .not_valid_before(now - timedelta(minutes=1)).not_valid_after(now + timedelta(hours=1))
                .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), False)
                .add_extension(x509.BasicConstraints(ca=True, path_length=None), True)
                .sign(key, hashes.SHA256()))
            cert_path, key_path = root / "test-cert.pem", root / "test-key.pem"
            cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
            key_path.write_bytes(key.private_bytes(serialization.Encoding.PEM,
                                  serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
            self.context = ssl.create_default_context(cafile=str(cert_path))
            kwargs = {"ssl_certfile": str(cert_path), "ssl_keyfile": str(key_path)}
        config = uvicorn.Config(app, log_level="error", access_log=False, proxy_headers=False,
                                timeout_graceful_shutdown=3, **kwargs)
        self.server = uvicorn.Server(config)
        self.thread = threading.Thread(target=self.server.run, kwargs={"sockets": [sock]}, daemon=True)
        self.thread.start()
        deadline = time.monotonic() + 10
        while not self.server.started and self.thread.is_alive() and time.monotonic() < deadline:
            time.sleep(.01)
        if not self.server.started:
            self.close()
            raise RuntimeError("Isolated test relay did not start")

    def close(self):
        if self.closed:
            return
        self.server.should_exit = True
        self.thread.join(10)
        self.sock.close()
        if self.thread.is_alive():
            raise RuntimeError("Isolated relay did not stop")
        self.closed = True


class BlobHttpFixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "original.bin"
        self.source.write_bytes(b"private media payload" * 100)
        self.token = secrets.token_hex(32)
        self.store = BlobStore(self.root / "relay.sqlite3")
        self.app = create_app(self.store, self.token)
        self.connections = set()
        @self.app.middleware("http")
        async def connections(request, call_next):
            self.connections.add(request.client.port)
            return await call_next(request)
        self.relay = LocalRelay(self.app, self.root)
        self.addCleanup(self.relay.close)
        self.client = BlobClient(self.relay.origin, provisioning_token=self.token,
                                 allow_loopback_http=True, trust_env=False)
        self.addCleanup(self.client.close)

    def staged(self):
        return StagedBlob.prepare(self.source, self.root / "sender", BINDING)


class BlobHttpTest(BlobHttpFixture):
    def test_offer_is_available_before_bulk_bytes_for_early_attachment_card(self):
        staged = self.staged()
        observed = []
        def on_offer(offer):
            status = self.store.status(staged.private["blob_id"], staged.remote["write_token"])
            self.assertFalse(status["complete"])
            self.assertEqual(staged.public, self.store.get_manifest(staged.private["blob_id"], offer["read_token"]))
            observed.append(offer)
        result = self.client.upload(staged, on_offer=on_offer)
        self.assertEqual([result], observed)

    def test_duplicate_sender_cannot_replace_capabilities(self):
        staged = self.staged()
        reopened = StagedBlob.open(staged.directory, BINDING)
        with staged.exclusive(), self.assertRaisesRegex(BlobError, "transfer_busy"):
            self.client.upload(reopened)
        self.assertEqual({}, StagedBlob.open(staged.directory, BINDING).remote)
        self.client.upload(staged)
        old_capability = staged.remote["write_token"]
        self.client.upload(reopened)
        self.assertEqual(old_capability, reopened.remote["write_token"])

    def test_real_http_roundtrip_offer_is_small_and_no_plaintext_relay(self):
        staged = self.staged()
        sent, received = [], []
        offer = self.client.upload(staged, progress=lambda done, total: sent.append((done, total)))
        self.assertLess(len(canonical(offer)), 1024)
        self.assertNotIn("write_token", offer)
        self.assertNotIn(self.token, canonical(offer).decode())
        result = self.client.download(offer, self.root / "receiver", BINDING,
                                      progress=lambda done, total: received.append((done, total)))
        self.assertEqual(self.source.read_bytes(), b"".join(result.plaintext(BINDING)))
        self.assertEqual(sent[-1], received[-1])
        self.assertEqual(self.source.stat().st_size, received[-1][0])
        self.assertNotIn(b"private media payload", self.store.path.read_bytes())

    def test_interrupted_upload_resumes_only_missing_blocks_after_reopen(self):
        self.source.write_bytes(b"a" * CHUNK_BYTES + b"b" * CHUNK_BYTES + b"c" * 512)
        staged = self.staged()
        stop = False
        def progress(done, total):
            nonlocal stop
            stop = done >= CHUNK_BYTES
        with self.assertRaisesRegex(BlobError, "transfer_cancelled"):
            self.client.upload(staged, progress=progress, cancel=lambda: stop)
        reopened = StagedBlob.open(staged.directory, BINDING)
        self.store = BlobStore(self.store.path)
        self.assertEqual("06", self.store.status(staged.private["blob_id"], staged.remote["write_token"])["missing_bitmap"])
        calls = []
        original = self.client._request
        def observe(method, path, token, **kwargs):
            if method == "PUT" and "/chunks/" in path:
                calls.append(path.rsplit("/", 1)[-1])
            return original(method, path, token, **kwargs)
        with patch.object(self.client, "_request", side_effect=observe):
            self.client.upload(reopened)
        self.assertEqual(["1", "2"], calls)

    def test_lost_create_and_put_responses_do_not_duplicate_transfer(self):
        staged = self.staged()
        original = self.client._request
        failures = {"create": True, "chunk": True}
        def lose_response(method, path, token, **kwargs):
            result = original(method, path, token, **kwargs)
            kind = "chunk" if "/chunks/" in path else "create"
            if method == "PUT" and failures[kind]:
                failures[kind] = False
                raise BlobError("relay_connection_failed", 503)
            return result
        for _ in range(2):
            with patch.object(self.client, "_request", side_effect=lose_response), self.assertRaises(BlobError):
                self.client.upload(StagedBlob.open(staged.directory, BINDING) if staged.remote else staged)
        resumed = StagedBlob.open(staged.directory, BINDING)
        self.client.upload(resumed)
        with closing(sqlite3.connect(self.store.path)) as db:
            self.assertEqual(1, db.execute("SELECT COUNT(*) FROM sessions").fetchone()[0])
            self.assertEqual(1, db.execute("SELECT COUNT(*) FROM chunks").fetchone()[0])

    def test_receiver_restart_and_local_corruption_redownload_only_missing(self):
        self.source.write_bytes(b"a" * CHUNK_BYTES + b"b" * CHUNK_BYTES + b"c" * 17)
        staged = self.staged()
        offer = self.client.upload(staged)
        stop = False
        def progress(done, total):
            nonlocal stop
            stop = done >= 2 * CHUNK_BYTES
        destination = self.root / "receiver"
        with self.assertRaisesRegex(BlobError, "transfer_cancelled"):
            self.client.download(offer, destination, BINDING, progress=progress, cancel=lambda: stop)
        (destination / "00000000.blob").write_bytes(b"corrupt")
        calls = []
        original = self.client._request
        def observe(method, path, token, **kwargs):
            if "/chunks/" in path:
                calls.append(path.rsplit("/", 1)[-1])
            return original(method, path, token, **kwargs)
        with patch.object(self.client, "_request", side_effect=observe):
            result = self.client.download(offer, destination, BINDING)
        self.assertEqual(["0", "2"], calls)
        self.assertEqual(staged.private["sha256"], hashlib.sha256(b"".join(result.plaintext(BINDING))).hexdigest())

    def test_relay_restarts_without_sender_or_receiver_identity_loss(self):
        staged = self.staged()
        offer = self.client.upload(staged)
        self.relay.close()
        # Bind the same origin to a fresh server/store instance, as after a process restart.
        restarted = LocalRelay(create_app(BlobStore(self.store.path), self.token), self.root, port=self.relay.port)
        self.addCleanup(restarted.close)
        result = self.client.download(offer, self.root / "after-restart", BINDING)
        self.assertEqual(self.source.read_bytes(), b"".join(result.plaintext(BINDING)))

    def test_read_capability_cannot_modify_or_revoke(self):
        staged = self.staged()
        offer = self.client.upload(staged)
        path = "/v1/blobs/" + staged.private["blob_id"]
        for method, suffix in (("DELETE", ""), ("PUT", "/chunks/0"), ("GET", "/missing")):
            with self.subTest(method=method), self.assertRaises(BlobError) as error:
                self.client._request(method, path + suffix, offer["read_token"])
            self.assertEqual(404, error.exception.status)
        self.client.revoke(staged)
        with self.assertRaises(BlobError) as error:
            self.client.download(offer, self.root / "revoked", BINDING)
        self.assertEqual(404, error.exception.status)

    def test_wrong_binding_is_rejected_before_any_ciphertext_fetch(self):
        offer = self.client.upload(self.staged())
        with self.assertRaisesRegex(BlobError, "transfer_binding_mismatch"):
            self.client.download(offer, self.root / "wrong", {**BINDING, "conversation_id": "other"})
        self.assertFalse((self.root / "wrong").exists())

    def test_resume_revalidates_private_descriptor_types(self):
        self.source.write_bytes(b"x")
        offer = self.client.upload(self.staged())
        directory = self.root / "receiver"
        self.client.download(offer, directory, BINDING)
        offer["private"]["size"] = True
        with self.assertRaisesRegex(BlobError, "invalid_file_size"):
            self.client.download(offer, directory, BINDING)

    def test_wrong_key_reports_authentication_failure_not_empty_error(self):
        offer = self.client.upload(self.staged())
        offer["private"]["key"] = "f" * 64
        with self.assertRaisesRegex(BlobError, "chunk_authentication_failed"):
            self.client.download(offer, self.root / "wrong-key", BINDING)

    def test_auth_before_body_and_streamed_body_limit(self):
        session_id = secrets.token_hex(16)
        with httpx.Client(trust_env=False) as client:
            response = client.put(self.relay.origin + "/v1/blobs/" + session_id,
                                  content=b"x" * (MAX_MANIFEST_BYTES + 1))
            self.assertEqual(401, response.status_code)
            response = client.put(self.relay.origin + "/v1/blobs/" + session_id,
                headers={"Authorization": "Bearer " + self.token},
                content=iter([b"x" * 65536] * 3))
            self.assertEqual(413, response.status_code)
            self.assertEqual("no-store", response.headers["cache-control"])

    def test_slow_upload_does_not_block_control_status(self):
        self.source.write_bytes(b"x" * CHUNK_BYTES)
        staged = self.staged()
        read, write = secrets.token_hex(32), secrets.token_hex(32)
        session_id = staged.private["blob_id"]
        self.store.create(session_id, staged.public, read, write)
        entered, release = threading.Event(), threading.Event()
        results = []
        data = staged.read_chunk(0)
        def body():
            yield data[:512]
            entered.set()
            if not release.wait(10):
                raise RuntimeError("Test did not release upload")
            yield data[512:]
        def upload():
            try:
                with httpx.Client(trust_env=False) as client:
                    response = client.put(self.relay.origin + f"/v1/blobs/{session_id}/chunks/0",
                        headers={"Authorization": "Bearer " + write}, content=body())
                    results.append(response.status_code)
            except Exception as error:
                results.append(type(error).__name__)
        worker = threading.Thread(target=upload)
        worker.start()
        try:
            self.assertTrue(entered.wait(5))
            started = time.monotonic()
            status = self.client._json("GET", f"/v1/blobs/{session_id}/missing", write)
            self.assertLess(time.monotonic() - started, 2)
            self.assertEqual("01", status["missing_bitmap"])
            self.assertTrue(worker.is_alive())
        finally:
            release.set()
            worker.join(10)
        self.assertFalse(worker.is_alive())
        self.assertEqual([200], results)

    def test_https_certificate_validation_and_no_plain_http_by_default(self):
        with self.assertRaisesRegex(BlobError, "requires_https"):
            BlobClient(self.relay.origin)
        for url in ("http://example.com", "https://user:password@example.com", "https://example.com/path"):
            with self.subTest(url=url), self.assertRaises(BlobError):
                BlobClient(url, allow_loopback_http=True)
        context = ssl._create_unverified_context()
        with self.assertRaisesRegex(BlobError, "tls_verification_required"):
            BlobClient("https://example.com", tls_context=context)
        tls = LocalRelay(create_app(BlobStore(self.root / "tls.sqlite3"), self.token), self.root, tls=True)
        self.addCleanup(tls.close)
        staged = self.staged()
        with BlobClient(tls.origin, provisioning_token=self.token, trust_env=False) as untrusted:
            with self.assertRaisesRegex(BlobError, "relay_tls_verification_failed"):
                untrusted.upload(staged)
        with BlobClient(tls.origin, provisioning_token=self.token, tls_context=tls.context,
                        trust_env=False) as trusted:
            offer = trusted.upload(StagedBlob.open(staged.directory, BINDING))
            result = trusted.download(offer, self.root / "tls-receiver", BINDING)
            self.assertEqual(self.source.read_bytes(), b"".join(result.plaintext(BINDING)))

    def test_redirect_does_not_forward_capability(self):
        attacker_calls = []
        app = FastAPI()
        @app.api_route("/{path:path}", methods=["PUT", "GET"])
        async def redirect(path: str, request: Request):
            if path == "stolen":
                attacker_calls.append(request.headers.get("authorization"))
                return Response(b"no")
            return RedirectResponse("/stolen", status_code=307)
        relay = LocalRelay(app, self.root)
        self.addCleanup(relay.close)
        with BlobClient(relay.origin, provisioning_token=self.token,
                        allow_loopback_http=True, trust_env=False) as client:
            with self.assertRaisesRegex(BlobError, "redirect_rejected"):
                client.upload(self.staged())
        self.assertEqual([], attacker_calls)

    def test_large_transfer_is_bounded_and_uses_one_reused_http_connection(self):
        size_mib = int(os.environ.get("GALAXYSSI_BLOB_LARGE_TEST_MIB", "3"))
        if not 1 <= size_mib <= 1024:
            self.fail("Invalid large-transfer test size")
        block = os.urandom(CHUNK_BYTES)
        with self.source.open("wb") as output:
            for _ in range(size_mib):
                output.write(block)
        memory = MemorySampler()
        self.addCleanup(memory.stop)
        started = time.monotonic()
        staged = self.staged()
        prepared = time.monotonic()
        offer = self.client.upload(staged)
        uploaded = time.monotonic()
        result = self.client.download(offer, self.root / "large-receiver", BINDING)
        downloaded = time.monotonic()
        digest = hashlib.sha256()
        for plain in result.plaintext(BINDING):
            self.assertLessEqual(len(plain), CHUNK_BYTES)
            digest.update(plain)
        self.assertEqual(staged.private["sha256"], digest.hexdigest())
        with closing(sqlite3.connect(self.store.path)) as db:
            count, maximum = db.execute("SELECT COUNT(*),MAX(size) FROM chunks").fetchone()
        self.assertEqual(size_mib, count)
        self.assertEqual(CHUNK_BYTES + 16, maximum)
        self.assertEqual(1, len(self.connections))
        memory.stop()
        if size_mib >= 100 and memory.before:
            self.assertLess(memory.peak - memory.before, 96 * CHUNK_BYTES)
        print(f"Blob real HTTP: {size_mib} MiB, {count} chunks, "
              f"prepare={prepared-started:.3f}s upload={uploaded-prepared:.3f}s "
              f"download+auth={downloaded-uploaded:.3f}s total={time.monotonic()-started:.3f}s "
              f"sampled_RSS_growth={(memory.peak-memory.before)/CHUNK_BYTES:.3f} MiB "
              f"connections={len(self.connections)}")
