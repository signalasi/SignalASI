"""Real Android HTTPS Blob transfer via ADB reverse; isolated data, no pairing or model changes."""
import argparse
import asyncio
from collections import Counter
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import secrets
import subprocess
import sys
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[2]
BACKEND = ROOT / "apps/desktop/core/galaxyssi-link/backend"
TEST_CLASS = "com.galaxyssi.chat.blob.BlobHttpsDeviceTest"
RUNNER = "com.galaxyssi.chat.test/androidx.test.runner.AndroidJUnitRunner"


def checked_version(package_details: str, expected: str) -> str:
    matches = [line.partition("=")[2].strip() for line in package_details.splitlines()
               if line.strip().startswith("versionName=")]
    if len(matches) != 1 or matches[0].strip() != expected:
        raise RuntimeError(f"Installed App version does not match required test version {expected}")
    return matches[0].strip()


def pattern_file(path: Path, size: int) -> str:
    pattern = bytes(range(251)) * 8192
    digest = hashlib.sha256()
    written = 0
    with path.open("wb") as output:
        while written < size:
            length = min(1024 * 1024, size - written)
            start = written % 251
            block = pattern[start:start + length]
            output.write(block)
            digest.update(block)
            written += len(block)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Explicit S20U ADB serial; no other device is touched")
    parser.add_argument("--expected-app-version", required=True, help="Reject an old installed App before changing test state")
    parser.add_argument("--size-mib", type=int, default=16, choices=range(2, 513), metavar="2..512")
    parser.add_argument("--test-apk", type=Path,
                        default=ROOT / "apps/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
    parser.add_argument("--adb", default=str(Path(os.environ.get("ANDROID_HOME", "")) / "platform-tools/adb.exe")
                        if os.name == "nt" else "adb")
    args = parser.parse_args()
    run_id = secrets.token_hex(16)
    log_root = ROOT / "build" / f"blob-device-{args.size_mib}mib-{run_id[:8]}"
    log_root.mkdir(parents=True)

    def adb(*command, timeout=90, check=True):
        result = subprocess.run([args.adb, "-s", args.serial, *command], stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", timeout=timeout,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        if check and result.returncode:
            raise RuntimeError(f"ADB {command[0]} failed: {result.stdout[-1500:]}")
        return result

    model = adb("shell", "getprop", "ro.product.model").stdout.strip()
    if model.replace("_", "-") != "SM-G9880":
        raise RuntimeError("This acceptance runner targets only the explicitly selected S20U")
    if not adb("shell", "pm", "path", "com.galaxyssi.chat").stdout.startswith("package:"):
        raise RuntimeError("Install the development App first; this runner never installs or resets the production App")
    app_version = checked_version(adb("shell", "dumpsys", "package", "com.galaxyssi.chat").stdout,
                                  args.expected_app_version)
    if not args.test_apk.is_file():
        raise RuntimeError("Build the instrumentation APK before running this test")
    if adb("shell", "pm", "path", "com.galaxyssi.chat.test", check=False).stdout.startswith("package:"):
        raise RuntimeError("An instrumentation package is already installed; inspect its owner before replacing it")

    with tempfile.TemporaryDirectory(prefix="galaxyssi-blob-device-") as temporary:
        root = Path(temporary)
        os.environ.update({"HOME": temporary, "USERPROFILE": temporary, "APPDATA": temporary,
                           "GALAXYSSI_STATE_DIR": str(root / "state"),
                           "GALAXYSSI_WORKSPACE_ROOT": str(root / "workspace")})
        sys.path.insert(0, str(BACKEND))
        from fastapi import Request
        from fastapi.responses import JSONResponse
        from blob_client import BlobClient
        from blob_crypto import StagedBlob
        from blob_input_receiver import BlobInputReceiver
        from blob_relay import create_app
        from blob_store import BlobStore
        from input_attachment_transfer import resolved_attachment_path, transfer_id_for
        from link_protocol import new_route_id
        from test_blob_http import LocalRelay

        token = secrets.token_hex(32)
        app = create_app(BlobStore(root / "relay.sqlite3"), token)
        state = {"receipt": None, "offer": None, "events": {}, "delay_injected": False}
        puts = Counter()
        lock = threading.Lock()
        receiver = None

        @app.middleware("http")
        async def record_and_delay(request, call_next):
            parts = request.url.path.split("/")
            if request.method == "PUT" and len(parts) == 6 and parts[4] == "chunks":
                with lock:
                    phone_id = (state["offer"] or {}).get("blob_offer", {}).get("private", {}).get("blob_id")
                    delay = parts[3] == phone_id and parts[5] == "1" and not state["delay_injected"]
                    if delay:
                        state["delay_injected"] = True
                if delay:
                    await asyncio.sleep(2)
            response = await call_next(request)
            if request.method == "PUT" and len(parts) == 6 and parts[4] == "chunks" and response.status_code == 200:
                with lock:
                    puts[(parts[3], int(parts[5]))] += 1
            return response

        @app.api_route("/__test/{action}", methods=["GET", "POST"])
        async def control(action: str, request: Request):
            if not secrets.compare_digest(request.headers.get("authorization", ""), "Bearer " + token):
                return JSONResponse({"error": "unauthorized"}, status_code=401)
            if action == "status" and request.method == "GET":
                with lock:
                    return {"receipt": state["receipt"]}
            body = await request.body()
            if len(body) > 128 * 1024:
                return JSONResponse({"error": "body_too_large"}, status_code=413)
            value = json.loads(body)
            if action == "offer":
                receiver.enqueue(value, manifest["client_route_id"], "device-test-phone")
                with lock:
                    state["offer"] = value
                receiver.start()
            elif action == "events":
                with lock:
                    state["events"][value["phase"]] = value["metrics"]
            else:
                return JSONResponse({"error": "not_found"}, status_code=404)
            return {"ok": True}

        relay = LocalRelay(app, root, tls=True)
        remote = PurePosixPath("/sdcard/Android/data/com.galaxyssi.chat/files") / f"blob-device-{run_id}"
        assert remote.parent == PurePosixPath("/sdcard/Android/data/com.galaxyssi.chat/files")
        installed = mapped = fixture_sent = False
        result = None

        def phase(name, *, expected_death=False):
            print(json.dumps({"phase": name, "model": model, "size_mib": args.size_mib}), flush=True)
            result = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", TEST_CLASS,
                         "-e", "blobDeviceRun", run_id, "-e", "blobDevicePhase", name, RUNNER,
                         timeout=600, check=False)
            (log_root / f"{name}.log").write_text(result.stdout, encoding="utf-8")
            if expected_death:
                with lock:
                    interrupted = state["events"].get("interrupting")
                if not interrupted or "Process crashed" not in result.stdout:
                    raise RuntimeError(f"Intentional process death was not observed; see {log_root}")
                if adb("shell", "pidof", "com.galaxyssi.chat", check=False).stdout.strip():
                    raise RuntimeError("App process unexpectedly survived the interruption")
            elif result.returncode or "OK (1 test)" not in result.stdout or "INSTRUMENTATION_STATUS_CODE: -4" in result.stdout:
                raise RuntimeError(f"Device phase {name} did not pass; see {log_root}")

        try:
            size = args.size_mib * 1024 * 1024
            source_hash = pattern_file(root / "source.bin", size)
            manifest = {"client_route_id": new_route_id(), "conversation_id": "\u771f\u673a\u9644\u4ef6\u6062\u590d\u6d4b\u8bd5-" + run_id,
                        "task_id": "task-" + run_id, "turn_id": "turn-" + run_id, "attachment_id": "file-" + run_id,
                        "contact_id": "device-test-contact", "client_message_id": 7, "attachment_ordinal": 0,
                        "name": "\u52a0\u5bc6\u9644\u4ef6.bin", "mime_type": "application/octet-stream",
                        "size_bytes": size, "sha256": source_hash, "chunk_size_bytes": 256 * 1024, "chunk_count": args.size_mib * 4}
            manifest["transfer_id"] = transfer_id_for(*(manifest[key] for key in (
                "client_route_id", "conversation_id", "task_id", "turn_id", "attachment_id", "sha256")))
            def publish(_route, _source, _fingerprint, receipt):
                if (_route, _source, _fingerprint) != (manifest["client_route_id"], "device-test-phone", "e" * 64):
                    raise RuntimeError("Receiver changed the authenticated fixture identity")
                with lock:
                    state["receipt"] = receipt
                return True
            receiver = BlobInputReceiver(root / "receiver", configured_origin=lambda: relay.origin,
                peer_identity=lambda route, source: "e" * 64 if route == manifest["client_route_id"] and source == "device-test-phone" else None,
                publish_receipt=publish,
                client_factory=lambda origin: BlobClient(origin, tls_context=relay.context, trust_env=False))
            return_size = 3 * 1024 * 1024 + 73
            return_hash = pattern_file(root / "return.bin", return_size)
            return_binding = {"conversation_id": "\u4e0b\u884c\u6d4b\u8bd5-" + run_id, "attachment_id": "return-" + run_id}
            with BlobClient(relay.origin, provisioning_token=token, tls_context=relay.context, trust_env=False) as host_client:
                returning = StagedBlob.prepare(root / "return.bin", root / "return-staging", return_binding)
                return_offer = host_client.upload(returning)
                fixture = {"origin": relay.origin, "token": token, "manifest": manifest, "return_offer": return_offer,
                           "return_binding": return_binding, "return_sha256": return_hash}
                (root / "fixture.json").write_text(json.dumps(fixture), encoding="utf-8")
                adb("install", "--no-streaming", "-r", str(args.test_apk)); installed = True
                adb("reverse", "--no-rebind", f"tcp:{relay.port}", f"tcp:{relay.port}"); mapped = True
                adb("shell", "mkdir", "-p", str(remote)); fixture_sent = True
                for name in ("fixture.json", "test-cert.pem"):
                    adb("push", str(root / name), str(remote / name))
                phase("interrupt", expected_death=True)
                phase("resume")
                with lock:
                    offer = state["offer"]
                    blob_id = offer["blob_offer"]["private"]["blob_id"]
                    counts = [puts[(blob_id, index)] for index in range(args.size_mib)]
                    events = dict(state["events"])
                if counts != [1] * args.size_mib:
                    raise RuntimeError("Accepted upload chunks were retransmitted or lost")
                completed = resolved_attachment_path(manifest, **{key: manifest[key] for key in (
                    "client_route_id", "conversation_id", "task_id", "turn_id")})
                if completed is None:
                    raise RuntimeError("Desktop did not commit a scoped input file")
                digest = hashlib.sha256()
                with completed.open("rb") as data:
                    for block in iter(lambda: data.read(1024 * 1024), b""):
                        digest.update(block)
                actual = digest.hexdigest()
                if actual != source_hash or "completed" not in events:
                    raise RuntimeError("Cross-device file or completion verification failed")
                result = {"result": "passed", "phone_model": model, "app_version": app_version,
                          "transport": "https_over_adb_reverse",
                          "upload_bytes": size, "return_bytes": return_size, "sha256_verified": True,
                          "accepted_chunks": len(counts), "each_chunk_uploaded_once": True,
                          "intentional_app_process_death": True, "metrics": events,
                          "production_pairing_or_mqtt_test": False}
                host_client.revoke(returning)
        finally:
            cleanup_errors = []
            def cleanup(action):
                try:
                    action()
                except Exception as error:
                    cleanup_errors.append(type(error).__name__)
            if installed:
                if fixture_sent:
                    cleanup(lambda: phase("cleanup"))
                cleanup(lambda: adb("uninstall", "com.galaxyssi.chat.test"))
                cleanup(lambda: adb("shell", "am", "start", "-n", "com.galaxyssi.chat/.StartupActivity"))
            if mapped:
                cleanup(lambda: adb("reverse", "--remove", f"tcp:{relay.port}"))
            if receiver:
                receiver.stop()
                if not receiver.wait_stopped(70):
                    cleanup_errors.append("ReceiverDidNotStop")
            cleanup(relay.close)
            if cleanup_errors:
                raise RuntimeError("Test cleanup failed: " + ",".join(cleanup_errors))
        (log_root / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
        print(json.dumps(result), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
