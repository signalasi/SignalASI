"""Opt-in live coding-Agent video test in a separate temporary task workspace."""
import argparse
from dataclasses import replace
import json
import os
from pathlib import Path
import sys
import subprocess
import tempfile
import time
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "apps/desktop/core/galaxyssi-link/backend"))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--codex", required=True)
    parser.add_argument("--model", default="gpt-6-astra")
    parser.add_argument("--permission-check", action="store_true")
    parser.add_argument("--resume-workspace", type=Path)
    args = parser.parse_args()
    workspace = args.resume_workspace or Path(tempfile.mkdtemp(prefix="galaxyssi-programmatic-video-"))
    workspace = workspace.resolve()
    if (not workspace.is_dir() or not workspace.name.startswith("galaxyssi-programmatic-video-")
            or workspace.parent != Path(tempfile.gettempdir()).resolve()):
        raise ValueError("Probe workspace must be a probe-owned directory in the system temp directory")
    os.environ["GALAXYSSI_WORKSPACE_ROOT"] = str(workspace)
    os.environ["GALAXYSSI_STATE_DIR"] = str(workspace / "state")
    os.environ["GALAXYSSI_CONFIG_PATH"] = str(workspace / "state/agents.json")
    from agent_gateway import BASE_AGENTS, _ask_agent_sync_inner
    from programmatic_video_task import run_programmatic_video_task
    spec = replace(BASE_AGENTS["codex"], timeout=480, env_key=None,
                   command=[args.codex, "exec", "--ignore-user-config", "--skip-git-repo-check",
                            "--ephemeral", "--sandbox", "workspace-write", "--model", args.model, "-"])
    from agent_config import save_config
    save_config({"commands": {"codex": subprocess.list2cmdline(spec.command)}, "cli_runtime": {"enabled": False}})
    def invoke(stage, text, readonly, remaining):
        started = time.monotonic()
        print(json.dumps({"stage": stage, "readonly": readonly}), flush=True)
        original_popen = subprocess.Popen
        def capture(command, **kwargs):
            process = original_popen(command, **kwargs)
            if "codex" in str(command[0]).lower():
                (workspace / f"{stage}-command.json").write_text(json.dumps(command), encoding="utf-8")
                communicate = process.communicate
                def logged(**values):
                    if values.get("timeout") is None:
                        values["timeout"] = min(480, remaining)
                    stdout, stderr = communicate(**values)
                    (workspace / f"{stage}-stderr.log").write_bytes(stderr or b"")
                    return stdout, stderr
                process.communicate = logged
            return process
        with patch("agent_gateway.subprocess.Popen", side_effect=capture):
            reply = _ask_agent_sync_inner("codex", text, replace(spec, timeout=min(480, int(remaining))),
                                        task_id="chip-animation", restricted_workspace=readonly,
                                        plan_only=readonly, response_language="zh-CN", agent_model_id=args.model,
                                        codex_video_permissions="read-only" if readonly else "workspace-write")
        (workspace / f"{stage}-reply.txt").write_text(reply, encoding="utf-8")
        print(json.dumps({"stage_completed": stage, "seconds": round(time.monotonic() - started, 2)}), flush=True)
        return reply
    print(json.dumps({"workspace": str(workspace)}), flush=True)
    if args.permission_check:
        from task_workspace import task_workspace
        target = task_workspace("chip-animation", "codex") / "permission-probe.txt"
        from video_execution_permissions import prepare_render_directory
        prepare_render_directory(target.parent, target.parent)
        invoke("permission", f"Use your file tool to create {target} containing exactly writable. Use this absolute path, then read it back. Do not change any other files.", False, 120)
        if not target.is_file() or target.read_text().strip() != "writable":
            raise RuntimeError("Render workspace is still not writable; inspect permission-stderr.log")
        print(json.dumps({"permission_probe": "passed", "path": str(target)}), flush=True)
        return
    result = run_programmatic_video_task(task_id="chip-animation", agent_id="codex",
        prompt="\u8bf7\u751f\u6210\u4e00\u4e2a32\u79d2\u82af\u7247\u5de5\u4f5c\u539f\u7406\u52a8\u753b\u89c6\u9891\uff0c\u4e2d\u6587\u5b57\u5e55\uff0c\u4e0d\u9700\u8981\u914d\u97f3\uff0c\u8bb2\u89e3\u6676\u4f53\u7ba1\u3001\u903b\u8f91\u95e8\u548cCPU\u6307\u4ee4\u6267\u884c\u3002",
        invoke=invoke, check=lambda: None, planner_model=args.model, timeout=1000,
        progress=lambda *p: print(json.dumps({"progress": p}), flush=True))
    print(json.dumps({"result": result, "output": str(workspace / "tasks/chip-animation/outputs/video-240p.mp4")}), flush=True)


if __name__ == "__main__":
    main()
