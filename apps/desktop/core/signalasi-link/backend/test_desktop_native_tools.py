import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from desktop_native_tools import (
    ARCHIVE_CREATE,
    FILE_LIST,
    FILE_READ_TEXT,
    FILE_SHA256,
    FILE_WRITE_TEXT,
    OFFICE_CONVERT,
    OFFICE_INSPECT,
    PROCESS_LIST,
    SYSTEM_STATUS,
    TERMINAL_RUN,
    DesktopNativeToolRegistry,
    _digest,
)


class DesktopNativeToolRegistryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.registry = DesktopNativeToolRegistry(
            state_root=self.root / "state",
            workspace_root=self.root / "workspaces",
        )

    def tearDown(self):
        self.temporary.cleanup()

    def invoke(self, tool_id, arguments, *, key="", confirmed=False, invocation_id="invocation-1"):
        confirmation = None
        if confirmed:
            confirmation = {
                "decision": "approved",
                "tool_id": tool_id,
                "tool_version": "1.0.0",
                "arguments_sha256": _digest(arguments),
                "expires_at": int(__import__("time").time() * 1000) + 60_000,
            }
        return self.registry.invoke(tool_id, arguments, {
            "invocation_id": invocation_id,
            "idempotency_key": key,
            "confirmation": confirmation,
        })

    def workspace(self, name="task-a"):
        path = self.root / "workspaces" / name
        path.mkdir(parents=True, exist_ok=True)
        return path

    def test_manifest_exposes_typed_windows_file_terminal_and_office_tools(self):
        manifest = self.registry.manifest()
        tools = {item["id"]: item for item in manifest["tools"]}

        self.assertEqual("signalasi.desktop-native-tools/1.0", manifest["contract_version"])
        for tool_id in (
            SYSTEM_STATUS, PROCESS_LIST, FILE_LIST, FILE_READ_TEXT, FILE_WRITE_TEXT,
            FILE_SHA256, ARCHIVE_CREATE, TERMINAL_RUN, OFFICE_INSPECT, OFFICE_CONVERT,
        ):
            self.assertIn(tool_id, tools)
            self.assertEqual("desktop", tools[tool_id]["location"])
            self.assertFalse(tools[tool_id]["input_schema"]["additionalProperties"])

    def test_workspace_path_escape_is_rejected(self):
        result = self.invoke(FILE_READ_TEXT, {"workspace_id": "task-a", "path": "../secret.txt"})

        self.assertEqual("failed", result["status"])
        self.assertEqual("invalid_path", result["error"]["code"])

    def test_write_requires_exact_confirmation_and_replays_same_receipt(self):
        arguments = {
            "workspace_id": "task-a",
            "path": "src/hello.txt",
            "content": "hello",
            "mode": "create",
        }
        rejected = self.invoke(FILE_WRITE_TEXT, arguments, key="write-1")
        accepted = self.invoke(FILE_WRITE_TEXT, arguments, key="write-1", confirmed=True)
        replayed = self.invoke(
            FILE_WRITE_TEXT,
            arguments,
            key="write-1",
            confirmed=True,
            invocation_id="invocation-2",
        )

        self.assertEqual("confirmation_required", rejected["error"]["code"])
        self.assertEqual("succeeded", accepted["status"])
        self.assertEqual("hello", (self.workspace() / "src" / "hello.txt").read_text(encoding="utf-8"))
        self.assertTrue(replayed["receipt"]["replayed"])
        self.assertEqual("invocation-1", replayed["receipt"]["original_invocation_id"])

    def test_idempotency_key_cannot_be_reused_with_different_input(self):
        first = {"workspace_id": "task-a", "path": "a.txt", "content": "one", "mode": "create"}
        second = {"workspace_id": "task-a", "path": "b.txt", "content": "two", "mode": "create"}
        self.assertEqual("succeeded", self.invoke(FILE_WRITE_TEXT, first, key="same", confirmed=True)["status"])

        result = self.invoke(FILE_WRITE_TEXT, second, key="same", confirmed=True, invocation_id="invocation-2")

        self.assertEqual("failed", result["status"])
        self.assertEqual("idempotency_key_conflict", result["error"]["code"])
        self.assertFalse((self.workspace() / "b.txt").exists())

        replay = self.invoke(FILE_WRITE_TEXT, first, key="same", confirmed=True, invocation_id="invocation-3")
        self.assertEqual("succeeded", replay["status"])
        self.assertTrue(replay["receipt"]["replayed"])

    def test_read_list_and_hash_return_host_observed_evidence(self):
        source = self.workspace() / "data.txt"
        source.write_text("SignalASI", encoding="utf-8")

        listing = self.invoke(FILE_LIST, {"workspace_id": "task-a"})
        read = self.invoke(FILE_READ_TEXT, {"workspace_id": "task-a", "path": "data.txt"})
        hashed = self.invoke(FILE_SHA256, {"workspace_id": "task-a", "path": "data.txt"})

        self.assertEqual("succeeded", listing["status"])
        self.assertEqual("data.txt", listing["output"]["entries"][0]["path"])
        self.assertEqual("SignalASI", read["output"]["text"])
        self.assertEqual(read["output"]["sha256"], hashed["output"]["sha256"])
        self.assertEqual("passed", hashed["verification"]["status"])

    def test_archive_contains_only_explicit_workspace_files(self):
        workspace = self.workspace()
        (workspace / "a.txt").write_text("a", encoding="utf-8")
        (workspace / "folder").mkdir()
        (workspace / "folder" / "b.txt").write_text("b", encoding="utf-8")
        arguments = {
            "workspace_id": "task-a",
            "paths": ["a.txt", "folder"],
            "output_path": "outputs/result.zip",
        }

        result = self.invoke(ARCHIVE_CREATE, arguments, key="zip-1", confirmed=True)

        self.assertEqual("succeeded", result["status"])
        with zipfile.ZipFile(workspace / "outputs" / "result.zip") as archive:
            self.assertEqual(["a.txt", "folder/b.txt"], sorted(archive.namelist()))
        self.assertEqual("application/zip", result["artifacts"][0]["mime_type"])

    def test_terminal_uses_argument_array_and_blocks_general_shells(self):
        arguments = {
            "workspace_id": "task-a",
            "argv": ["cmd.exe", "/c", "echo unsafe"],
            "timeout_seconds": 10,
        }

        result = self.invoke(TERMINAL_RUN, arguments, key="terminal-1", confirmed=True)

        self.assertEqual("failed", result["status"])
        self.assertEqual("shell_blocked", result["error"]["code"])

    def test_terminal_returns_exit_code_and_bounded_output(self):
        self.workspace()
        arguments = {
            "workspace_id": "task-a",
            "argv": ["python", "-c", "print('ok')"],
            "timeout_seconds": 10,
        }
        with patch.object(self.registry, "_resolve_executable", return_value=sys.executable):
            result = self.invoke(TERMINAL_RUN, arguments, key="terminal-2", confirmed=True)

        self.assertEqual("succeeded", result["status"])
        self.assertEqual(0, result["output"]["exit_code"])
        self.assertEqual("ok", result["output"]["stdout"].strip())

    def test_docx_inspection_does_not_execute_active_content(self):
        source = self.workspace() / "report.docx"
        document_xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body><w:p><w:r><w:t>Hello report</w:t></w:r></w:p></w:body>
</w:document>"""
        with zipfile.ZipFile(source, "w") as archive:
            archive.writestr("word/document.xml", document_xml)

        result = self.invoke(OFFICE_INSPECT, {"workspace_id": "task-a", "path": "report.docx"})

        self.assertEqual("succeeded", result["status"])
        self.assertEqual("word", result["output"]["document_type"])
        self.assertEqual(["Hello report"], result["output"]["text_items"])

    def test_office_conversion_requires_confirmation_and_verifies_artifact(self):
        source = self.workspace() / "report.docx"
        with zipfile.ZipFile(source, "w") as archive:
            archive.writestr(
                "word/document.xml",
                '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body/></w:document>',
            )
        arguments = {
            "workspace_id": "task-a",
            "path": "report.docx",
            "output_format": "pdf",
            "output_path": "outputs/report.pdf",
        }

        with patch.object(self.registry, "_run_office_conversion", side_effect=lambda _id, _src, dst, _fmt: dst.write_bytes(b"%PDF-test")):
            result = self.invoke(OFFICE_CONVERT, arguments, key="office-1", confirmed=True)

        self.assertEqual("succeeded", result["status"])
        self.assertEqual("application/pdf", result["output"]["mime_type"])
        self.assertEqual("passed", result["verification"]["status"])
        self.assertEqual("desktop_workspace", result["artifacts"][0]["location"])

    def test_excel_text_conversion_flattens_rows_instead_of_creating_empty_output(self):
        source = self.workspace() / "data.xlsx"
        with zipfile.ZipFile(source, "w") as archive:
            archive.writestr(
                "xl/workbook.xml",
                '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
                '<sheets><sheet name="Sheet1" sheetId="1"/></sheets></workbook>',
            )
            archive.writestr(
                "xl/sharedStrings.xml",
                '<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
                '<si><t>Name</t></si><si><t>SignalASI</t></si></sst>',
            )
            archive.writestr(
                "xl/worksheets/sheet1.xml",
                '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>'
                '<row><c t="s"><v>0</v></c><c t="s"><v>1</v></c></row>'
                '</sheetData></worksheet>',
            )
        arguments = {
            "workspace_id": "task-a",
            "path": "data.xlsx",
            "output_format": "txt",
            "output_path": "outputs/data.txt",
        }

        result = self.invoke(OFFICE_CONVERT, arguments, key="office-text-1", confirmed=True)

        self.assertEqual("succeeded", result["status"])
        self.assertEqual("Name\tSignalASI", (self.workspace() / "outputs" / "data.txt").read_text(encoding="utf-8"))

    @unittest.skipUnless(sys.platform == "win32", "Windows-only host probes")
    def test_windows_status_and_process_inventory_execute_real_host_probes(self):
        status = self.invoke(SYSTEM_STATUS, {})
        processes = self.invoke(PROCESS_LIST, {"max_entries": 5})

        self.assertEqual("succeeded", status["status"])
        self.assertGreater(status["output"]["logical_cpu_count"], 0)
        self.assertEqual("succeeded", processes["status"])
        self.assertGreater(processes["output"]["count"], 0)


if __name__ == "__main__":
    unittest.main()
