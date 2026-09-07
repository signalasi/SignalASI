import base64
import hashlib
import json
from pathlib import Path
from unittest.mock import Mock, patch

import pytest

from programmatic_video_task import parse_video_plan, run_programmatic_video_task
from video_generation_policy import video_creation_requested
from video_transport import VideoError, transcode_240p


PLAN = {"summary": "Chip principles", "duration_seconds": 32,
        "scenes": [{"start": 0, "end": 16, "description": "Transistor switches"},
                   {"start": 16, "end": 32, "description": "Logic gates"}]}


@pytest.mark.parametrize("text", [
    "\u8bf7\u751f\u6210\u82af\u7247\u5de5\u4f5c\u539f\u7406\u7684\u89c6\u9891", "\u7528AI\u751f\u6210\u89c6\u9891", "AI\u505a\u4e00\u4e2a\u77ed\u7247", "\u751f\u6210AI\u89c6\u9891",
    "\u7528LTX\u751f\u6210\u89c6\u9891", "\u7528Python\u505a\u79d1\u666e\u52a8\u753b", "\u8bf7\u505a\u4eba\u7269\u52a8\u4f5c\u89c6\u9891", "\u8bf7\u5236\u4f5c\u521b\u610f\u77ed\u7247",
    "Create a realistic video", "Make an algorithm animation", "Generate a camera orbit clip",
    "Produce a product commercial", "Render a landscape film", "\u505a\u4e00\u4e2a\u903b\u8f91\u95e8\u89e3\u91ca\u89c6\u9891",
    "\u505a\u5199\u5b9e\u8857\u666f\u89c6\u9891", "\u505a\u79d1\u5e7b\u57ce\u5e02\u89c6\u9891", "\u505a\u955c\u5934\u8fd0\u52a8\u89c6\u9891", "\u505a\u4ea7\u54c1\u5c55\u793a\u89c6\u9891",
])
def test_all_video_wording_uses_the_same_route(text):
    assert video_creation_requested(text)
    from agent_execution_harness import AgentTaskKind, execution_policy_for
    policy = execution_policy_for(text)
    assert policy.task_kind == AgentTaskKind.ARTIFACT
    assert policy.requires_artifact
    assert policy.no_progress_timeout_seconds >= 360


@pytest.mark.parametrize("text", [
    "\u5982\u4f55\u751f\u6210\u89c6\u9891", "\u4e0d\u8981\u751f\u6210\u89c6\u9891", "\u53ea\u5199\u5206\u955c\uff0c\u4e0d\u8981\u751f\u6210\u89c6\u9891", "\u751f\u6210\u89c6\u9891\u7684\u63d0\u793a\u8bcd",
    "Generate a video thumbnail", "Only write a storyboard to make a video", "How to create a video?",
    "Do not generate AI video", "Can GPT-6 generate videos?", "\u5f00\u53d1AI\u89c6\u9891\u751f\u6210\u529f\u80fd",
    "\u5206\u6790\u8fd9\u4e2a\u89c6\u9891", "\u64ad\u653e\u89c6\u9891", "\u6211\u6628\u5929\u751f\u6210\u4e86\u89c6\u9891", 'The phrase "generate a video" is an example',
    "```generate video```", "\u751f\u6210\u89c6\u9891\u7684\u539f\u7406", "\u8bf7\u751f\u6210\u56fe\u7247",
])
def test_questions_negations_and_nonvideo_deliverables_do_not_render(text):
    assert not video_creation_requested(text)


@pytest.mark.parametrize("value", ["Done", "[]", '{}', json.dumps({**PLAN, "duration_seconds": True}),
    json.dumps({**PLAN, "duration_seconds": 121}), json.dumps({**PLAN, "scenes": []}),
    json.dumps({**PLAN, "scenes": [{"start": 1, "end": 32, "description": "x"}]}),
    json.dumps({**PLAN, "scenes": [{"start": 0, "end": 31, "description": "x"}]})])
def test_invalid_plans(value):
    with pytest.raises(VideoError):
        parse_video_plan(value)


@pytest.fixture
def isolated(tmp_path, monkeypatch):
    monkeypatch.setenv("GALAXYSSI_WORKSPACE_ROOT", str(tmp_path))
    monkeypatch.setenv("GALAXYSSI_STATE_DIR", str(tmp_path / "state"))
    monkeypatch.setenv("GALAXYSSI_STATE_MASTER_KEY", base64.urlsafe_b64encode(b"0" * 32).decode())
    return tmp_path


def run_fixture(root, *, review=None, no_source=False, cancel=None, media_fail=False, prompt="Create a video", render_prompts=None):
    calls = []
    directory = root / "tasks/video-test/.video-generation"
    def invoke(stage, text, readonly, remaining):
        calls.append(stage)
        assert remaining > 0
        if stage == "plan":
            assert readonly
            return json.dumps(PLAN)
        if stage == "render":
            assert not readonly
            if render_prompts is not None:
                render_prompts.append(text)
            if not no_source:
                (directory / "source.mp4").write_bytes(b"synthetic-test-only")
            return "Generated"
        assert stage == "review" and readonly
        return json.dumps(review if review is not None else {"approved": True, "issues": []})
    def media(command, **kwargs):
        if "-frames:v" in command:
            Path(command[-1]).write_bytes(command[-1].encode())
        elif media_fail:
            raise VideoError("decode failed")
        return b""
    def inspect(path, **kwargs):
        if not path.is_file():
            raise VideoError("video_source_size_invalid")
        return {"duration": 32, "width": 1280, "height": 720, "size_bytes": 20, "has_audio": False}
    def transcode(source, destination, **kwargs):
        destination.write_bytes(b"240p-test-only")
        return {"duration": 32, "width": 426, "height": 240, "size_bytes": 14, "has_audio": False}
    with patch("programmatic_video_task.media_executable", side_effect=lambda n: n), \
         patch("programmatic_video_task.run_media", side_effect=media), \
         patch("programmatic_video_task.inspect_video", side_effect=inspect), \
         patch("programmatic_video_task.transcode_240p", side_effect=transcode):
        reply = run_programmatic_video_task(task_id="video-test", agent_id="codex", prompt=prompt,
            invoke=invoke, check=cancel or (lambda: None), progress=lambda *a: None, planner_model="gpt-6-astra")
    return reply, calls


def test_full_pipeline_resume_and_artifact(isolated):
    prompts = []
    reply, calls = run_fixture(isolated, render_prompts=prompts)
    assert calls == ["plan", "render", "review"]
    assert "high-contrast throughout transitions" in prompts[0]
    assert "transition midpoints at 240p" in prompts[0]
    assert "cache static backgrounds/fonts" in prompts[0]
    assert "Programmatically" in reply and "not native" in reply
    assert run_fixture(isolated)[1] == []
    checkpoint = isolated / "tasks/video-test/.video-generation/job.json"
    assert PLAN["summary"] not in checkpoint.read_text()
    from task_workspace import task_artifacts
    from rich_output import build_rich_output
    from artifact_delivery import artifact_chunk_payloads, prepare_artifacts
    files = task_artifacts("video-test")
    assert [f["relative_path"] for f in files] == ["outputs/video-240p.mp4"]
    _, document = build_rich_output(reply, files, "video-test")
    assert not next(b for b in document["blocks"] if b["type"] == "video").get("data_b64")
    chunks = list(artifact_chunk_payloads(prepare_artifacts("video-test", files)[0]))
    data = b"".join(base64.b64decode(chunk["data_b64"]) for chunk in chunks)
    assert hashlib.sha256(data).hexdigest() == chunks[0]["sha256"]


@pytest.mark.parametrize("kwargs,error", [({"no_source": True}, "render_no_output"),
    ({"review": {"approved": False, "issues": ["cannot view images"]}}, "review_failed"),
    ({"media_fail": True}, "decode failed"), ({"prompt": "Create a 5 second video"}, "clarification")])
def test_failures_never_publish_success(isolated, kwargs, error):
    with pytest.raises(VideoError, match=error):
        run_fixture(isolated, **kwargs)
    assert not (isolated / "tasks/video-test/outputs/video-240p.mp4").exists()


def test_cancellation_stops_before_work(isolated):
    def stop():
        raise VideoError("cancelled")
    with pytest.raises(VideoError, match="cancelled"):
        run_fixture(isolated, cancel=stop)


def test_checkpoint_is_bound_to_request(isolated):
    run_fixture(isolated)
    with pytest.raises(VideoError, match="binding_mismatch"):
        run_fixture(isolated, prompt="Make a different video")


def test_resume_preserves_specific_review_feedback(isolated):
    with pytest.raises(VideoError, match="review_failed"):
        run_fixture(isolated, review={"approved": False, "issues": ["Gate outline overlaps its label"]})
    prompts = []
    _, calls = run_fixture(isolated, render_prompts=prompts)
    assert calls == ["render", "review"]
    assert "Gate outline overlaps its label" in prompts[0]


def test_gateway_keeps_selected_model_and_stage_permissions(isolated):
    import agent_gateway
    from desktop_agent_adapters import AgentAdapterRequest
    def exercise(**kwargs):
        assert kwargs["planner_model"] == "gpt-6-astra"
        kwargs["invoke"]("plan", "storyboard", True, 100)
        kwargs["invoke"]("render", "render animation", False, 100)
        kwargs["invoke"]("review", "inspect images", True, 100)
        return "Video verified"
    with patch("agent_task_manager.agent_task_manager") as manager, \
         patch("programmatic_video_task.run_programmatic_video_task", side_effect=exercise), \
         patch.object(agent_gateway, "_ask_agent_sync_inner", return_value="ok") as cli:
        manager.get.return_value = None
        result = agent_gateway._execute_agent_adapter_request("codex", AgentAdapterRequest(
            agent_id="codex", run_id="video-gateway", prompt="Create a video", checkpoint={
                "agent_model_id": "gpt-6-astra", "desktop_access_profile": "desktop_executor"}))
        assert "verified" in result
        assert [c.kwargs["plan_only"] for c in cli.call_args_list] == [True, False, True]
        assert all(c.kwargs["agent_model_id"] == "gpt-6-astra" for c in cli.call_args_list)
        assert [c.kwargs["codex_video_permissions"] for c in cli.call_args_list] == [
            "read-only", "workspace-write", "read-only"]


@pytest.mark.parametrize("profile", [None, "restricted", "unknown"])
def test_video_requires_executor_grant(profile):
    from video_execution_permissions import require_video_executor
    with pytest.raises(VideoError, match="executor_required"):
        require_video_executor({"desktop_access_profile": profile})


@pytest.mark.parametrize("prompt,mode,generic", [
    ("Create an 8 second video", "auto_complete", True),
    ("Explain binary counting", "auto_complete", False),
    ("Create an 8 second video", "plan_only", False),
])
def test_mqtt_codex_video_uses_verified_runner(isolated, prompt, mode, generic):
    import agent_gateway
    import mqtt_bridge
    from pairing_access import grant_for_executor
    class Dispatched(Exception):
        pass
    manager = Mock()
    manager.get.return_value = None
    manager.active_for_conversation.return_value = None
    manager.create.side_effect = Dispatched()
    manager.create_external.side_effect = Dispatched()
    payload = {"agent_id": "codex", "contact_id": "codex", "client_message_id": "m1",
               "client_route_id": "phone-test", "conversation_id": "conv-test",
               "task_id": "mqtt-video-test", "turn_id": "turn-test", "execution_mode": mode}
    with patch.object(mqtt_bridge, "agent_task_manager", manager), \
         patch.object(mqtt_bridge, "get_client", return_value={"access": grant_for_executor(True)}), \
         patch.object(agent_gateway, "all_agent_specs", return_value=agent_gateway.BASE_AGENTS), \
         patch.object(agent_gateway, "_command_for", return_value=["codex", "exec", "-"]), \
         pytest.raises(Dispatched):
        mqtt_bridge._start_remote_agent_task(Mock(), {"scheme": "signal", "_client_route_id": "phone-test"},
                                             payload, [], prompt, "text")
    assert manager.create.called is generic
    assert manager.create_external.called is not generic


def test_video_rechecks_current_pairing_grant():
    from video_execution_permissions import require_video_executor
    checkpoint = {"desktop_access_profile": "desktop_executor", "client_route_id": "phone-test"}
    with patch("pairing_state.get_client", return_value=None), \
         patch("pairing_access.has_full_executor", return_value=False):
        with pytest.raises(VideoError, match="executor_revoked"):
            require_video_executor(checkpoint)
    with patch("pairing_state.get_client", return_value={}) as client, \
         patch("pairing_access.has_full_executor", return_value=True):
        require_video_executor(checkpoint)
        client.assert_called_once_with("phone-test")


@pytest.mark.parametrize("mode", ["read-only", "workspace-write"])
def test_video_codex_command_scopes_permissions(mode):
    import agent_gateway
    import os
    command = agent_gateway._video_codex_command([
        "codex", "exec", "--dangerously-bypass-approvals-and-sandbox", "-"], mode)
    assert "--dangerously-bypass-approvals-and-sandbox" not in command
    assert command[command.index("--sandbox") + 1] == mode
    assert 'approval_policy="never"' in command
    if os.name == "nt":
        assert 'windows.sandbox="elevated"' in command
    assert command[-1] == "-"


def test_render_directory_rejects_outside_workspace(tmp_path):
    from video_execution_permissions import prepare_render_directory
    root = tmp_path / "task"
    root.mkdir()
    with pytest.raises(VideoError, match="workspace_path_rejected"):
        prepare_render_directory(tmp_path, root)


def test_windows_render_acl_grants_only_current_user(tmp_path):
    import os
    import subprocess
    from video_execution_permissions import prepare_render_directory
    if os.name != "nt":
        pytest.skip("Windows-specific output ownership")
    private = tmp_path / ".video-generation"
    private.mkdir()
    sid = "S-1-5-21-111-222-333-1001"
    identity = subprocess.CompletedProcess([], 0, f'"host\\user","{sid}"\r\n'.encode(), b"")
    with patch("video_execution_permissions.subprocess.run", return_value=identity) as run:
        prepare_render_directory(private, tmp_path)
        assert run.call_count == 2
        command = run.call_args.args[0]
        assert command[1:] == [str(private.resolve()), "/grant", f"*{sid}:(OI)(CI)(M)"]
        assert "/T" not in command


def test_readonly_codex_mode_removes_obsolete_or_bypass_flags():
    import agent_gateway
    command = agent_gateway._plan_only_command(agent_gateway.BASE_AGENTS["codex"], [
        "codex", "exec", "--ask-for-approval", "untrusted", "--dangerously-bypass-approvals-and-sandbox", "-"])
    assert "--ask-for-approval" not in command
    assert "--dangerously-bypass-approvals-and-sandbox" not in command
    assert command[command.index("--sandbox") + 1] == "read-only"
    assert 'approval_policy="never"' in command and command[-1] == "-"


def test_cancelled_media_process_is_reaped(tmp_path):
    import sys
    import time
    from video_transport import run_media
    count = 0
    def cancel():
        nonlocal count
        count += 1
        if count > 1:
            raise VideoError("cancelled")
    start = time.monotonic()
    with pytest.raises(VideoError, match="cancelled"):
        run_media([sys.executable, "-c", "import time; time.sleep(20)"], check=cancel, timeout=25)
    assert time.monotonic() - start < 5


@pytest.mark.parametrize("size,audio", [("1280x720", True), ("720x1280", False), ("160x90", False)])
def test_real_ffmpeg_240p_keeps_original_and_audio(tmp_path, size, audio):
    import shutil
    import subprocess
    ffmpeg, ffprobe = shutil.which("ffmpeg"), shutil.which("ffprobe")
    if not ffmpeg or not ffprobe:
        pytest.skip("FFmpeg and FFprobe required")
    source, output = tmp_path / "source.mp4", tmp_path / "240p.mp4"
    command = [ffmpeg, "-v", "error", "-f", "lavfi", "-i", f"testsrc2=size={size}:rate=30"]
    if audio:
        command += ["-f", "lavfi", "-i", "sine=frequency=440:sample_rate=24000"]
    command += ["-t", "2", "-c:v", "libx264", "-preset", "ultrafast", "-threads", "2", "-pix_fmt", "yuv420p"]
    if audio:
        command += ["-c:a", "aac"]
    subprocess.run(command + [str(source)], check=True, capture_output=True, timeout=40)
    original = source.read_bytes()
    with patch("video_transport.media_executable", side_effect=lambda n: ffmpeg if n == "ffmpeg" else ffprobe):
        result = transcode_240p(source, output)
        assert result["codec"] == "h264" and result["has_audio"] == audio
        assert min(result["width"], result["height"]) <= min(240, min(map(int, size.split("x"))))
        assert result["fps"] <= 24.01 and abs(result["duration"] - 2) <= 0.5
        assert source.read_bytes() == original
        header = output.read_bytes()
        assert header.index(b"moov") < header.index(b"mdat")
