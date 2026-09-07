"""Bounded, cancellable 240p derivatives for Agent-produced video only."""
from __future__ import annotations

import json
import os
import subprocess
import time
import uuid
import html
from fractions import Fraction
from pathlib import Path
from typing import Callable

SHORT_EDGE = 240
VIDEO_KBPS = 240
AUDIO_KBPS = 24
MAX_SOURCE_BYTES = 64 * 1024 * 1024


class VideoError(RuntimeError):
    pass


def media_executable(name: str) -> str:
    from desktop_runtime import desktop_runtime_manager
    resolved = desktop_runtime_manager().resolve_executable(name)
    if not resolved:
        raise VideoError(f"video_runtime_unavailable: {name}")
    return resolved


def run_media(command: list[str], *, check: Callable[[], None], timeout: float, cwd=None) -> bytes:
    check()
    process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, cwd=cwd, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    deadline = time.monotonic() + timeout
    try:
        while True:
            check()
            if time.monotonic() >= deadline:
                raise VideoError("video_transcode_timeout")
            try:
                out, _err = process.communicate(timeout=0.2)
                if process.returncode:
                    raise VideoError(f"video_media_command_failed: exit={process.returncode}")
                return out
            except subprocess.TimeoutExpired:
                continue
    finally:
        if process.poll() is None:
            process.kill()
        process.communicate()


def inspect_video(path: Path, *, check: Callable[[], None] = lambda: None) -> dict:
    if not path.is_file() or not 0 < path.stat().st_size <= MAX_SOURCE_BYTES:
        raise VideoError("video_source_size_invalid")
    raw = run_media([media_executable("ffprobe"), "-v", "error", "-protocol_whitelist", "file,pipe",
                     "-show_entries", "stream=codec_type,codec_name,width,height,pix_fmt,avg_frame_rate:format=duration,size",
                     "-of", "json", str(path)], check=check, timeout=30)
    try:
        value = json.loads(raw)
        video = next(s for s in value["streams"] if s["codec_type"] == "video")
        duration = float(value["format"]["duration"])
        width, height = int(video["width"]), int(video["height"])
        if not 0 < duration <= 600 or not 1 < width <= 8192 or not 1 < height <= 8192:
            raise ValueError("bounds")
        return {"width": width, "height": height, "duration": duration,
                "fps": float(Fraction(video["avg_frame_rate"])), "codec": video["codec_name"],
                "pixel_format": video.get("pix_fmt"), "size_bytes": path.stat().st_size,
                "has_audio": any(s["codec_type"] == "audio" for s in value["streams"])}
    except (ValueError, KeyError, StopIteration, ZeroDivisionError, TypeError) as exc:
        raise VideoError("video_probe_invalid") from exc


def transcode_240p(source: Path, destination: Path, *, check: Callable[[], None] = lambda: None, subtitles=None) -> dict:
    source, destination = source.resolve(), destination.resolve()
    if source.resolve() == destination.resolve():
        raise VideoError("video_source_must_be_preserved")
    original = inspect_video(source, check=check)
    ffmpeg = media_executable("ffmpeg")
    fps = min(24, original["fps"])
    if fps <= 0:
        raise VideoError("video_frame_rate_invalid")
    destination.parent.mkdir(parents=True, exist_ok=True)
    staged = destination.with_suffix(".partial.mp4")
    if staged.is_symlink():
        raise VideoError("video_staging_path_rejected")
    scale = "scale=w='if(gte(iw,ih),-2,trunc(min(240,iw)/2)*2)':h='if(gte(iw,ih),trunc(min(240,ih)/2)*2,-2)',setsar=1"
    captions = source.parent / ("captions-" + uuid.uuid4().hex + ".srt")
    if subtitles:
        def timestamp(seconds):
            ms = round(float(seconds) * 1000)
            return f"{ms // 3600000:02}:{ms // 60000 % 60:02}:{ms // 1000 % 60:02},{ms % 1000:03}"
        lines = []
        for index, caption in enumerate(subtitles, 1):
            text = html.escape(str(caption["text"]).replace("\r", " ").replace("\n", " ").replace("{", "(").replace("}", ")"))
            lines.append(f"{index}\n{timestamp(caption['start'])} --> {timestamp(caption['end'])}\n{text}\n")
        captions.write_text("\n".join(lines), encoding="utf-8")
        scale += f",subtitles={captions.name}"
    command = [ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
               "-protocol_whitelist", "file,pipe", "-i", str(source), "-map", "0:v:0", "-map", "0:a:0?",
               "-vf", scale, "-r", str(fps), "-c:v", "libx264", "-preset", "veryfast", "-threads", "2",
               "-pix_fmt", "yuv420p", "-g", str(max(1, round(fps * 2))), "-b:v", f"{VIDEO_KBPS}k", "-maxrate", f"{VIDEO_KBPS}k",
               "-bufsize", f"{VIDEO_KBPS * 2}k", "-c:a", "aac", "-b:a", f"{AUDIO_KBPS}k",
               "-ac", "1", "-ar", "24000", "-map_metadata", "-1", "-movflags", "+faststart", str(staged)]
    try:
        run_media(command, check=check, timeout=min(600, max(60, original["duration"] * 5)), cwd=source.parent)
        result = inspect_video(staged, check=check)
        if (min(result["width"], result["height"]) > SHORT_EDGE or result["fps"] > 24.01
                or result["codec"] != "h264" or result["pixel_format"] != "yuv420p"
                or abs(result["duration"] - original["duration"]) > 0.5
                or result["has_audio"] != original["has_audio"]):
            raise VideoError("video_derivative_validation_failed")
        check()
        os.replace(staged, destination)
        return result
    finally:
        staged.unlink(missing_ok=True)
        captions.unlink(missing_ok=True)
