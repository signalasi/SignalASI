"""Cached encryption readiness and recovery, independent of broker connectivity."""
from __future__ import annotations

import threading
import time
from typing import Callable


class SignalSidecarSupervisor:
    def __init__(self, probe: Callable[[], bool], recover: Callable[[], None], *,
                 interval: float = 2, max_age: float = 6, clock=time.monotonic):
        if interval <= 0 or max_age < interval:
            raise ValueError("Invalid sidecar observation interval")
        self.probe, self.recover = probe, recover
        self.interval, self.max_age, self.clock = interval, max_age, clock
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._ready = False
        self._observed: float | None = None
        self._error = "signal_runtime_not_checked"
        self._attempts = 0

    def start(self):
        with self._lock:
            if self._thread is not None and self._thread.is_alive():
                return
            self._stop.clear()
            self._ready = False
            self._observed = None
            self._error = "signal_runtime_not_checked"
            self._thread = threading.Thread(target=self._run, name="signal-sidecar-supervisor", daemon=True)
            self._thread.start()

    def snapshot(self) -> dict:
        # No filesystem, sidecar HTTP, startup lock, or process creation on the API thread.
        with self._lock:
            running = self._thread is not None and self._thread.is_alive() and not self._stop.is_set()
            age = None if self._observed is None else max(0.0, self.clock() - self._observed)
            fresh = age is not None and age <= self.max_age
            code = self._error
            if not running:
                code = "signal_supervisor_stopped"
            elif not fresh and self._ready:
                code = "signal_runtime_observation_stale"
            return {"ready": running and fresh and self._ready, "supervised": running,
                    "error_code": code, "observation_age_ms": None if age is None else round(age * 1000),
                    "recovery_attempts": self._attempts}

    def _observe(self, ready: bool, error: str = ""):
        with self._lock:
            self._ready = ready
            self._error = error
            self._observed = self.clock()

    def _run(self):
        while not self._stop.is_set():
            try:
                try:
                    healthy = self.probe()
                except Exception:
                    healthy = False
                if healthy:
                    self._observe(True)
                else:
                    self._observe(False, "signal_runtime_recovering")
                    if self._stop.is_set():
                        break
                    with self._lock:
                        self._attempts += 1
                    self.recover()
                    if self._stop.is_set():
                        break
                    ready = self.probe()
                    self._observe(ready, "" if ready else "signal_runtime_unavailable")
            except FileNotFoundError:
                self._observe(False, "signal_runtime_missing")
            except Exception:
                self._observe(False, "signal_runtime_unavailable")
            self._stop.wait(self.interval)

    def stop(self, timeout: float = 30):
        self._stop.set()
        with self._lock:
            thread = self._thread
        if thread is not None:
            thread.join(timeout)
            if thread.is_alive():
                raise RuntimeError("Signal supervisor is still stopping")


def encrypted_transport_health(bridge: dict, sidecar: dict) -> dict:
    return {"status": "ok", "protocol": "GalaxySSI Link Protocol", "connector": "GalaxySSI Desktop",
            "ready": bridge.get("ready") is True and sidecar.get("ready") is True,
            "message_bridge": bridge, "signal_sidecar": sidecar}


_runtime: SignalSidecarSupervisor | None = None
_runtime_lock = threading.Lock()


def start_supervisor():
    global _runtime
    import galaxyssi_client
    with _runtime_lock:
        if _runtime is None:
            _runtime = SignalSidecarSupervisor(galaxyssi_client._is_healthy, galaxyssi_client.start_signal_sidecar)
        _runtime.start()


def sidecar_status() -> dict:
    with _runtime_lock:
        runtime = _runtime
    return runtime.snapshot() if runtime is not None else {
        "ready": False, "supervised": False, "error_code": "signal_supervisor_stopped",
        "observation_age_ms": None, "recovery_attempts": 0}


def stop_supervisor():
    with _runtime_lock:
        runtime = _runtime
    if runtime is not None:
        runtime.stop()
