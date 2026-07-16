import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from desktop_file_tools import try_execute_explicit_file_task


class DesktopFileToolTests(unittest.TestCase):
    def test_explicit_csv_summary_calculates_revenue_without_model(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "sales.csv"
            source.write_text(
                "product,units,price\nAlpha,3,12.50\nBeta,2,8.00\nGamma,5,4.20\n",
                encoding="utf-8",
            )

            result = try_execute_explicit_file_task(
                "Summarize this CSV and calculate total revenue", [source], root / "outputs"
            )

        self.assertIsNotNone(result)
        self.assertEqual("csv_summary", result.operation)
        self.assertIn("| Alpha | 3 | 12.50 | 37.50 |", result.message)
        self.assertIn("**Total revenue: 74.50**", result.message)
        self.assertIsNone(result.output_path)

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

    def test_attachment_only_policy_does_not_trigger_csv_summary(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "phone-test.csv"
            source.write_text("name,value\nAlpha,1\n", encoding="utf-8")
            prompt = (
                "The user attached files without stating a task. Ask one concise question about what to do. "
                "Mention only the file names; do not inspect, summarize, or return the attachments.\n\n"
                "Attached input:\n- phone-test.csv (text/csv, 19 B)\n"
                "Do not inspect the attached content until the user provides a task."
            )
            result = try_execute_explicit_file_task(prompt, [source], Path(temporary) / "outputs")
        self.assertIsNone(result)

    def test_chinese_attachment_only_policy_does_not_trigger_csv_summary(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "phone-test.csv"
            source.write_text("name,value\nAlpha,1\n", encoding="utf-8")
            prompt = (
                "\u7528\u6237\u53ea\u4e0a\u4f20\u4e86\u9644\u4ef6\uff0c\u6ca1\u6709\u8bf4\u660e\u4efb\u52a1\u3002"
                "\u8bf7\u53ea\u95ee\u4e00\u4e2a\u7b80\u77ed\u95ee\u9898\u786e\u8ba4\u8981\u505a\u4ec0\u4e48\uff1b"
                "\u4ec5\u63d0\u6587\u4ef6\u540d\uff0c\u4e0d\u8981\u8bfb\u53d6\u3001\u603b\u7ed3\u6216\u56de\u4f20\u9644\u4ef6\u3002"
            )
            result = try_execute_explicit_file_task(prompt, [source], Path(temporary) / "outputs")
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

    def test_invalid_excel_returns_actionable_error_without_model_fallback(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "corrupt-test.xlsx"
            source.write_bytes(b"not an xlsx archive")

            result = try_execute_explicit_file_task(
                "Convert this spreadsheet to CSV", [source], root / "outputs"
            )

        self.assertIsNotNone(result)
        self.assertEqual("excel_conversion_failed", result.operation)
        self.assertIn("isn't a valid or readable Excel workbook", result.message)
        self.assertIn("try repairing it", result.message)
        self.assertIsNone(result.output_path)


if __name__ == "__main__":
    unittest.main()
