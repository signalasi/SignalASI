"""Cross-process ownership for the SignalASI Desktop external-service backend."""

from __future__ import annotations

import os
import tempfile
from pathlib import Path


class BackendInstanceAlreadyRunning(RuntimeError):
    pass


class BackendInstanceLock:
    def __init__(self, name: str = "SignalASI.Backend.ExternalServices.v1") -> None:
        self.name = name
        self._handle = None
        self._file = None

    def acquire(self) -> None:
        if self._handle is not None or self._file is not None:
            return
        if os.name == "nt":
            self._acquire_windows()
        else:
            self._acquire_file()

    def release(self) -> None:
        if self._handle is not None:
            import ctypes

            ctypes.windll.kernel32.CloseHandle(self._handle)
            self._handle = None
        if self._file is not None:
            import fcntl

            fcntl.flock(self._file.fileno(), fcntl.LOCK_UN)
            self._file.close()
            self._file = None

    def _acquire_windows(self) -> None:
        import ctypes
        from ctypes import wintypes

        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel32.CreateMutexW.argtypes = (wintypes.LPVOID, wintypes.BOOL, wintypes.LPCWSTR)
        kernel32.CreateMutexW.restype = wintypes.HANDLE
        kernel32.CloseHandle.argtypes = (wintypes.HANDLE,)
        kernel32.CloseHandle.restype = wintypes.BOOL
        ctypes.set_last_error(0)
        handle = kernel32.CreateMutexW(None, False, f"Local\\{self.name}")
        if not handle:
            raise OSError(ctypes.get_last_error(), "Could not create the SignalASI backend mutex")
        if ctypes.get_last_error() == 183:
            kernel32.CloseHandle(handle)
            raise BackendInstanceAlreadyRunning("Another SignalASI external-service backend is already running")
        self._handle = handle

    def _acquire_file(self) -> None:
        import fcntl

        safe_name = "".join(character if character.isalnum() else "-" for character in self.name)
        path = Path(tempfile.gettempdir()) / f"{safe_name}-{os.getuid()}.lock"
        lock_file = path.open("a+b")
        try:
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            lock_file.close()
            raise BackendInstanceAlreadyRunning(
                "Another SignalASI external-service backend is already running"
            ) from error
        self._file = lock_file

    def __enter__(self) -> "BackendInstanceLock":
        self.acquire()
        return self

    def __exit__(self, _exc_type, _exc_value, _traceback) -> None:
        self.release()
