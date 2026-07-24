import unittest

import mqtt_bridge


class MqttInitialNarrationTests(unittest.TestCase):
    def test_chinese_image_edit_acknowledges_before_work_starts(self):
        text = mqtt_bridge._initial_codex_narration(
            "请批改图片并把批注图发回来",
            has_attachments=True,
            has_image_attachment=True,
            image_artifact_required=True,
        )
        self.assertIn("收到", text)
        self.assertIn("返回", text)

    def test_chinese_image_review_does_not_claim_an_edit(self):
        text = mqtt_bridge._initial_codex_narration(
            "请检查这张图片",
            has_attachments=True,
            has_image_attachment=True,
            image_artifact_required=False,
        )
        self.assertIn("检查", text)
        self.assertNotIn("修改并返回", text)

    def test_file_task_acknowledges_inspection_and_verification(self):
        text = mqtt_bridge._initial_codex_narration(
            "分析这个文件",
            has_attachments=True,
            has_image_attachment=False,
            image_artifact_required=False,
        )
        self.assertIn("文件", text)
        self.assertIn("验证", text)

    def test_english_text_task_uses_concise_acknowledgement(self):
        text = mqtt_bridge._initial_codex_narration(
            "Summarize the latest news",
            has_attachments=False,
            has_image_attachment=False,
            image_artifact_required=False,
        )
        self.assertEqual("Got it. I will handle this now.", text)


if __name__ == "__main__":
    unittest.main()
