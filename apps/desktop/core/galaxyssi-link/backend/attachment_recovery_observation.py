"""Return failed attachment restoration to the model as an explicit observation."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Callable

from attachment_request_broker import AttachmentTransferFailed
from blob_failures import failure_observation
from model_recovery import ModelRecoveryDecision, recovery_follow_up


@dataclass(frozen=True)
class AttachmentRecoveryObservation:
    paths: tuple[str, ...] = ()
    error_code: str = ""

    def follow_up(self, decision: ModelRecoveryDecision) -> str:
        if self.error_code:
            return failure_observation(self.error_code)
        return recovery_follow_up(decision, list(self.paths))


def observe_attachment_recovery(restore: Callable[[], list[str]], *,
                                on_failure: Callable[[str], None]) -> AttachmentRecoveryObservation:
    try:
        return AttachmentRecoveryObservation(paths=tuple(restore()))
    except AttachmentTransferFailed as error:
        on_failure(error.code)
        return AttachmentRecoveryObservation(error_code=error.code)
