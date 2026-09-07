"""Storyboard -> coding-agent render -> visual review -> verified MP4 artifact."""
from __future__ import annotations

import hashlib
import json
import math
import os
import re
import threading
import time
from pathlib import Path

from secure_state import read_secure_json, write_secure_json
from video_generation_policy import VIDEO_PLANNING_CONTRACT
from video_transport import VideoError, inspect_video, media_executable, run_media, transcode_240p

PURPOSE = "programmatic-video-job-v1"
_LOCKS = tuple(threading.Lock() for _ in range(64))


def json_reply(reply):
    text = reply.strip()
    if text.startswith("```json") and text.endswith("```"):
        text = text[7:-3].strip()
    try:
        value = json.loads(text)
        if not isinstance(value, dict):
            raise ValueError()
        return value
    except (ValueError, TypeError):
        raise VideoError("video_response_invalid: expected a JSON object") from None


def parse_video_plan(reply):
    value = json_reply(reply)
    if value.get("needs_clarification"):
        raise VideoError("video_needs_clarification: " + str(value["needs_clarification"])[:500])
    try:
        duration, summary, scenes = value["duration_seconds"], value["summary"], value["scenes"]
        if (type(duration) is not int or not 2 <= duration <= 120
                or not isinstance(summary, str) or not 1 <= len(summary.strip()) <= 500
                or not isinstance(scenes, list) or not 1 <= len(scenes) <= 16):
            raise ValueError()
        previous = 0
        for scene in scenes:
            start, end, description = scene["start"], scene["end"], scene["description"]
            if (type(start) not in (float, int) or type(end) not in (float, int)
                    or not math.isfinite(start) or not math.isfinite(end)
                    or abs(start - previous) > 0.001 or not start < end <= duration
                    or not isinstance(description, str) or not 1 <= len(description.strip()) <= 2000):
                raise ValueError()
            previous = end
        if abs(previous - duration) > 0.001:
            raise ValueError()
        return {"summary": summary, "duration_seconds": duration, "scenes": scenes}
    except (ValueError, TypeError, KeyError):
        raise VideoError("video_plan_invalid: require contiguous timed scenes covering the requested duration") from None


def digest_file(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def run_programmatic_video_task(**kwargs):
    lock = _LOCKS[int(hashlib.sha256(kwargs["task_id"].encode()).hexdigest()[:8], 16) % len(_LOCKS)]
    deadline = time.monotonic() + kwargs.get("timeout", 900)
    while not lock.acquire(timeout=0.2):
        kwargs["check"]()
        if time.monotonic() >= deadline:
            raise VideoError("video_busy_timeout")
    try:
        kwargs["timeout"] = max(0, deadline - time.monotonic())
        return _run(**kwargs)
    finally:
        lock.release()


def _run(*, task_id, agent_id, prompt, invoke, check, progress, timeout=900, planner_model=""):
    from task_workspace import task_workspace
    check()
    ffmpeg, ffprobe = media_executable("ffmpeg"), media_executable("ffprobe")
    root = task_workspace(task_id, agent_id)
    private = root / ".video-generation"
    private.mkdir(exist_ok=True)
    from video_execution_permissions import prepare_render_directory
    prepare_render_directory(private, root)
    source = private / "source.mp4"
    output = root / "outputs" / "video-240p.mp4"
    checkpoint = private / "job.json"
    deadline = time.monotonic() + timeout

    def guard():
        check()
        if time.monotonic() >= deadline:
            raise VideoError("video_timeout: resume from the saved task checkpoint")
        for path in (private, source, output, checkpoint):
            if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
                raise VideoError("video_workspace_path_rejected")

    def call(stage, text, readonly):
        guard()
        value = invoke(stage, text, readonly, max(1, deadline - time.monotonic()))
        guard()
        return value

    def save():
        guard()
        write_secure_json(checkpoint, state, purpose=PURPOSE)

    guard()
    binding = hashlib.sha256(f"{agent_id}\0{planner_model}\0{prompt}".encode()).hexdigest()
    state = read_secure_json(checkpoint, purpose=PURPOSE, allow_legacy_plaintext=False).value if checkpoint.exists() else {}
    if state and state.get("binding") != binding:
        raise VideoError("video_checkpoint_binding_mismatch")
    if not state:
        progress("video_plan", "Planning storyboard and scientific checks", "running")
        plan = parse_video_plan(call("plan", VIDEO_PLANNING_CONTRACT + "\nUser request:\n" + prompt, True))
        durations = re.findall(r"(?<![\d.])(\d+(?:\.\d+)?)\s*(?:seconds?\b|secs?\b|\u79d2)", prompt, re.I)
        if len(durations) == 1 and float(durations[0]) != plan["duration_seconds"]:
            raise VideoError("video_needs_clarification: planned duration differs from the explicit request")
        state = {"binding": binding, "plan": plan, "method": "programmatic", "status": "planned"}
        save()
    plan = state["plan"]
    if not (source.is_file() and state.get("source_sha256") == digest_file(source)
            and state.get("visual_review", {}).get("approved") is True):
        # A rebuilt source must never reuse a derivative approved for older bytes.
        guard()
        state.pop("output_sha256", None)
        state.pop("media", None)
        output.unlink(missing_ok=True)
        feedback = str(state.get("review_feedback") or "")[:4000]
        for attempt in range(2):
            guard()
            progress("video_render", "Writing and rendering the animation" if not attempt else "Correcting the animation", "running")
            state["status"] = "rendering"
            save()
            call("render", (
                "Create a real programmatically animated MP4 using your existing terminal/file tools. "
                "Do not just describe steps. Do not use native video models, cloud video APIs or download weights. "
                "When review feedback exists, patch the existing script and preserve already-correct scenes; "
                "do not restart the animation design or reintroduce earlier errors. "
                "Use Python/Pillow/NumPy or installed local animation tools and FFmpeg. Check Python libraries "
                "and a Chinese-capable font first. Do not install software automatically. Write reproducible "
                "source code under .video-generation/render.py and the finished source video ONLY to "
                ".video-generation/source.mp4. Do not publish files under outputs yet. "
                "Use H.264, yuv420p, <=24fps, faststart; preserve requested aspect ratio. "
                "Make labels and captions legible after reduction to a 240-pixel short edge. Animate changes "
                "over time, not static slides. Verify factual logic in science scenes and label simplifications. "
                "Keep text high-contrast throughout transitions, not only at keyframes: use a stable "
                "contrasting label plate or outline rather than fading text with its background. "
                "First render and inspect still previews including transition midpoints at 240p, correct "
                "legibility/layout, and only then render the complete clip. For simple explainers prefer "
                "640x360 source at 12fps unless the request needs more; cache static backgrounds/fonts "
                "instead of recomputing expensive effects for every frame. Avoid decorative effects "
                "that slow rendering or obscure the requested content. "
                "Use existing TTS only if available; do not present tones/music as narration. "
                "Generate background audio programmatically only when suitable/requested. "
                "If the request is impossible here, explain the limitation without claiming success.\n"
                f"Task directory: {root}\nFFmpeg: {ffmpeg}\nFFprobe: {ffprobe}\n"
                "Use absolute paths for all file operations and explicitly set the shell working directory; "
                "do not assume a child shell inherits the task directory.\n"
                "Approved storyboard: " + json.dumps(plan, ensure_ascii=False)
                + "\nUser request: " + prompt + "\nReview feedback: " + feedback
            ), False)
            guard()
            if not source.is_file():
                raise VideoError("video_render_no_output: the coding Agent did not create the MP4; check its tool permissions and local dependencies")
            info = inspect_video(source, check=guard)
            if abs(info["duration"] - plan["duration_seconds"]) > 0.5:
                feedback = "Rendered duration does not match the storyboard. Fix it."
                state.update(review_feedback=feedback, status="needs_revision")
                save()
                continue
            progress("video_preview", "Extracting and reviewing actual rendered frames", "running")
            previews = []
            times = sorted({(scene["start"] + scene["end"]) / 2 for scene in plan["scenes"]}
                           | {info["duration"] * part for part in (0.15, 0.5, 0.85)})
            for index, timestamp in enumerate(times):
                preview = private / f"preview-{index}.png"
                if preview.is_symlink():
                    raise VideoError("video_workspace_path_rejected")
                run_media([ffmpeg, "-v", "error", "-nostdin", "-y", "-protocol_whitelist", "file,pipe",
                           "-ss", str(timestamp), "-i", str(source),
                           "-frames:v", "1", "-vf", "scale=640:-2", "-threads", "2", str(preview)],
                          check=guard, timeout=30)
                if not preview.is_file() or not 0 < preview.stat().st_size <= 5 * 1024 * 1024:
                    raise VideoError("video_preview_missing")
                previews.append(str(preview))
            if len({digest_file(Path(path)) for path in previews}) < 2:
                feedback = "Sampled frames are identical. Render actual animated changes, not a still image."
                state.update(review_feedback=feedback, status="needs_revision")
                save()
                continue
            review = json_reply(call("review", (
                "Read-only visual review: use your image-viewing tool to inspect EVERY listed frame. "
                "Check nonblank content, readable Chinese, overlaps, captions, scientific consistency and "
                "the approved storyboard. Check render.py for actual animation, not a still-frame placeholder. "
                "Return ONLY JSON {approved: boolean, issues: [strings]}. If images cannot be inspected, "
                "approved MUST be false. Never infer approval just from file existence.\nFrames: "
                + json.dumps(previews) + f"\nScript: {private / 'render.py'}\nStoryboard: "
                + json.dumps(plan, ensure_ascii=False)
            ), True))
            if (review.get("approved") is True and review.get("issues") == []):
                state.update(source_sha256=digest_file(source), visual_review=review, status="reviewed")
                state.pop("review_feedback", None)
                save()
                break
            feedback = json.dumps(review, ensure_ascii=False)[:4000]
            state.update(review_feedback=feedback, status="needs_revision")
            save()
        else:
            raise VideoError("video_review_failed: bounded correction attempts exhausted; no success artifact published")
    if not (output.is_file() and state.get("output_sha256") == digest_file(output)):
        progress("video_verify", "Verifying full decode and preparing 240p playback", "running")
        run_media([ffmpeg, "-v", "error", "-xerror", "-nostdin", "-protocol_whitelist", "file,pipe",
                   "-i", str(source), "-f", "null", "-"], check=guard, timeout=120)
        playback = private / "playback.mp4"
        if playback.is_symlink():
            raise VideoError("video_workspace_path_rejected")
        info = transcode_240p(source, playback, check=guard)
        run_media([ffmpeg, "-v", "error", "-xerror", "-nostdin", "-protocol_whitelist", "file,pipe",
                   "-i", str(playback), "-f", "null", "-"], check=guard, timeout=120)
        guard()
        os.replace(playback, output)
        state.update(status="completed", output_sha256=digest_file(output), media=info)
        save()
    guard()
    progress("video_ready", "Video verified; ready for encrypted delivery", "completed")
    info = state["media"]
    return (plan["summary"] + "\n\nProgrammatically rendered animation (not native video-model generation). "
            + f"{info['duration']:.1f}s, {info['width']}x{info['height']}, {info['size_bytes']} bytes. "
            + "Visual review and full decode passed; phone delivery is tracked separately.\n"
            + "[video-240p.mp4](outputs/video-240p.mp4)")
