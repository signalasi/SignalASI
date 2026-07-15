"""Deterministic Desktop file operations for explicit, low-risk requests."""
from __future__ import annotations

import base64
import os
import re
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class DesktopFileToolResult:
    message: str
    output_path: Path
    operation: str
    elapsed_ms: int


def _contains_chinese(value: str) -> bool:
    return bool(re.search(r"[\u4e00-\u9fff]", value))


def _requested_conversion(prompt: str) -> str:
    value = str(prompt or "").strip().lower()
    conversion_intent = bool(re.search(
        r"(?:convert|export|save\s+(?:as|to)|\u8f6c(?:\u6210|\u6362(?:\u6210|\u4e3a)?)?|\u5bfc\u51fa|\u4fdd\u5b58\u6210|\u53e6\u5b58\u4e3a)",
        value,
    ))
    if not conversion_intent:
        return ""
    if re.search(r"(?:\.pdf\b|\bpdf\b)", value):
        return "pdf"
    if re.search(r"(?:\.csv\b|\bcsv\b)", value):
        return "csv"
    return ""


def _powershell_executable() -> str:
    return shutil.which("powershell.exe") or shutil.which("powershell") or "powershell.exe"


def _run_excel_conversion(source: Path, target: Path, output_format: str, timeout: int = 45) -> None:
    format_code = "0" if output_format == "pdf" else "6"
    action = (
        f"$book.ExportAsFixedFormat({format_code}, $env:SIGNALASI_OUTPUT)"
        if output_format == "pdf"
        else f"$book.SaveAs($env:SIGNALASI_OUTPUT, {format_code})"
    )
    script = f"""
$ErrorActionPreference = 'Stop'
$excel = New-Object -ComObject Excel.Application
$excel.Visible = $false
$excel.DisplayAlerts = $false
$book = $null
try {{
    $book = $excel.Workbooks.Open($env:SIGNALASI_INPUT)
    {action}
    $book.Close($false)
    $book = $null
}} finally {{
    if ($null -ne $book) {{ $book.Close($false) }}
    $excel.Quit()
    [void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($excel)
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
}}
""".strip()
    encoded = base64.b64encode(script.encode("utf-16le")).decode("ascii")
    env = os.environ.copy()
    env["SIGNALASI_INPUT"] = str(source.resolve())
    env["SIGNALASI_OUTPUT"] = str(target.resolve())
    completed = subprocess.run(
        [_powershell_executable(), "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
        env=env,
        timeout=timeout,
        check=False,
        creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
    )
    if completed.returncode != 0 or not target.exists() or target.stat().st_size == 0:
        detail = (completed.stderr or completed.stdout or "Excel conversion failed").strip()
        raise RuntimeError(detail[-600:])


def try_execute_explicit_file_task(
    prompt: str,
    input_paths: list[Path],
    output_directory: Path,
) -> DesktopFileToolResult | None:
    output_format = _requested_conversion(prompt)
    if not output_format or len(input_paths) != 1:
        return None
    source = Path(input_paths[0])
    if not source.is_file() or source.suffix.lower() not in {".xlsx", ".xls"}:
        return None
    output_directory.mkdir(parents=True, exist_ok=True)
    target = output_directory / f"{source.stem}.{output_format}"

    import time
    started = time.perf_counter()
    _run_excel_conversion(source, target, output_format)
    elapsed_ms = round((time.perf_counter() - started) * 1000)
    if _contains_chinese(prompt):
        message = f"\u5df2\u8f6c\u6362\u4e3a {target.name}\u3002"
    else:
        message = f"Converted to {target.name}."
    return DesktopFileToolResult(message, target, f"excel_to_{output_format}", elapsed_ms)
