"""MCP stdio wrapper for SignalASI Custom Agent.

Example command in SignalASI Desktop:

    python mcp_agent_wrapper.py --server "python your_mcp_server.py" --tool echo -

For Python MCP servers, this avoids shell quoting:

    python mcp_agent_wrapper.py --server-python your_mcp_server.py --tool echo -

The wrapper reads the phone message from stdin when the command ends with ``-``,
connects to an MCP stdio server, calls the selected tool, and prints the tool
result back to SignalASI.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from typing import Any, Callable


DEFAULT_TIMEOUT = 20.0


class McpError(RuntimeError):
    pass


def read_prompt(parts: list[str]) -> str:
    if not parts or parts[-1] == "-":
        return sys.stdin.read().strip()
    return " ".join(parts).strip()


def write_frame(stream, payload: dict[str, Any]) -> None:
    body = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    stream.write(f"Content-Length: {len(body)}\r\n\r\n".encode("ascii") + body)
    stream.flush()


def read_frame(stream, deadline: float) -> dict[str, Any]:
    headers: dict[str, str] = {}
    while True:
        if time.monotonic() > deadline:
            raise McpError("Timed out waiting for MCP response headers")
        line = stream.readline()
        if not line:
            raise McpError("MCP server closed stdout")
        line_text = line.decode("ascii", errors="replace").strip()
        if not line_text:
            break
        if ":" in line_text:
            key, value = line_text.split(":", 1)
            headers[key.lower()] = value.strip()

    try:
        length = int(headers["content-length"])
    except Exception as exc:
        raise McpError(f"Invalid MCP response headers: {headers}") from exc

    body = stream.read(length)
    if len(body) != length:
        raise McpError("MCP server closed stdout during response body")
    return json.loads(body.decode("utf-8"))


def request(process: subprocess.Popen, method: str, params: dict[str, Any] | None, request_id: int, deadline: float) -> dict[str, Any]:
    write_frame(process.stdin, {"jsonrpc": "2.0", "id": request_id, "method": method, "params": params or {}})
    while True:
        message = read_frame(process.stdout, deadline)
        if message.get("id") != request_id:
            continue
        if "error" in message:
            raise McpError(json.dumps(message["error"], ensure_ascii=False))
        return message.get("result") or {}


def notify(process: subprocess.Popen, method: str, params: dict[str, Any] | None = None) -> None:
    write_frame(process.stdin, {"jsonrpc": "2.0", "method": method, "params": params or {}})


def tool_arguments(tool: dict[str, Any], prompt: str, arg_json: str | None) -> dict[str, Any]:
    if arg_json:
        data = json.loads(arg_json)
        if not isinstance(data, dict):
            raise McpError("--arg-json must decode to an object")
        return data

    schema = tool.get("inputSchema") or {}
    properties = schema.get("properties") if isinstance(schema, dict) else None
    if isinstance(properties, dict) and properties:
        preferred = ["prompt", "input", "text", "query", "message"]
        for key in preferred:
            if key in properties:
                return {key: prompt}
        first_key = next(iter(properties))
        return {first_key: prompt}
    return {"prompt": prompt}


def result_text(result: dict[str, Any]) -> str:
    content = result.get("content")
    if isinstance(content, list):
        parts = []
        for item in content:
            if isinstance(item, dict) and item.get("type") == "text":
                parts.append(str(item.get("text", "")))
            elif isinstance(item, dict):
                parts.append(json.dumps(item, ensure_ascii=False))
            else:
                parts.append(str(item))
        text = "\n".join(part for part in parts if part).strip()
        if text:
            return text
    if "structuredContent" in result:
        return json.dumps(result["structuredContent"], ensure_ascii=False)
    return json.dumps(result, ensure_ascii=False)


def server_command(args: argparse.Namespace) -> tuple[Any, bool]:
    if args.server_python:
        return [sys.executable, args.server_python], False
    command = args.server or os.environ.get("SIGNALASI_MCP_SERVER_CMD", "").strip()
    if not command:
        return None, False
    return command, True


def _open_mcp(
    args: argparse.Namespace,
    on_process: Callable[[subprocess.Popen], None] | None = None,
) -> tuple[subprocess.Popen, list[dict[str, Any]], float]:
    command, use_shell = server_command(args)
    if not command:
        raise McpError("No MCP server configured")

    deadline = time.monotonic() + float(args.timeout)
    process = subprocess.Popen(
        command,
        shell=use_shell,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=False,
    )
    try:
        if on_process is not None:
            on_process(process)
        request(
            process,
            "initialize",
            {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {"name": "SignalASI Desktop", "version": "0.1.0"},
            },
            1,
            deadline,
        )
        notify(process, "notifications/initialized")
        listed = request(process, "tools/list", {}, 2, deadline)
        tools = listed.get("tools") or []
        if not tools:
            raise McpError("MCP server returned no tools")
        return process, tools, deadline
    except Exception:
        _close_mcp(process)
        raise


def _close_mcp(process: subprocess.Popen) -> None:
    try:
        process.terminate()
        process.wait(timeout=2)
    except Exception:
        process.kill()
        process.wait(timeout=2)
    finally:
        for stream in (process.stdin, process.stdout, process.stderr):
            if stream is not None:
                stream.close()


def list_mcp_tools(args: argparse.Namespace) -> list[dict[str, Any]]:
    process, tools, _deadline = _open_mcp(args)
    try:
        return tools
    finally:
        _close_mcp(process)


def call_mcp(
    args: argparse.Namespace,
    prompt: str,
    on_process: Callable[[subprocess.Popen], None] | None = None,
) -> str:
    command, _use_shell = server_command(args)
    if not command:
        return (
            "[MCP Agent] No MCP server configured. Set --server, --server-python, "
            "or SIGNALASI_MCP_SERVER_CMD. Prompt received: "
            f"{prompt}"
        )

    process, tools, deadline = _open_mcp(args, on_process=on_process)
    try:
        tool = next((item for item in tools if item.get("name") == args.tool), None) if args.tool else tools[0]
        if not tool:
            names = ", ".join(str(item.get("name")) for item in tools)
            raise McpError(f"MCP tool not found: {args.tool}. Available: {names}")
        called = request(
            process,
            "tools/call",
            {"name": tool["name"], "arguments": tool_arguments(tool, prompt, args.arg_json)},
            3,
            deadline,
        )
        return result_text(called)
    finally:
        _close_mcp(process)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Expose an MCP stdio server as a SignalASI Custom Agent")
    parser.add_argument("--server", help="MCP server command, for example: python server.py")
    parser.add_argument("--server-python", help="Run this Python MCP server script with the current Python")
    parser.add_argument("--tool", help="MCP tool name to call. Defaults to the first listed tool.")
    parser.add_argument("--arg-json", help="JSON object to pass as tool arguments instead of mapping prompt text")
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT)
    parser.add_argument("--stdio", action="store_true", help="Compatibility flag; SignalASI already uses stdio")
    parser.add_argument("prompt", nargs="*")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    prompt = read_prompt(args.prompt)
    try:
        print(call_mcp(args, prompt))
        return 0
    except Exception as exc:
        print(f"[MCP Agent] {exc}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
