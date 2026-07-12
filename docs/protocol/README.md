# SignalASI Link Protocol

This directory contains protocol specifications, schemas, and compatibility notes shared by all SignalASI clients.

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
