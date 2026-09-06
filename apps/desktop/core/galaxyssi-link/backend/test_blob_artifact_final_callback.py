"""Execute the production final callback with a real workspace and isolated transport."""
import ast
import inspect
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from agent_execution_harness import ArtifactFinalization
import artifact_delivery as delivery
import mqtt_bridge
import response_policy
import test_blob_artifact_peer as fixtures


class BlobArtifactFinalCallbackTests(unittest.TestCase):
    setUp = fixtures.BlobArtifactPeerTests.setUp
    stop = fixtures.BlobArtifactPeerTests.stop
    enable = fixtures.BlobArtifactPeerTests.enable

    def callback(self):
        # Compile the unchanged production callback body; only its surrounding
        # provider loop is omitted, so no external model or real phone is used.
        tree = ast.parse(inspect.getsource(mqtt_bridge._start_remote_agent_task))
        function = next(node for node in tree.body[0].body if isinstance(node, ast.FunctionDef)
                        and node.name == "publish_result")
        module = ast.Module(body=[function], type_ignores=[])
        namespace = {**mqtt_bridge.__dict__, "fast_chat_delivery": False, "plan_only": False,
            "agent_id": "codex", "full_desktop_executor": False, "structured_connector_response": False,
            "contact_id": self.payload["contact_id"], "source_message_id": self.payload["source_message_id"],
            "client_conversation_id": self.payload["conversation_id"], "client_route_id": self.route,
            "wire_payload": {"scheme": "signal", "_client_route_id": self.route}, "mqttc": None,
            "current_user_request": "Return the generated file", "add_task_trace": Mock(),
            "task_trace_snapshot": Mock(return_value=[]), "_task_reputation_evidence": Mock(return_value=(None, None)),
            "_log_task_latency": Mock(), "_wire_down_topic": Mock(return_value="test"),
            "response_language_tag": response_policy.response_language_tag}
        exec(compile(module, inspect.getfile(mqtt_bridge), "exec"), namespace)
        return namespace["publish_result"]

    def test_unavailable_transport_reports_failure_and_manual_retry_restores_original_card(self):
        with self.source.open("wb") as stream:
            stream.truncate(delivery.MAX_ARTIFACT_BYTES + 1)
        files = [{"name": self.source.name, "relative_path": self.artifact.relative_path,
                  "size": self.source.stat().st_size}]
        task = {**self.payload, "status": "completed", "result": "Generated report.",
            "client_conversation_id": self.payload["conversation_id"], "client_turn_id": self.payload["turn_id"],
            "output_files": files}
        with patch("blob_pair_configuration.can_receive_artifacts", return_value=False), \
                patch("agent_execution_harness.finalize_task_artifacts",
                      return_value=ArtifactFinalization(output_files=tuple(files), verification={"status": "passed"})), \
                patch.object(mqtt_bridge, "_publish_or_queue_task_result") as final, \
                patch("agent_latency.record_task"):
            self.callback()(task)
        final.assert_not_called()
        notice = self.bridge._publish_to_registered_client.call_args.args[2]
        self.assertEqual("artifact_blob_transport_required", notice["artifact_delivery"]["error_code"])
        self.assertNotIn("rich_output", notice)
        self.assertTrue(self.source.exists())
        self.assertFalse(self.archive.path.exists())
        stored = SimpleNamespace(task_id=task["task_id"], client_route_id=self.route, status="completed",
                                 result=task["result"], public=lambda: task)
        with patch.object(mqtt_bridge, "agent_task_manager", SimpleNamespace(get=lambda _: stored)):
            result = mqtt_bridge.republish_agent_task_result(task["task_id"])
        self.assertTrue(result["ok"], result)
        self.assertEqual({"held": 1}, self.runtime.sender.journal.snapshot())
        self.runtime.sender._register_batches()
        final_wire = self.bridge._publish_to_registered_client.call_args.args[2]
        self.assertIn("rich_output", final_wire)
        self.assertNotIn("artifact_delivery", final_wire)
        self.assertEqual(task["turn_id"], final_wire["turn_id"])
        self.assertTrue(self.source.exists())
        self.bridge._publish_task_artifacts.assert_not_called()


if __name__ == "__main__":
    unittest.main()
