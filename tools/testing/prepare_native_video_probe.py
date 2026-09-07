"""Generate a clearly synthetic test clip and real artifact wire payloads, never a fake AI receipt."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import uuid
import tempfile
import argparse

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "apps/desktop/core/galaxyssi-link/backend"))

from artifact_delivery import prepare_artifacts, artifact_chunk_payloads
from task_workspace import task_workspace, task_artifacts
from video_transport import inspect_video, transcode_240p


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--video", type=Path, help="Use an existing programmatic video instead of testsrc2")
    args = parser.parse_args()
    os.environ["GALAXYSSI_WORKSPACE_ROOT"] = tempfile.mkdtemp(prefix="galaxyssi-native-video-probe-")
    task_id = "video-probe-" + uuid.uuid4().hex[:12]
    workspace = task_workspace(task_id, "synthetic-fixture")
    source = workspace / "temp/synthetic-source.mp4"
    output = workspace / "outputs/synthetic-240p.mp4"
    if args.video:
        shutil.copyfile(args.video, source)
    else:
        subprocess.run([shutil.which("ffmpeg") or "ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                    "testsrc2=size=1280x720:rate=30", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=24000",
                    "-t", "16", "-c:v", "libx264", "-preset", "ultrafast", "-threads", "2",
                    "-c:a", "aac", "-pix_fmt", "yuv420p", str(source)], check=True, timeout=90)
    info = inspect_video(source)
    if (args.video and info["codec"] == "h264" and info["pixel_format"] == "yuv420p"
            and min(info["width"], info["height"]) <= 240 and info["fps"] <= 24.01):
        # Exercise the exact published bytes, not a second re-encode of the output.
        shutil.copyfile(source, output)
    else:
        info = transcode_240p(source, output)
    artifact = prepare_artifacts(task_id, task_artifacts(task_id))[0]
    payloads = list(artifact_chunk_payloads(artifact))
    assert payloads
    assets = ROOT / "tools/testing/native-video-probe/assets"
    assets.mkdir(parents=True, exist_ok=True)
    (assets / "native-video-chunks.json").write_text(json.dumps(payloads), encoding="utf-8")
    report = {"task_id": task_id, "fixture": "provided programmatic video" if args.video else "synthetic test pattern", "media": info,
              "chunks": len(payloads), "sha256": artifact.sha256}
    (assets / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
