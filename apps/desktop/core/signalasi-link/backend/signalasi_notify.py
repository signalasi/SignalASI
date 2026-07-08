"""Send a local Agent-initiated message to the paired SignalASI phone."""
from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request

from push_auth import agent_push_token


def main() -> int:
    parser = argparse.ArgumentParser(
        prog="signalasi notify",
        description="Push a message from a local Agent or automation to SignalASI mobile.",
    )
    parser.add_argument("contact_id", help="SignalASI contact id, for example codex or research-agent")
    parser.add_argument("message", nargs="+", help="Message text to send")
    parser.add_argument("--source", default="signalasi-notify", help="Source label stored with the push message")
    parser.add_argument("--url", default="http://127.0.0.1:8765/api/agent/push", help="SignalASI Desktop push API URL")
    args = parser.parse_args()

    payload = json.dumps({
        "contact_id": args.contact_id,
        "content": " ".join(args.message),
        "source": args.source,
    }).encode("utf-8")
    request = urllib.request.Request(
        args.url,
        data=payload,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-SignalASI-Token": agent_push_token(),
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            body = response.read().decode("utf-8", "replace")
            print(body)
            return 0 if 200 <= response.status < 300 else 1
    except urllib.error.HTTPError as exc:
        print(exc.read().decode("utf-8", "replace") or str(exc), file=sys.stderr)
        return 1
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
