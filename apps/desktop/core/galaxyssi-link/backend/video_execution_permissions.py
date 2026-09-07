"""Pairing authorization and narrowly scoped Windows render-directory permissions."""
import csv
import io
import os
from pathlib import Path
import re
import subprocess

from video_transport import VideoError


def require_video_executor(checkpoint):
    from pairing_access import DESKTOP_EXECUTOR, has_full_executor
    if checkpoint.get("desktop_access_profile") != DESKTOP_EXECUTOR:
        raise VideoError("video_executor_required: re-pair with Desktop Executor authorization to render video")
    client_id = str(checkpoint.get("client_route_id") or "")
    if client_id and client_id != "desktop-local":
        from pairing_state import get_client
        if not has_full_executor(get_client(client_id)):
            raise VideoError("video_executor_revoked: this paired client has no current Desktop Executor grant")


def prepare_render_directory(directory: Path, task_root: Path):
    directory, root = Path(directory), Path(task_root).resolve()
    if directory.is_symlink() or not directory.resolve().is_relative_to(root) or not directory.is_dir():
        raise VideoError("video_workspace_path_rejected")
    if os.name != "nt":
        return
    system = Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32"
    try:
        identity = subprocess.run([str(system / "whoami.exe"), "/user", "/fo", "csv", "/nh"],
                                  capture_output=True, check=True, timeout=10)
        row = next(csv.reader(io.StringIO(identity.stdout.decode("utf-8", errors="replace"))))
        sid = row[-1].strip()
        if not re.fullmatch(r"S-1-\d+(?:-\d+)+", sid):
            raise ValueError()
        # OWNER RIGHTS alone refers to the sandbox account for files it creates.
        # Preserve inherited host read/write access without granting any new user.
        subprocess.run([str(system / "icacls.exe"), str(directory.resolve()),
                        "/grant", f"*{sid}:(OI)(CI)(M)"],
                       capture_output=True, check=True, timeout=10)
    except (OSError, ValueError, StopIteration, subprocess.SubprocessError):
        raise VideoError("video_workspace_acl_failed: cannot prepare shared host/sandbox task output access") from None
