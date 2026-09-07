"""Read-only, route-scoped recovery observations. Never starts or resumes a task."""

from __future__ import annotations

from agent_recovery_timing import recovery_timing

MAX_ITEMS = 32
IDENTITY_FIELDS = (
    "client_route_id", "conversation_id", "task_id", "turn_id", "contact_id",
    "source_message_id", "agent_id",
)
TASK_FIELDS = (
    "client_route_id", "client_conversation_id", "task_id", "client_turn_id",
    "contact_id", "source_message_id", "agent_id",
)
STATUSES = frozenset({
    "accepted", "queued", "starting", "running", "recovering", "waiting_input",
    "waiting_approval", "pausing", "paused", "takeover", "interrupted",
    "completed", "failed", "timed_out", "cancelled",
})


def recovery_query(payload: dict, *, client_route_id: str, manager) -> dict | None:
    request_id = payload.get("request_id")
    items = payload.get("items")
    if (not isinstance(request_id, str) or not 1 <= len(request_id) <= 128
            or payload.get("client_route_id") != client_route_id
            or not client_route_id or not isinstance(items, list)
            or not 1 <= len(items) <= MAX_ITEMS):
        return None
    # Validate the whole batch before any lookup; malformed requests cannot widen scope.
    if any(not isinstance(item, dict) or any(
        not isinstance(item.get(key), str) or not 1 <= len(item[key]) <= 200
        for key in IDENTITY_FIELDS
    ) or item["client_route_id"] != client_route_id for item in items):
        return None
    observations = []
    for item in items:
        observation = {key: item[key] for key in IDENTITY_FIELDS}
        observation["status"] = "unavailable"
        with recovery_timing(item, "lookup", request_id=request_id) as measurement:
            task = manager.recovery_snapshot(
                item["task_id"], client_route_id=client_route_id,
                conversation_id=item["conversation_id"], turn_id=item["turn_id"],
            )
            if task is not None and all(
                str(task.get(field, "")) == item[key]
                for key, field in zip(IDENTITY_FIELDS, TASK_FIELDS)
            ):
                status = str(task.get("status") or "")
                if status in STATUSES:
                    observation.update(
                        status=status,
                        remote_run_id=str(task.get("run_id") or f"task:{item['task_id']}"),
                        status_sequence=max(0, int(task.get("status_seq") or 0)),
                        execution_generation=max(1, int(task.get("execution_generation") or 1)),
                    )
                    measurement.completed = True
        observations.append(observation)
    return {
        "type": "agent_task_recovery_result", "request_id": request_id,
        "client_route_id": client_route_id, "items": observations,
    }
