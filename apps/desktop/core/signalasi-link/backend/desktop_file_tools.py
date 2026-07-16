"""Deterministic Desktop file operations for explicit, low-risk requests."""
from __future__ import annotations

import base64
import csv
import decimal
import os
import re
import shutil
import subprocess
import zipfile
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class DesktopFileToolResult:
    message: str
    output_path: Path | None
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


def _requested_csv_summary(prompt: str) -> bool:
    value = str(prompt or "").strip().lower()
    # Attachment-only turns carry policy text that names actions the assistant
    # must not perform. Those words are constraints, not an explicit file task.
    if re.search(
        r"(?:files?\s+without\s+stating\s+a\s+task|do\s+not\s+(?:inspect|summari[sz]e|return)|"
        r"\u53ea\u4e0a\u4f20\u4e86?\u9644\u4ef6|\u6ca1\u6709\u8bf4\u660e\u4efb\u52a1|\u4e0d\u8981(?:\u8bfb\u53d6|\u603b\u7ed3|\u56de\u4f20))",
        value,
    ):
        return False
    return bool(re.search(
        r"(?:summari[sz]e|summary|analy[sz]e|overview|calculate|total|revenue|statistics|"
        r"\u6c47\u603b|\u603b\u7ed3|\u5206\u6790|\u7edf\u8ba1|\u5408\u8ba1|\u6536\u5165|\u8425\u6536)",
        value,
    ))


def _read_csv_rows(source: Path) -> tuple[list[str], list[dict[str, str]]]:
    if source.stat().st_size > 5 * 1024 * 1024:
        raise RuntimeError("CSV is too large for the interactive summary path")
    raw = source.read_bytes()
    text = None
    for encoding in ("utf-8-sig", "gb18030"):
        try:
            text = raw.decode(encoding)
            break
        except UnicodeDecodeError:
            continue
    if text is None:
        raise RuntimeError("CSV text encoding is not supported")
    reader = csv.DictReader(text.splitlines())
    columns = [str(value or "").strip() for value in (reader.fieldnames or [])]
    if not columns:
        raise RuntimeError("CSV does not contain a header row")
    rows = [
        {column: str(row.get(column) or "").strip() for column in columns}
        for row in reader
    ]
    return columns, rows


def _decimal(value: str) -> decimal.Decimal | None:
    try:
        return decimal.Decimal(value.replace(",", "").strip())
    except decimal.InvalidOperation:
        return None


def _csv_summary_message(prompt: str, source: Path) -> str:
    columns, rows = _read_csv_rows(source)
    normalized = {column: re.sub(r"[^a-z0-9\u4e00-\u9fff]", "", column.lower()) for column in columns}
    quantity = next((column for column, value in normalized.items() if value in {
        "quantity", "qty", "units", "count", "\u6570\u91cf", "\u4ef6\u6570", "\u9500\u91cf"
    }), None)
    price = next((column for column, value in normalized.items() if value in {
        "price", "unitprice", "\u4ef7\u683c", "\u5355\u4ef7"
    }), None)
    revenue = next((column for column, value in normalized.items() if value in {
        "revenue", "sales", "amount", "total", "\u6536\u5165", "\u8425\u6536", "\u91d1\u989d"
    }), None)
    preview_columns = list(columns)
    computed_revenue: list[decimal.Decimal | None] = []
    if revenue:
        computed_revenue = [_decimal(row.get(revenue, "")) for row in rows]
    elif quantity and price:
        revenue = "Revenue" if not _contains_chinese(prompt) else "\u6536\u5165"
        preview_columns.append(revenue)
        computed_revenue = [
            (_decimal(row.get(quantity, "")) * _decimal(row.get(price, "")))
            if _decimal(row.get(quantity, "")) is not None and _decimal(row.get(price, "")) is not None
            else None
            for row in rows
        ]
    table_rows: list[list[str]] = []
    for index, row in enumerate(rows[:20]):
        values = [row.get(column, "") for column in columns]
        if len(preview_columns) > len(columns):
            computed = computed_revenue[index]
            values.append(f"{computed:.2f}" if computed is not None else "")
        table_rows.append(values)
    escape = lambda value: str(value).replace("|", "\\|").replace("\n", " ")
    lines = [
        (f"{len(rows)} rows, {len(columns)} columns." if not _contains_chinese(prompt)
         else f"\u5171 {len(rows)} \u884c\u3001{len(columns)} \u5217\u3002"),
        "",
        "| " + " | ".join(map(escape, preview_columns)) + " |",
        "| " + " | ".join("---" for _ in preview_columns) + " |",
    ]
    lines.extend("| " + " | ".join(map(escape, values)) + " |" for values in table_rows)
    total = sum((value for value in computed_revenue if value is not None), decimal.Decimal(0))
    if computed_revenue:
        label = "\u603b\u6536\u5165" if _contains_chinese(prompt) else "Total revenue"
        lines.extend(["", f"**{label}: {total:.2f}**"])
    if len(rows) > len(table_rows):
        lines.extend(["", f"Showing the first {len(table_rows)} rows."])
    return "\n".join(lines)


def _powershell_executable() -> str:
    return shutil.which("powershell.exe") or shutil.which("powershell") or "powershell.exe"


def _run_excel_conversion(source: Path, target: Path, output_format: str, timeout: int = 45) -> None:
    suffix = source.suffix.lower()
    if suffix == ".xlsx" and not zipfile.is_zipfile(source):
        raise ValueError("not a valid XLSX archive")
    if suffix == ".xls" and source.read_bytes()[:8] != bytes.fromhex("D0CF11E0A1B11AE1"):
        raise ValueError("not a valid XLS workbook")
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
    if len(input_paths) == 1:
        source = Path(input_paths[0])
        if source.is_file() and source.suffix.lower() == ".csv" and _requested_csv_summary(prompt):
            import time
            started = time.perf_counter()
            message = _csv_summary_message(prompt, source)
            return DesktopFileToolResult(
                message=message,
                output_path=None,
                operation="csv_summary",
                elapsed_ms=round((time.perf_counter() - started) * 1000),
            )
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
    try:
        _run_excel_conversion(source, target, output_format)
    except Exception:
        target.unlink(missing_ok=True)
        elapsed_ms = round((time.perf_counter() - started) * 1000)
        if _contains_chinese(prompt):
            message = (
                f"\u65e0\u6cd5\u8f6c\u6362 `{source.name}`\uff1a\u5b83\u4e0d\u662f\u6709\u6548\u6216\u53ef\u8bfb\u53d6\u7684 Excel \u5de5\u4f5c\u7c3f\u3002"
                "\u8bf7\u4e0a\u4f20\u6709\u6548\u6587\u4ef6\uff0c\u6216\u8ba9\u6211\u5c1d\u8bd5\u4fee\u590d\u5b83\u3002"
            )
        else:
            message = (
                f"I couldn't convert `{source.name}` because it isn't a valid or readable Excel workbook. "
                "Upload a valid file, or ask me to try repairing it."
            )
        return DesktopFileToolResult(message, None, "excel_conversion_failed", elapsed_ms)
    elapsed_ms = round((time.perf_counter() - started) * 1000)
    if _contains_chinese(prompt):
        message = f"\u5df2\u8f6c\u6362\u4e3a {target.name}\u3002"
    else:
        message = f"Converted to {target.name}."
    return DesktopFileToolResult(message, target, f"excel_to_{output_format}", elapsed_ms)
