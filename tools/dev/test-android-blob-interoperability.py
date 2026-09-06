"""Real HTTPS Kotlin/Python Blob interoperability; isolated state, no production Desktop or phone."""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timedelta, timezone
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import secrets
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[2]
BACKEND = ROOT / "apps/desktop/core/galaxyssi-link/backend"


def certificate(root: Path):
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "GalaxySSI isolated Blob test")])
    now = datetime.now(timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now - timedelta(minutes=5))
            .not_valid_after(now + timedelta(days=1))
            .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), critical=False)
            .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True).sign(key, hashes.SHA256()))
    (root / "relay.crt").write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    (root / "relay.key").write_bytes(key.private_bytes(serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assemble", action="store_true", help="Also build the App and instrumentation APKs")
    options = parser.parse_args()
    original_environment = dict(os.environ)
    with tempfile.TemporaryDirectory(prefix="galaxyssi-blob-interop-") as temporary:
        root = Path(temporary)
        # Set before importing backend modules that initialize device-bound storage.
        os.environ.update({"HOME": temporary, "USERPROFILE": temporary, "APPDATA": temporary,
                           "GALAXYSSI_STATE_DIR": str(root / "state")})
        sys.path.insert(0, str(BACKEND))
        import uvicorn
        from blob_client import BlobClient
        from blob_crypto import StagedBlob
        from blob_relay import create_app
        from blob_store import BlobStore

        certificate(root)
        context = ssl.create_default_context(cafile=str(root / "relay.crt"))
        token = secrets.token_hex(32)
        relay = create_app(BlobStore(root / "relay.sqlite3"), provisioning_token=token)
        uploads: Counter[tuple[str, int]] = Counter()

        @relay.middleware("http")
        async def count_chunks(request, call_next):
            response = await call_next(request)
            parts = request.url.path.split("/")
            if request.method == "PUT" and len(parts) == 6 and parts[4] == "chunks" and response.status_code == 200:
                uploads[(parts[3], int(parts[5]))] += 1
            return response

        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        listener.listen(128)
        origin = f"https://127.0.0.1:{listener.getsockname()[1]}"
        server = uvicorn.Server(uvicorn.Config(relay, log_level="critical", access_log=False,
            ssl_certfile=str(root / "relay.crt"), ssl_keyfile=str(root / "relay.key")))
        thread = threading.Thread(target=server.run, kwargs={"sockets": [listener]}, daemon=True)
        thread.start()
        try:
            deadline = time.monotonic() + 10
            while not server.started:
                if not thread.is_alive() or time.monotonic() > deadline:
                    raise RuntimeError("Isolated HTTPS relay did not become ready")
                time.sleep(0.02)
            source = root / "source.bin"
            data = bytes(index % 251 for index in range(3 * 1024 * 1024 + 73))
            source.write_bytes(data)
            expected_hash = hashlib.sha256(data).hexdigest()
            binding = {"client_route_id": "interop-route", "conversation_id": "\u6d4b\u8bd5\u4f1a\u8bdd",
                       "task_id": "interop-task", "turn_id": "interop-turn", "attachment_id": "interop-file"}
            with BlobClient(origin, provisioning_token=token, tls_context=context, trust_env=False) as client:
                staged = StagedBlob.prepare(source, root / "python-sender", binding)
                offer = client.upload(staged)
                (root / "fixture.json").write_text(json.dumps({"offer": offer, "token": token,
                    "binding": binding, "sha256": expected_hash}), encoding="utf-8")
                log = ROOT / "build/android-blob-interop-gradle.log"
                log.parent.mkdir(parents=True, exist_ok=True)
                environment = {**original_environment, "GALAXYSSI_BLOB_TEST_ROOT": temporary}
                command = [str(ROOT / "apps/android/gradlew.bat" if os.name == "nt" else ROOT / "apps/android/gradlew"),
                    ":app:testDebugUnitTest", "--tests", "com.galaxyssi.chat.blob.*", "--tests",
                    "com.galaxyssi.chat.AttachmentAtRestCipherTest", "--tests",
                    "com.galaxyssi.chat.AgentAttachmentTransferProtocolTest", "--tests",
                    "com.galaxyssi.chat.AgentAttachmentPublishOrderTest", "--tests",
                    "com.galaxyssi.chat.AttachmentControlInboxTest", "--tests",
                    "com.galaxyssi.chat.PeerAttachmentTransferProgressTest", "--tests",
                    "com.galaxyssi.chat.PeerChatAttachmentTest", "--console=plain"]
                if options.assemble:
                    command.extend([":app:assembleDebug", ":app:assembleDebugAndroidTest"])
                with log.open("w", encoding="utf-8") as output:
                    result = subprocess.run(command, cwd=ROOT / "apps/android", env=environment,
                        stdout=output, stderr=subprocess.STDOUT,
                        creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
                if result.returncode:
                    print(f"Android regression failed; see {log}", flush=True)
                    return result.returncode
                result_path = root / "kotlin-offer.json"
                if not result_path.is_file():
                    raise RuntimeError("Kotlin interoperability case did not run; a cached or skipped test is not evidence")
                kotlin_offer = json.loads(result_path.read_text(encoding="utf-8"))
                received = client.download(kotlin_offer, root / "python-receiver", binding)
                digest = hashlib.sha256()
                for chunk in received.plaintext(binding):
                    digest.update(chunk)
                if digest.hexdigest() != expected_hash:
                    raise RuntimeError("Python could not verify the Kotlin ciphertext")
                blob_id = kotlin_offer["private"]["blob_id"]
                counts = [uploads[(blob_id, index)] for index in range(4)]
                if counts != [1, 1, 1, 1]:
                    raise RuntimeError(f"Kotlin resume resent accepted chunks: {counts}")
                print(json.dumps({"result": "passed", "transport": "real_loopback_https", "bytes_each_direction": len(data),
                    "kotlin_upload_counts": counts, "sha256_verified": True, "phone_test": False}), flush=True)
        finally:
            server.should_exit = True
            thread.join(timeout=10)
            listener.close()
            if thread.is_alive():
                raise RuntimeError("Isolated test relay did not stop")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
