#!/usr/bin/env python3
"""SignalASI Linux guest execution service."""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import re
import signal
import stat
import struct
import subprocess
import threading
import time
import uuid
from collections import OrderedDict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, BinaryIO


PROTOCOL_VERSION = 1
MAX_FRAME_BYTES = 1024 * 1024
MAX_CLOCK_SKEW_MILLIS = 5 * 60_000
CHANNEL_NAME = "org.signalasi.runtime"
VIRTIO_PORT_CLASS_ROOT = Path("/sys/class/virtio-ports")
DEVICE_ROOT = Path("/dev")
SESSION_PATH = Path("/sys/firmware/qemu_fw_cfg/by_name/opt/com.signalasi/runtime-session/raw")
CONFIG_PATH = Path("/sys/firmware/qemu_fw_cfg/by_name/opt/com.signalasi/runtime-config/raw")
WORKSPACE_ROOT = Path("/workspace")
ISOLATED_WORKSPACE_ROOT = Path("/work")
PACK_ROOT = Path("/opt/signalasi/packs")
PACK_NAMESPACE_ROOT = PACK_ROOT.parent
PACK_DESCRIPTOR_NAME = "signalasi-pack.json"
LAUNCHER_PATH = Path("/usr/libexec/signalasi-runtime-launcher")
REQUEST_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
MAX_SEQUENCE_WINDOWS = 8192
MAX_CONCURRENT_EXECUTIONS = 16
MIN_TRUSTED_EPOCH_MILLIS = 1_577_836_800_000
MAX_TRUSTED_EPOCH_MILLIS = 4_102_444_800_000
PACK_ENTRYPOINTS = {
    "python-uv": ("bin/python3", "bin/uv"),
    "node-js": ("bin/node", "bin/tsx"),
    "go": ("bin/go",),
    "rust": ("bin/rustc",),
    "cpp": ("bin/cc", "bin/c++"),
    "java": ("bin/java", "bin/javac"),
    "browser-automation": ("bin/signalasi-browser", "bin/playwright"),
    "ffmpeg": ("bin/ffmpeg", "bin/ffprobe"),
}
PACK_REQUIRED_CAPABILITIES = {
    "python-uv": {"python.execute", "uv.sync"},
    "node-js": {"javascript.execute", "typescript.execute"},
    "go": {"go.execute"},
    "rust": {"rust.execute"},
    "cpp": {"c.execute", "cpp.execute"},
    "java": {"java.execute"},
    "browser-automation": {"browser.automation.execute"},
    "ffmpeg": {"ffmpeg.execute", "ffprobe.inspect"},
}


def canonical_json(value: Any) -> str:
    validate_canonical_value(value)
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def validate_canonical_value(value: Any) -> None:
    if value is None or isinstance(value, bool):
        return
    if isinstance(value, str):
        try:
            value.encode("utf-8")
        except UnicodeEncodeError as error:
            raise ValueError("Runtime payload contains invalid Unicode") from error
        return
    if isinstance(value, int):
        if not -(2**63) <= value <= 2**63 - 1:
            raise ValueError("Runtime payload integer is outside the signed 64-bit range")
        return
    if isinstance(value, float):
        raise ValueError("Runtime payload numbers must be signed 64-bit integers")
    if isinstance(value, list):
        for item in value:
            validate_canonical_value(item)
        return
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str):
                raise ValueError("Runtime payload key must be a string")
            validate_canonical_value(item)
        return
    raise ValueError("Runtime payload value is unsupported")


def unsigned_payload(envelope: dict[str, Any]) -> bytes:
    values = (
        str(envelope["protocol_version"]),
        envelope["message_id"],
        envelope["request_id"],
        envelope["type"],
        str(envelope["sequence"]),
        str(envelope["timestamp_millis"]),
        canonical_json(envelope.get("payload", {})),
    )
    return "\n".join(values).encode("utf-8")


def sign_envelope(envelope: dict[str, Any], session_key: bytes) -> dict[str, Any]:
    if len(session_key) < 32:
        raise ValueError("Runtime session key is too short")
    signed = dict(envelope)
    signed["mac"] = base64.b64encode(
        hmac.new(session_key, unsigned_payload(signed), hashlib.sha256).digest()
    ).decode("ascii")
    return signed


def verify_envelope(envelope: dict[str, Any], session_key: bytes, now_millis: int | None = None) -> bool:
    try:
        if int(envelope["protocol_version"]) != PROTOCOL_VERSION:
            return False
        if not envelope["message_id"] or not envelope["request_id"] or int(envelope["sequence"]) < 1:
            return False
        now = int(time.time() * 1000) if now_millis is None else now_millis
        if abs(now - int(envelope["timestamp_millis"])) > MAX_CLOCK_SKEW_MILLIS:
            return False
        supplied = base64.b64decode(envelope["mac"], validate=True)
        expected = hmac.new(session_key, unsigned_payload(envelope), hashlib.sha256).digest()
        return hmac.compare_digest(supplied, expected)
    except (KeyError, TypeError, ValueError):
        return False


def read_exact(stream: BinaryIO, size: int) -> bytes:
    output = bytearray()
    while len(output) < size:
        chunk = stream.read(size - len(output))
        if not chunk:
            raise EOFError("Runtime channel closed")
        output.extend(chunk)
    return bytes(output)


def read_frame(stream: BinaryIO) -> dict[str, Any]:
    size = struct.unpack(">I", read_exact(stream, 4))[0]
    if size < 1 or size > MAX_FRAME_BYTES:
        raise ValueError("Runtime protocol frame size is invalid")
    value = json.loads(read_exact(stream, size).decode("utf-8"))
    if not isinstance(value, dict):
        raise ValueError("Runtime protocol frame is invalid")
    return value


def write_frame(stream: BinaryIO, envelope: dict[str, Any]) -> None:
    payload = canonical_json(envelope).encode("utf-8")
    if len(payload) > MAX_FRAME_BYTES:
        raise ValueError("Runtime protocol frame is too large")
    stream.write(struct.pack(">I", len(payload)))
    stream.write(payload)
    stream.flush()


@dataclass(frozen=True)
class ExecutionLimits:
    wall_clock_ms: int
    cpu_ms: int
    memory_bytes: int
    disk_bytes: int
    max_processes: int
    max_output_bytes: int
    max_artifact_bytes: int

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> "ExecutionLimits":
        value = payload.get("limits") or {}
        limits = cls(
            wall_clock_ms=int(value.get("wall_clock_ms", 60_000)),
            cpu_ms=int(value.get("cpu_ms", 45_000)),
            memory_bytes=int(value.get("memory_bytes", 512 * 1024 * 1024)),
            disk_bytes=int(value.get("disk_bytes", 512 * 1024 * 1024)),
            max_processes=int(value.get("max_processes", 64)),
            max_output_bytes=int(value.get("max_output_bytes", 512 * 1024)),
            max_artifact_bytes=int(value.get("max_artifact_bytes", 256 * 1024 * 1024)),
        )
        if not 100 <= limits.wall_clock_ms <= 30 * 60_000:
            raise ValueError("Runtime wall-clock limit is invalid")
        if not 100 <= limits.cpu_ms <= limits.wall_clock_ms:
            raise ValueError("Runtime CPU limit is invalid")
        if not 32 * 1024 * 1024 <= limits.memory_bytes <= 4 * 1024 * 1024 * 1024:
            raise ValueError("Runtime memory limit is invalid")
        if not 8 * 1024 * 1024 <= limits.disk_bytes <= 8 * 1024 * 1024 * 1024:
            raise ValueError("Runtime disk limit is invalid")
        if not 1 <= limits.max_processes <= 512:
            raise ValueError("Runtime process limit is invalid")
        if not 1024 <= limits.max_output_bytes <= 4 * 1024 * 1024:
            raise ValueError("Runtime output limit is invalid")
        if not 1024 <= limits.max_artifact_bytes <= 2 * 1024 * 1024 * 1024:
            raise ValueError("Runtime artifact limit is invalid")
        return limits


def resolve_workspace(raw_path: str) -> Path:
    candidate = Path(raw_path)
    if not candidate.is_absolute():
        raise ValueError("Runtime workspace path is invalid")
    resolved_root = WORKSPACE_ROOT.resolve()
    resolved = candidate.resolve()
    if resolved == resolved_root or resolved_root not in resolved.parents:
        raise ValueError("Runtime workspace path escapes the shared root")
    if not resolved.is_dir():
        raise ValueError("Runtime workspace is unavailable")
    return resolved


def executable(name: str, search_path: str | None = None) -> str:
    for directory in (search_path if search_path is not None else os.environ.get("PATH", "")).split(os.pathsep):
        if not directory:
            continue
        candidate = Path(directory) / name
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
    raise FileNotFoundError(f"Runtime executable is unavailable: {name}")


def command_plan(
    language: str,
    workspace: Path,
    arguments: list[str],
    search_path: str | None = None,
) -> list[list[str]]:
    if any("\x00" in item or len(item.encode("utf-8")) > 8192 for item in arguments):
        raise ValueError("Runtime argument is invalid")
    if language == "shell":
        return [[executable("sh", search_path), str(workspace / "main.sh"), *arguments]]
    if language == "python":
        return [[executable("python3", search_path), str(workspace / "main.py"), *arguments]]
    if language == "uv":
        return [[executable("uv", search_path), "run", "--no-cache", "--offline", str(workspace / "main.py"), *arguments]]
    if language == "javascript":
        return [[executable("node", search_path), str(workspace / "main.js"), *arguments]]
    if language == "typescript":
        return [[executable("tsx", search_path), str(workspace / "main.ts"), *arguments]]
    if language == "go":
        return [[executable("go", search_path), "run", str(workspace / "main.go"), *arguments]]
    if language == "rust":
        return [
            [executable("rustc", search_path), str(workspace / "main.rs"), "-o", str(workspace / ".signalasi-main")],
            [str(workspace / ".signalasi-main"), *arguments],
        ]
    if language == "c":
        return [
            [executable("cc", search_path), str(workspace / "main.c"), "-O2", "-o", str(workspace / ".signalasi-main")],
            [str(workspace / ".signalasi-main"), *arguments],
        ]
    if language == "cpp":
        return [
            [executable("c++", search_path), str(workspace / "main.cpp"), "-O2", "-o", str(workspace / ".signalasi-main")],
            [str(workspace / ".signalasi-main"), *arguments],
        ]
    if language == "java":
        return [
            [executable("javac", search_path), str(workspace / "Main.java")],
            [executable("java", search_path), "-cp", str(workspace), "Main", *arguments],
        ]
    if language == "browser":
        return [[executable("signalasi-browser", search_path), str(workspace / "main.browser.js"), *arguments]]
    if language == "ffmpeg":
        return [[executable("ffmpeg", search_path), "-nostdin", *arguments]]
    if language == "ffprobe":
        return [[executable("ffprobe", search_path), *arguments]]
    raise ValueError("Runtime language is invalid")


def positive_config_id(config: dict[str, Any], name: str) -> int:
    value = int(config.get(name, 0))
    if not 1 <= value <= 2_147_483_646:
        raise ValueError(f"Runtime {name} is invalid")
    return value


def launcher_plan(
    config: dict[str, Any],
    workspace: Path,
    limits: ExecutionLimits,
    command: list[str],
) -> list[str]:
    if not LAUNCHER_PATH.is_file() or not os.access(LAUNCHER_PATH, os.X_OK):
        raise FileNotFoundError("Runtime sandbox launcher is unavailable")
    return [
        str(LAUNCHER_PATH),
        "--workspace",
        str(workspace),
        "--uid",
        str(positive_config_id(config, "workspace_uid")),
        "--gid",
        str(positive_config_id(config, "workspace_gid")),
        "--cpu-seconds",
        str(max(1, (limits.cpu_ms + 999) // 1000)),
        "--memory-bytes",
        str(limits.memory_bytes),
        "--max-processes",
        str(limits.max_processes),
        "--file-size-bytes",
        str(limits.disk_bytes),
        "--",
        *command,
    ]


def prepare_private_directory(path: Path) -> None:
    try:
        path.mkdir(mode=0o700)
    except FileExistsError:
        pass
    metadata = path.lstat()
    if not stat.S_ISDIR(metadata.st_mode) or path.is_symlink():
        raise ValueError("Runtime private directory is unsafe")
    path.chmod(0o700)


def open_private_output(path: Path) -> BinaryIO:
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags, 0o600)
    return os.fdopen(descriptor, "wb")


def stop_process(process: subprocess.Popen[bytes]) -> None:
    if process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        process.wait(timeout=1)
        return
    except subprocess.TimeoutExpired:
        pass
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass


def bounded_read(path: Path, limit: int) -> tuple[str, bool]:
    with path.open("rb") as stream:
        data = stream.read(limit + 1)
    truncated = len(data) > limit
    return data[:limit].decode("utf-8", errors="replace"), truncated


def bounded_directory_size(path: Path, limit: int) -> int:
    total = 0
    for root, directories, files in os.walk(path, followlinks=False):
        directories[:] = [name for name in directories if not (Path(root) / name).is_symlink()]
        for name in files:
            candidate = Path(root) / name
            if candidate.is_symlink():
                continue
            total += candidate.stat().st_size
            if total > limit:
                return total
    return total


class GuestService:
    def __init__(self, stream: BinaryIO, session_key: bytes, config: dict[str, Any]):
        self.stream = stream
        self.session_key = session_key
        self.config = config
        self.write_lock = threading.Lock()
        self.state_lock = threading.Lock()
        self.inbound_sequences: OrderedDict[str, int] = OrderedDict()
        self.outbound_sequences: OrderedDict[str, int] = OrderedDict()
        self.cancellations: dict[str, threading.Event] = {}

    def send(self, request_id: str, message_type: str, payload: dict[str, Any] | None = None) -> None:
        with self.write_lock:
            sequence = self.outbound_sequences.get(request_id, 0) + 1
            self.outbound_sequences[request_id] = sequence
            self.outbound_sequences.move_to_end(request_id)
            while len(self.outbound_sequences) > MAX_SEQUENCE_WINDOWS:
                self.outbound_sequences.popitem(last=False)
            envelope = sign_envelope(
                {
                    "protocol_version": PROTOCOL_VERSION,
                    "message_id": str(uuid.uuid4()),
                    "request_id": request_id,
                    "type": message_type,
                    "sequence": sequence,
                    "timestamp_millis": int(time.time() * 1000),
                    "payload": payload or {},
                    "mac": "",
                },
                self.session_key,
            )
            write_frame(self.stream, envelope)

    def serve(self) -> None:
        while True:
            envelope = read_frame(self.stream)
            if not verify_envelope(envelope, self.session_key):
                raise ValueError("Runtime protocol authentication failed")
            request_id = str(envelope["request_id"])
            if not REQUEST_ID_PATTERN.fullmatch(request_id):
                raise ValueError("Runtime request id is invalid")
            sequence = int(envelope["sequence"])
            if sequence <= self.inbound_sequences.get(request_id, 0):
                raise ValueError("Runtime protocol frame is replayed or out of order")
            self.inbound_sequences[request_id] = sequence
            self.inbound_sequences.move_to_end(request_id)
            while len(self.inbound_sequences) > MAX_SEQUENCE_WINDOWS:
                oldest_request_id = next(iter(self.inbound_sequences))
                with self.state_lock:
                    is_active = oldest_request_id in self.cancellations
                if is_active:
                    self.inbound_sequences.move_to_end(oldest_request_id)
                    continue
                self.inbound_sequences.popitem(last=False)
            message_type = envelope["type"]
            if message_type == "hello":
                ready, reason = runtime_readiness(self.config)
                self.send(
                    request_id,
                    "hello_ack",
                    {
                        "guest_api_version": PROTOCOL_VERSION,
                        "guest_version": "1.0.0",
                        "ready": ready,
                        "reason": reason,
                        "capabilities": [
                            "runtime.execute",
                            "runtime.cancel",
                            "runtime.progress",
                            "runtime.concurrent",
                        ],
                    },
                )
            elif message_type == "heartbeat":
                ready, reason = runtime_readiness(self.config)
                self.send(request_id, "heartbeat_ack", {"ready": ready, "reason": reason})
            elif message_type == "execute":
                self.start_execution(request_id, envelope.get("payload") or {})
            elif message_type == "cancel":
                with self.state_lock:
                    event = self.cancellations.get(request_id)
                if event is not None:
                    event.set()
            else:
                self.send(request_id, "error", {"message": "Unsupported runtime request"})

    def start_execution(self, request_id: str, payload: dict[str, Any]) -> None:
        with self.state_lock:
            if request_id in self.cancellations:
                self.send(request_id, "error", {"message": "Runtime request is already active"})
                return
            if len(self.cancellations) >= MAX_CONCURRENT_EXECUTIONS:
                self.send(request_id, "error", {"message": "Runtime concurrency limit reached"})
                return
            cancellation = threading.Event()
            self.cancellations[request_id] = cancellation
        threading.Thread(
            target=self.execute,
            args=(request_id, payload, cancellation),
            name=f"signalasi-exec-{request_id[:12]}",
            daemon=True,
        ).start()

    def execute(self, request_id: str, payload: dict[str, Any], cancellation: threading.Event) -> None:
        started = time.monotonic()
        try:
            workspace = resolve_workspace(str(payload.get("workspace_path", "")))
            language = str(payload.get("language", ""))
            arguments = payload.get("arguments") or []
            if not isinstance(arguments, list) or len(arguments) > 256:
                raise ValueError("Runtime arguments are invalid")
            limits = ExecutionLimits.from_payload(payload)
            network = payload.get("network") or {}
            if bool(network.get("enabled")):
                raise ValueError("Guest networking must use the host-mediated network tool")
            environment = runtime_environment()
            commands = command_plan(
                language,
                ISOLATED_WORKSPACE_ROOT,
                [str(value) for value in arguments],
                environment["PATH"],
            )
            stdout_path = workspace / ".signalasi-stdout"
            stderr_path = workspace / ".signalasi-stderr"
            prepare_private_directory(workspace / ".tmp")
            self.send(request_id, "progress", {"stage": "starting", "message": "Runtime started", "percent": 5})
            exit_code = 0
            with open_private_output(stdout_path) as stdout, open_private_output(stderr_path) as stderr:
                for index, command in enumerate(commands):
                    if cancellation.is_set():
                        self.send(request_id, "cancelled")
                        return
                    process = subprocess.Popen(
                        launcher_plan(self.config, workspace, limits, command),
                        cwd=workspace,
                        stdin=subprocess.DEVNULL,
                        stdout=stdout,
                        stderr=stderr,
                        env=environment,
                        start_new_session=True,
                        umask=0o077,
                    )
                    next_quota_check = 0.0
                    while process.poll() is None:
                        now = time.monotonic()
                        elapsed_ms = int((now - started) * 1000)
                        if cancellation.is_set():
                            stop_process(process)
                            self.send(request_id, "cancelled")
                            return
                        if elapsed_ms > limits.wall_clock_ms:
                            stop_process(process)
                            raise TimeoutError("Runtime wall-clock limit exceeded")
                        output_size = stdout_path.stat().st_size + stderr_path.stat().st_size
                        if output_size > limits.max_output_bytes:
                            stop_process(process)
                            raise ValueError("Runtime output limit exceeded")
                        if now >= next_quota_check:
                            if bounded_directory_size(workspace, limits.disk_bytes) > limits.disk_bytes:
                                stop_process(process)
                                raise ValueError("Runtime workspace limit exceeded")
                            next_quota_check = now + 0.25
                        time.sleep(0.05)
                    exit_code = int(process.returncode or 0)
                    if exit_code != 0:
                        break
                    percent = 10 + int(((index + 1) / len(commands)) * 80)
                    self.send(request_id, "progress", {"stage": "running", "message": "Runtime step completed", "percent": percent})
            stdout_value, stdout_truncated = bounded_read(stdout_path, limits.max_output_bytes)
            stderr_value, stderr_truncated = bounded_read(stderr_path, limits.max_output_bytes)
            self.send(
                request_id,
                "result",
                {
                    "exit_code": exit_code,
                    "stdout": stdout_value,
                    "stderr": stderr_value,
                    "output_truncated": stdout_truncated or stderr_truncated,
                    "duration_ms": int((time.monotonic() - started) * 1000),
                    "artifacts": [],
                },
            )
        except Exception as error:
            self.send(request_id, "error", {"message": str(error) or "Runtime execution failed"})
        finally:
            with self.state_lock:
                self.cancellations.pop(request_id, None)


def runtime_environment() -> dict[str, str]:
    pack_bins = [str(path) for path in sorted(PACK_ROOT.glob("*/bin")) if path.is_dir()]
    task_temp = ISOLATED_WORKSPACE_ROOT / ".tmp"
    return {
        "HOME": str(ISOLATED_WORKSPACE_ROOT),
        "TMPDIR": str(task_temp),
        "PATH": os.pathsep.join(pack_bins + ["/usr/local/sbin", "/usr/local/bin", "/usr/sbin", "/usr/bin", "/sbin", "/bin"]),
        "LANG": "C.UTF-8",
        "LC_ALL": "C.UTF-8",
        "PYTHONNOUSERSITE": "1",
        "UV_NO_MODIFY_PATH": "1",
        "UV_NO_CACHE": "1",
        "UV_PYTHON": "/usr/bin/python3",
        "UV_PYTHON_DOWNLOADS": "never",
        "UV_OFFLINE": "1",
        "UV_CACHE_DIR": str(task_temp / "uv-cache"),
        "CARGO_HOME": str(task_temp / "cargo"),
        "CARGO_NET_OFFLINE": "true",
        "ZIG_GLOBAL_CACHE_DIR": str(task_temp / "zig-global-cache"),
        "ZIG_LOCAL_CACHE_DIR": str(task_temp / "zig-local-cache"),
        "JAVA_HOME": str(PACK_ROOT / "java"),
    }


def runtime_readiness(config: dict[str, Any]) -> tuple[bool, str]:
    if not LAUNCHER_PATH.is_file() or not os.access(LAUNCHER_PATH, os.X_OK):
        return False, "Runtime sandbox launcher is unavailable"
    try:
        positive_config_id(config, "workspace_uid")
        positive_config_id(config, "workspace_gid")
    except (TypeError, ValueError) as error:
        return False, str(error)
    return True, ""


def mount_runtime(config: dict[str, Any]) -> None:
    WORKSPACE_ROOT.mkdir(parents=True, exist_ok=True)
    if not os.path.ismount(WORKSPACE_ROOT):
        subprocess.run(
            ["mount", "-t", "9p", "-o", "trans=virtio,version=9p2000.L,msize=262144", "signalasi_workspaces", str(WORKSPACE_ROOT)],
            check=True,
        )
    PACK_NAMESPACE_ROOT.mkdir(mode=0o755, parents=True, exist_ok=True)
    PACK_NAMESPACE_ROOT.chmod(0o755)
    PACK_ROOT.mkdir(mode=0o755, parents=True, exist_ok=True)
    PACK_ROOT.chmod(0o755)
    for pack in config.get("packs") or []:
        pack_id = str(pack.get("id", ""))
        serial = str(pack.get("serial", ""))
        if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,79}", pack_id):
            raise ValueError("Runtime pack id is invalid")
        target = PACK_ROOT / pack_id
        target.mkdir(mode=0o755, parents=True, exist_ok=True)
        if os.path.ismount(target):
            validate_mounted_pack(target, pack)
            continue
        device = wait_for_block_device(serial)
        subprocess.run(["mount", "-t", "squashfs", "-o", "ro,nodev,nosuid", str(device), str(target)], check=True)
        validate_mounted_pack(target, pack)


def validate_mounted_pack(target: Path, pack: dict[str, Any]) -> None:
    pack_id = str(pack.get("id", ""))
    version = str(pack.get("version", ""))
    capabilities_value = pack.get("capabilities") or []
    if not isinstance(capabilities_value, list) or any(not isinstance(value, str) for value in capabilities_value):
        raise ValueError(f"Runtime pack capabilities are invalid: {pack_id}")
    capabilities = set(capabilities_value)
    if len(capabilities) != len(capabilities_value):
        raise ValueError(f"Runtime pack capabilities are duplicated: {pack_id}")
    missing_capabilities = PACK_REQUIRED_CAPABILITIES.get(pack_id, set()) - capabilities
    if pack_id not in PACK_ENTRYPOINTS or missing_capabilities:
        raise ValueError(f"Runtime pack capabilities are incomplete: {pack_id}")

    descriptor_path = target / PACK_DESCRIPTOR_NAME
    if not descriptor_path.is_file() or descriptor_path.stat().st_size > 64 * 1024:
        raise ValueError(f"Runtime pack descriptor is unavailable: {pack_id}")
    descriptor = json.loads(descriptor_path.read_text(encoding="utf-8"))
    if (
        int(descriptor.get("format_version", 0)) != 1
        or descriptor.get("id") != pack_id
        or descriptor.get("version") != version
        or set(descriptor.get("capabilities") or []) != capabilities
    ):
        raise ValueError(f"Runtime pack descriptor does not match its signed manifest: {pack_id}")

    resolved_target = target.resolve()
    for relative in PACK_ENTRYPOINTS[pack_id]:
        executable_path = target / relative
        resolved_executable = executable_path.resolve()
        if (
            resolved_target not in resolved_executable.parents
            or not resolved_executable.is_file()
            or not os.access(resolved_executable, os.X_OK)
        ):
            raise ValueError(f"Runtime pack entrypoint is unavailable: {pack_id}/{relative}")


def wait_for_block_device(serial: str, timeout_seconds: float = 10.0) -> Path:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        for entry in Path("/sys/block").glob("vd*"):
            for serial_file in (entry / "serial", entry / "device" / "serial"):
                try:
                    matches = serial_file.is_file() and serial_file.read_text(encoding="utf-8").strip() == serial
                except OSError:
                    matches = False
                if matches:
                    device = Path("/dev") / entry.name
                    if device.exists():
                        return device
        time.sleep(0.1)
    raise FileNotFoundError(f"Runtime pack device is unavailable: {serial}")


def wait_for_runtime_channel(
    timeout_seconds: float = 10.0,
    class_root: Path = VIRTIO_PORT_CLASS_ROOT,
    device_root: Path = DEVICE_ROOT,
) -> Path:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        for entry in sorted(class_root.glob("*")):
            try:
                if (entry / "name").read_text(encoding="utf-8").strip() != CHANNEL_NAME:
                    continue
            except OSError:
                continue

            device_names = [entry.name]
            try:
                for line in (entry / "uevent").read_text(encoding="utf-8").splitlines():
                    if line.startswith("DEVNAME="):
                        device_names.insert(0, line.removeprefix("DEVNAME="))
            except OSError:
                pass

            for device_name in device_names:
                relative_device = Path(device_name)
                if relative_device.is_absolute() or ".." in relative_device.parts:
                    continue
                candidate = device_root / relative_device
                if candidate.exists() and not candidate.is_dir():
                    return candidate
        time.sleep(0.1)
    raise FileNotFoundError(f"Runtime API channel is unavailable: {CHANNEL_NAME}")


def synchronize_guest_clock(config: dict[str, Any]) -> None:
    host_epoch_millis = int(config.get("host_epoch_millis", 0))
    if not MIN_TRUSTED_EPOCH_MILLIS <= host_epoch_millis <= MAX_TRUSTED_EPOCH_MILLIS:
        raise ValueError("Runtime host clock is invalid")
    current_epoch_millis = int(time.time() * 1000)
    if abs(current_epoch_millis - host_epoch_millis) > MAX_CLOCK_SKEW_MILLIS:
        try:
            time.clock_settime(time.CLOCK_REALTIME, host_epoch_millis / 1000)
        except (AttributeError, OSError) as error:
            raise RuntimeError("Runtime guest clock synchronization failed") from error
    if abs(int(time.time() * 1000) - host_epoch_millis) > MAX_CLOCK_SKEW_MILLIS:
        raise RuntimeError("Runtime guest clock remains outside the trusted window")


def run_service() -> None:
    session_key = SESSION_PATH.read_bytes()
    if len(session_key) < 32:
        raise ValueError("Runtime session key is invalid")
    config = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    if int(config.get("guest_api_version", 0)) != PROTOCOL_VERSION:
        raise ValueError("Runtime guest API is incompatible")
    synchronize_guest_clock(config)
    mount_runtime(config)
    while True:
        try:
            channel_path = wait_for_runtime_channel()
            with channel_path.open("r+b", buffering=0) as stream:
                GuestService(stream, session_key, config).serve()
        except (EOFError, OSError, ValueError):
            time.sleep(0.2)


if __name__ == "__main__":
    run_service()
