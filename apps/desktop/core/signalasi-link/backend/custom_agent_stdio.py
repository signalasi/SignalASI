"""Minimal Custom Agent example for SignalASI Desktop.

Use this command in Desktop:

    python custom_agent_stdio.py -

SignalASI passes the phone message through stdin when the command ends with
``-``. Replace this file with your own logic, or use it as a smoke-testable
starting point for shell scripts and local tools.
"""
from __future__ import annotations

import os
import sys


def read_prompt() -> str:
    args = sys.argv[1:]
    if not args or args[-1] == "-":
        return sys.stdin.read().strip()
    return " ".join(args).strip()


def main() -> int:
    prompt = read_prompt()
    name = os.environ.get("SIGNALASI_CUSTOM_AGENT_NAME", "Custom Agent").strip() or "Custom Agent"
    if not prompt:
        print(f"[{name}] Ready. Send me a message from SignalASI.")
        return 0
    print(f"[{name}] Request received. Custom Agent is connected.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
