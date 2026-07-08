"""Local authorization token for Agent-to-SignalASI push messages."""
from __future__ import annotations

import secrets
from pathlib import Path


TOKEN_PATH = Path(__file__).with_name("signalasi_push_token.txt")


def agent_push_token() -> str:
    token = _read_token()
    if token:
        return token
    token = secrets.token_urlsafe(32)
    TOKEN_PATH.write_text(token, encoding="utf-8")
    return token


def verify_agent_push_token(value: str) -> bool:
    token = agent_push_token()
    return bool(value) and secrets.compare_digest(value.strip(), token)


def _read_token() -> str:
    try:
        return TOKEN_PATH.read_text(encoding="utf-8").strip()
    except FileNotFoundError:
        return ""
