"""Trusted workflow skills for the SignalASI Desktop super agent."""
from __future__ import annotations

import json
import os
import re
import threading
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any


SKILL_ID = re.compile(r"[a-z0-9][a-z0-9._-]{1,63}\Z")


@dataclass(frozen=True)
class DesktopSkill:
    id: str
    name: str
    description: str
    triggers: tuple[str, ...]
    instructions: str
    enabled: bool = True
    source: str = "builtin"

    def public(self, include_instructions: bool = False) -> dict[str, Any]:
        value = asdict(self)
        value["triggers"] = list(self.triggers)
        if not include_instructions:
            value.pop("instructions", None)
        return value


BUILTIN_SKILLS = (
    DesktopSkill(
        "signalasi.code-work",
        "Code and project work",
        "Inspect, change, run, and verify software projects with concise artifact reporting.",
        ("code", "project", "repository", "build", "compile", "bug", "test", "\u4ee3\u7801", "\u9879\u76ee", "\u7f16\u8bd1", "\u4fee\u590d"),
        "Inspect the real project state first. Make the requested change, run proportionate verification, and report the changed files and verified result. Do not stop at a proposal when execution is possible.",
    ),
    DesktopSkill(
        "signalasi.file-work",
        "File analysis",
        "Read and transform attached documents without repeating the attachment back to the user.",
        ("file", "document", "xlsx", "docx", "pdf", "csv", "attachment", "\u6587\u4ef6", "\u9644\u4ef6", "\u8868\u683c", "\u6587\u6863"),
        "Use attached files directly. If the requested operation is clear, execute it. If no operation is specified, ask one short question with several useful options. Never duplicate an existing user attachment card in the response.",
    ),
    DesktopSkill(
        "signalasi.research",
        "Web research",
        "Find fresh evidence, compare sources, and return a concise cited synthesis.",
        ("research", "latest", "news", "weather", "search", "compare", "\u7814\u7a76", "\u6700\u65b0", "\u65b0\u95fb", "\u5929\u6c14", "\u641c\u7d22"),
        "For time-sensitive claims, retrieve current evidence before answering. Separate observed facts from inference, prefer primary sources, and include direct source links without exposing internal tool logs.",
    ),
    DesktopSkill(
        "signalasi.computer-control",
        "Computer control",
        "Observe, operate, and verify local computer state through bounded native tools.",
        ("computer", "desktop", "process", "open app", "browser", "system", "\u7535\u8111", "\u684c\u9762", "\u8fdb\u7a0b", "\u6253\u5f00\u5e94\u7528", "\u6d4f\u89c8\u5668"),
        "Use the narrowest local tool that can satisfy the request. Observe before changing state, bind high-impact actions to approval, verify the resulting host state, and present only the useful result.",
    ),
)


def _state_path() -> Path:
    configured = str(os.environ.get("SIGNALASI_STATE_DIR") or "").strip()
    root = Path(configured) if configured else Path(os.environ.get("APPDATA") or Path.home()) / "SignalASI"
    return root / "desktop-skills.json"


class DesktopSkillRegistry:
    def __init__(self, path: Path | None = None) -> None:
        self.path = Path(path) if path else _state_path()
        self._lock = threading.RLock()

    def list(self, include_instructions: bool = False) -> list[dict[str, Any]]:
        return [skill.public(include_instructions) for skill in self._all()]

    def match(self, prompt: str, limit: int = 2) -> list[DesktopSkill]:
        normalized = re.sub(r"\s+", " ", str(prompt or "")).casefold()
        ranked: list[tuple[int, DesktopSkill]] = []
        for skill in self._all():
            if not skill.enabled:
                continue
            score = sum(1 for trigger in skill.triggers if trigger.casefold() in normalized)
            if score:
                ranked.append((score, skill))
        return [skill for _score, skill in sorted(ranked, key=lambda item: (-item[0], item[1].id))[:max(1, min(limit, 4))]]

    def compile(self, prompt: str, limit: int = 2) -> tuple[str, list[DesktopSkill]]:
        skills = self.match(prompt, limit)
        text = "\n\n".join(f"Skill: {skill.name}\n{skill.instructions}" for skill in skills)
        return text[:8_000], skills

    def upsert(self, value: dict[str, Any]) -> dict[str, Any]:
        skill_id = str(value.get("id") or "").strip().casefold()
        if not SKILL_ID.fullmatch(skill_id) or skill_id.startswith("signalasi."):
            raise ValueError("Custom skill id must be valid and cannot use the signalasi namespace")
        name = str(value.get("name") or "").strip()[:80]
        description = str(value.get("description") or "").strip()[:400]
        instructions = str(value.get("instructions") or "").strip()[:12_000]
        triggers = tuple(dict.fromkeys(str(item).strip()[:80] for item in list(value.get("triggers") or []) if str(item).strip()))[:32]
        if not name or not instructions or not triggers:
            raise ValueError("Custom skill requires a name, instructions, and at least one trigger")
        skill = DesktopSkill(skill_id, name, description, triggers, instructions, bool(value.get("enabled", True)), "user")
        with self._lock:
            rows = [item for item in self._load_user() if item.id != skill_id]
            rows.append(skill)
            self._save_user(rows)
        return skill.public(include_instructions=True)

    def set_enabled(self, skill_id: str, enabled: bool) -> dict[str, Any]:
        if skill_id.startswith("signalasi."):
            overrides = self._load_overrides()
            overrides[skill_id] = bool(enabled)
            self._save_overrides(overrides)
            found = next((item for item in self._all() if item.id == skill_id), None)
            if not found:
                raise KeyError(skill_id)
            return found.public()
        with self._lock:
            rows = self._load_user()
            found = next((item for item in rows if item.id == skill_id), None)
            if not found:
                raise KeyError(skill_id)
            updated = DesktopSkill(found.id, found.name, found.description, found.triggers, found.instructions, bool(enabled), found.source)
            self._save_user([updated if item.id == skill_id else item for item in rows])
            return updated.public()

    def delete(self, skill_id: str) -> bool:
        if str(skill_id).startswith("signalasi."):
            return False
        with self._lock:
            rows = self._load_user()
            updated = [item for item in rows if item.id != skill_id]
            if len(updated) == len(rows):
                return False
            self._save_user(updated)
            return True

    def _all(self) -> list[DesktopSkill]:
        overrides = self._load_overrides()
        builtins = [
            DesktopSkill(item.id, item.name, item.description, item.triggers, item.instructions, overrides.get(item.id, item.enabled), item.source)
            for item in BUILTIN_SKILLS
        ]
        return builtins + self._load_user()

    def _load_user(self) -> list[DesktopSkill]:
        data = self._load_data().get("skills") or []
        result: list[DesktopSkill] = []
        for item in data:
            try:
                result.append(DesktopSkill(
                    str(item["id"]), str(item["name"]), str(item.get("description") or ""),
                    tuple(str(value) for value in item.get("triggers") or []), str(item["instructions"]),
                    bool(item.get("enabled", True)), "user",
                ))
            except Exception:
                continue
        return result

    def _load_overrides(self) -> dict[str, bool]:
        return {str(key): bool(value) for key, value in dict(self._load_data().get("builtin_enabled") or {}).items()}

    def _load_data(self) -> dict[str, Any]:
        if not self.path.exists():
            return {}
        try:
            return json.loads(self.path.read_text(encoding="utf-8-sig"))
        except Exception:
            return {}

    def _save_user(self, skills: list[DesktopSkill]) -> None:
        data = self._load_data()
        data["skills"] = [skill.public(include_instructions=True) for skill in skills]
        self._write(data)

    def _save_overrides(self, overrides: dict[str, bool]) -> None:
        data = self._load_data()
        data["builtin_enabled"] = overrides
        self._write(data)

    def _write(self, data: dict[str, Any]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(self.path.suffix + ".tmp")
        temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        temporary.replace(self.path)


_SKILLS: DesktopSkillRegistry | None = None
_SKILLS_LOCK = threading.Lock()


def desktop_skill_registry() -> DesktopSkillRegistry:
    global _SKILLS
    with _SKILLS_LOCK:
        if _SKILLS is None:
            _SKILLS = DesktopSkillRegistry()
        return _SKILLS
