# Super Agent Rich Output Protocol

## Envelope

Rich output is carried inside the encrypted application payload. The transport never interprets block content.

```json
{
  "type": "text",
  "content": "Fallback plain-text result",
  "rich_output": {
    "version": 1,
    "blocks": []
  }
}
```

`content` is mandatory as an accessible fallback. `rich_output` is optional. Receivers must ignore unknown fields and render unsupported blocks using `fallback_text` when present.

## Common Block Fields

```json
{
  "id": "stable-block-id",
  "type": "table",
  "title": "Optional title",
  "text": "Optional text or fallback",
  "uri": "Optional artifact or media URI",
  "mime_type": "Optional MIME type",
  "language": "Optional code or content language",
  "provenance": {
    "resource_id": "codex",
    "tool_call_id": "call-id",
    "created_at": 1784000000000
  }
}
```

## Table Block

```json
{
  "id": "weather-table",
  "type": "table",
  "title": "Forecast",
  "columns": ["Time", "Condition", "Temperature"],
  "rows": [
    ["09:00", "Clear", "28 C"],
    ["12:00", "Cloudy", "31 C"]
  ]
}
```

## Code Block

```json
{
  "id": "patch-example",
  "type": "code",
  "language": "kotlin",
  "text": "fun main() = println(\"SignalASI\")"
}
```

## Media and Artifact Blocks

`image`, `video`, `audio`, `file`, `link`, and `citation` blocks use `uri`. Clients must allow only supported URI schemes, never autoplay remote media, never execute downloaded content, and require an explicit user action before leaving the app.

## Interactive Blocks

`actions`, `approval`, and `form` blocks carry bounded user interactions. Actions use controlled verbs instead of script code. Version 1 clients may handle `copy`, `open_uri`, `set_input`, `submit_prompt`, `approve_task`, and `reject_task`; unknown verbs remain inert. Approval decisions are accepted only when the block task identity matches a live local task.

`tool`, `diff`, and `chart` blocks represent tool activity, code changes, and structured visualization without requiring executable HTML.

## Limits

- Maximum blocks per result: 100.
- Maximum serialized rich output: 256 KiB.
- Maximum table columns: 24.
- Maximum table rows: 500.
- Maximum text per block: 32,000 characters.
- Maximum inline preview download: 12 MiB.
- Unknown block types must not fail the conversation.

## Streaming

Future streaming events use `rich_output_patch` with `conversation_id`, `turn_id`, `block_id`, and a monotonic `revision`. A receiver applies only newer revisions and persists the final block document with the transcript entry.
