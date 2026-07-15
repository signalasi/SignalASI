import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from desktop_file_tools import try_execute_explicit_file_task


class DesktopFileToolTests(unittest.TestCase):
    def test_explicit_excel_pdf_conversion_uses_deterministic_tool(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "test.xlsx"
            source.write_bytes(b"xlsx")

            def fake_convert(_source, target, _format):
                target.write_bytes(b"pdf")

            with patch("desktop_file_tools._run_excel_conversion", side_effect=fake_convert):
                result = try_execute_explicit_file_task(
                    "\u4fdd\u5b58\u6210 PDF", [source], root / "outputs"
                )

            self.assertIsNotNone(result)
            self.assertEqual("excel_to_pdf", result.operation)
            self.assertEqual("test.pdf", result.output_path.name)

    def test_ambiguous_file_request_stays_on_model_route(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "test.xlsx"
            source.write_bytes(b"xlsx")
            result = try_execute_explicit_file_task("\u5904\u7406\u4e00\u4e0b", [source], Path(temporary) / "outputs")
        self.assertIsNone(result)

    def test_multiple_files_stay_on_model_route(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = root / "a.xlsx"
            second = root / "b.xlsx"
            first.write_bytes(b"a")
            second.write_bytes(b"b")
            result = try_execute_explicit_file_task("convert to PDF", [first, second], root / "outputs")
        self.assertIsNone(result)


if __name__ == "__main__":
    unittest.main()
