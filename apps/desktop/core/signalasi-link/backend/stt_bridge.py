"""Local speech-to-text bridge for SignalASI phone voice messages."""
from __future__ import annotations

import os
import threading
from pathlib import Path
from typing import Any

try:
    from faster_whisper import WhisperModel
except ImportError:
    WhisperModel = None


MODEL_NAME = os.environ.get("HERMESCHAT_WHISPER_MODEL", "medium")
DEVICE = os.environ.get("HERMESCHAT_WHISPER_DEVICE", "cpu")
COMPUTE_TYPE = os.environ.get("HERMESCHAT_WHISPER_COMPUTE_TYPE", "int8")

_model: Any | None = None
_lock = threading.Lock()


def _get_model() -> Any:
    global _model
    if WhisperModel is None:
        raise RuntimeError("faster-whisper is not installed. Install faster-whisper to enable voice transcription.")
    if _model is None:
        with _lock:
            if _model is None:
                _model = WhisperModel(MODEL_NAME, device=DEVICE, compute_type=COMPUTE_TYPE)
    return _model


def transcribe_audio(path: str | Path, language: str | None = None) -> str:
    audio_path = Path(path)
    if not audio_path.is_file():
        raise FileNotFoundError(f"audio file not found: {audio_path}")

    segments, _info = _get_model().transcribe(
        str(audio_path),
        language=language or None,
        vad_filter=True,
        beam_size=5,
    )
    text = "".join(segment.text for segment in segments).strip()
    return text
