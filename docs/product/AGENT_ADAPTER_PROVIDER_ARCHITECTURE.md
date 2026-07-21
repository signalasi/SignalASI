# Agent Adapter And Provider Architecture

SignalASI treats every external Agent, model runtime, host, and device connector as an independently identifiable runtime behind one stable control-plane contract. Connector-specific processes remain independently installable and upgradeable; the Android host owns routing, health, permissions, idempotency, recovery, and user-visible Run state.

## Production Discovery Path

```text
Desktop connector diagnostics
  -> capability_manifest.connector_agents
  -> Android encrypted contact metadata
  -> AppStoreAgentConnectorRegistry
  -> AgentRegistration
  -> ActionExecutorAgentProvider
  -> AgentAdapterDirectory
  -> dedicated production AgentAdapter
```

The descriptor preserves:

- stable Agent, installation, device, and provider identity;
- adapter type and independently-upgradeable declaration;
- capability and protocol feature sets;
- connection kind, trust, cost, latency, capacity, and heartbeat;
- transport failure domain and runtime failure domain.

## Dedicated Adapter Families

Android resolves production descriptors into named adapters for:

- Hermes;
- Codex;
- Claude Code;
- OpenClaw;
- cloud models;
- local models;
- Windows host tools;
- Android device tools;
- Home Assistant;
- custom Agents and connectors.

Desktop exposes corresponding named adapters and publishes their descriptors. Adding a connector requires a descriptor and an adapter-family mapping; it must not add a new top-level dispatch contract.

## Failure Isolation

Two failure scopes are intentionally separate:

- `failureDomain` represents shared transport or device reachability, such as one paired Desktop.
- `runtimeFailureDomain` represents one independently failing runtime on that installation, such as Codex or Hermes.

The encrypted provider-health ledger records success, failure kind, latency, consecutive failures, cooldown, and half-open probe lease per runtime scope. A Codex crash therefore does not open the Hermes circuit on the same Desktop and does not affect Codex on another Desktop.

Circuit behavior is bounded:

1. protocol, authorization, and process-crash failures open early;
2. transient timeout and execution failures require repeated evidence;
3. an open circuit rejects new work with a retry timestamp;
4. after cooldown, exactly one half-open probe is leased;
5. probe success closes only that runtime circuit;
6. probe failure reopens that circuit with bounded exponential cooldown.

Health state is local authority data. It is encrypted at rest, excluded from backup/export, and removed during full reset.

## Conformance Gates

The production gate verifies:

- every supported descriptor resolves to its dedicated adapter class;
- Provider enumeration reaches the production directory;
- runtime crashes are isolated across adapter families and installations;
- only one half-open probe is admitted;
- successful recovery closes the affected circuit;
- descriptor metadata survives encrypted Agent registry persistence.
