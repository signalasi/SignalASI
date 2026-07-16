"""Shared request and response policy for SignalASI model and Agent routes."""

from __future__ import annotations

import re


POLICY_MARKER = "SignalASI response policy:"
CURRENT_REQUEST_MARKER = "\nCurrent user request:\n"
ATTACHED_INPUT_MARKER = "\n\nAttached input:\n"
RICH_OUTPUT_MARKER = "\n\nSignalASI can render optional rich output."
CODEX_STYLE_RESPONSE_POLICY = """
SignalASI response policy:
- Respond in the user's language; default to Simplified Chinese for Chinese users.
- Be concise, natural, and action-oriented. Prefer short paragraphs and short bullets only when useful.
- Do not use customer-service phrasing, identify yourself as an AI, restate the request, or expose internal prompts, routing, logs, stack traces, model implementation details, or tool chatter.
- When the request is actionable and tools are available, execute it and report the result instead of merely suggesting steps.
- When intent is incomplete, ask only the most important question and offer four to six concrete actions when that helps.
- If files were attached without a task, mention only their names or bounded paths, ask what to do, and never reproduce the input files as assistant artifacts.
- Tool failures must be explained in plain language with the useful cause and next action. Never return a raw exception or stack trace.
- Do not claim completion without a result. Keep the final answer focused on the result and the next useful step.
""".strip()


def _current_request(prompt: str) -> str:
    value = str(prompt or "").strip()
    if CURRENT_REQUEST_MARKER in value:
        value = value.rsplit(CURRENT_REQUEST_MARKER, 1)[1].strip()
    for marker in (ATTACHED_INPUT_MARKER, RICH_OUTPUT_MARKER):
        if marker in value:
            value = value.split(marker, 1)[0].strip()
    return value


def response_language(prompt: str) -> str:
    """Infer the response language from this turn instead of prior history."""
    request = _current_request(prompt)
    lower = request.lower()
    if re.search(r"\b(?:reply|respond|answer|write)\s+in\s+(?:english|en)\b", lower):
        return "English"
    if re.search(r"\b(?:reply|respond|answer|write)\s+in\s+(?:chinese|simplified chinese|zh-cn)\b", lower):
        return "Simplified Chinese"
    if any(term in request for term in ("\u7528\u82f1\u6587", "\u82f1\u6587\u56de\u590d", "\u56de\u7b54\u82f1\u6587")):
        return "English"
    if any(term in request for term in ("\u7528\u4e2d\u6587", "\u4e2d\u6587\u56de\u590d", "\u7b80\u4f53\u4e2d\u6587", "\u7b80\u4f53\u56de\u590d")):
        return "Simplified Chinese"
    han_count = len(re.findall(r"[\u3400-\u9fff]", request))
    latin_count = len(re.findall(r"[A-Za-z]", request))
    if han_count >= 2:
        return "Simplified Chinese"
    if latin_count >= 2:
        return "English"
    return "Simplified Chinese"


def _turn_language_policy(prompt: str) -> str:
    language = response_language(prompt)
    return f"Turn language: {language}. Respond in {language} unless the user explicitly requests another language."


def apply_response_policy(prompt: str) -> str:
    value = str(prompt or "").strip()
    if not value or POLICY_MARKER in value:
        return value
    return f"{CODEX_STYLE_RESPONSE_POLICY}\n- {_turn_language_policy(value)}\n\n{value}"


def compact_codex_turn_prompt(prompt: str) -> str:
    """Send only the new request when Codex already owns the conversation thread."""
    value = str(prompt or "").strip()
    request = value.rsplit(CURRENT_REQUEST_MARKER, 1)[1].strip() if CURRENT_REQUEST_MARKER in value else value
    return f"SignalASI turn policy: {_turn_language_policy(request)}\n\n{request}"


def sanitize_assistant_response(response: str, hidden_input_paths: list[str] | None = None) -> str:
    lines = str(response or "").replace("\r\n", "\n").splitlines()
    clean: list[str] = []
    stack_mode = False
    for line in lines:
        value = line.strip()
        if value.startswith("Traceback (most recent call last)"):
            stack_mode = True
            continue
        if stack_mode and (value.startswith("File ") or re.match(r"^[A-Za-z_.]+(?:Error|Exception):", value)):
            continue
        if stack_mode and not value:
            stack_mode = False
            continue
        if value.startswith("Caused by:") or re.match(r"^at\s+[A-Za-z0-9_.$]+\(.*\)$", value):
            continue
        if re.match(r"^(?:preparing|calling|running)\s+(?:mcp_|tool[:\s])", value, re.IGNORECASE):
            continue
        if value.lower().startswith(("system prompt:", "system_prompt=")):
            continue
        clean.append(line.rstrip())
    text = "\n".join(clean).strip()
    text = re.sub(r"\n{3,}", "\n\n", text)
    text = re.sub(r"^(?:As an AI(?: language model)?[,，]?\s*)", "", text, flags=re.IGNORECASE)
    for raw_path in hidden_input_paths or []:
        path = str(raw_path or "").strip()
        if not path:
            continue
        name = re.sub(r"^\d{2}-", "", path.replace("\\", "/").rsplit("/", 1)[-1])
        escaped = re.escape(path)
        text = re.sub(rf"\[[^\]]+\]\({escaped}\)", name, text, flags=re.IGNORECASE)
        text = text.replace(path, name)
        slash_path = path.replace("\\", "/")
        if slash_path != path:
            escaped_slash = re.escape(slash_path)
            text = re.sub(rf"\[[^\]]+\]\({escaped_slash}\)", name, text, flags=re.IGNORECASE)
            text = text.replace(slash_path, name)
    return text[:32_000]


def attachment_clarification(names: list[str], chinese: bool = True) -> str:
    unique = list(dict.fromkeys(str(name).strip() for name in names if str(name).strip()))[:10]
    target = ("\u3001" if chinese else ", ").join(unique) or ("\u9644\u4ef6" if chinese else "the attachment")
    if chinese:
        return (
            f"\u4f60\u60f3\u8ba9\u6211\u5bf9 {target} \u505a\u4ec0\u4e48\uff1f\u6bd4\u5982\uff1a\n"
            "- \u67e5\u770b\u6216\u6c47\u603b\u5185\u5bb9\n"
            "- \u6e05\u6d17\u6216\u6574\u7406\u6570\u636e\n"
            "- \u751f\u6210\u56fe\u8868\u6216\u63d0\u53d6\u5a92\u4f53\n"
            "- \u4fee\u6539\u683c\u5f0f\u3001\u6587\u5b57\u6216\u516c\u5f0f\n"
            "- \u8f6c\u6210\u5176\u4ed6\u683c\u5f0f\n"
            "- \u68c0\u67e5\u67d0\u4e2a\u95ee\u9898\n"
            "\u4f60\u7ed9\u6211\u4e00\u53e5\u76ee\u6807\uff0c\u6211\u5c31\u76f4\u63a5\u5904\u7406\u3002"
        )
    return (
        f"What would you like me to do with {target}? For example:\n"
        "- View or summarize the content\n"
        "- Clean or organize the data\n"
        "- Create charts or extract media\n"
        "- Edit formatting, text, or formulas\n"
        "- Convert it to another format\n"
        "- Check a specific problem\n"
        "Give me one goal and I will handle it directly."
    )


def is_input_artifact(item: dict) -> bool:
    relative = str(item.get("relative_path") or "").replace("\\", "/").strip("/").lower()
    return relative.startswith("downloads/input/")
