# SignalASI Link Protocol

This directory contains protocol specifications, schemas, and compatibility notes shared by all SignalASI clients.

Additional protocol contracts:

- [Desktop Native Tool Execution v1](Desktop-Native-Tool-Execution-v1.md)
- [Phone-Native Tool Session v1](Phone-Native-Tool-Session-v1.md)
- [Super Agent Rich Output](Super-Agent-Rich-Output.md)

## Agent Conversation Identity

Agent messages use three independent identifiers:

- `conversation_id` identifies the durable user-visible Agent session.
- `turn_id` identifies one user request and its resulting assistant response.
- `task_id` identifies one execution task inside a turn.

The Android client includes `conversation_id` and `turn_id` inside the encrypted
SignalASI Link payload. Desktop connectors bind task lifecycle events to the
originating turn. Codex App Server integrations reuse one Codex thread for each
SignalASI conversation while other connectors receive a bounded conversation
summary and recent dialogue.

Task lifecycle events may include bounded `output_files` metadata using paths
relative to the isolated task workspace. Provider-reported `input_tokens`,
`output_tokens`, and `cost_micros` are retained when available.

Deleting a conversation sends `type: agent_conversation_delete` with its
`conversation_id` and known `task_ids`. The paired Desktop removes matching task
records, Codex thread mappings, and temporary/log directories while preserving
saved output files to prevent accidental data loss.
