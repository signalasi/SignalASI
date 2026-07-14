# SignalASI Five-Year Super-Agent Architecture

## Product Direction

SignalASI should evolve from a mobile agent application into a user-owned personal intelligence runtime. The phone remains the policy authority and identity root while models, agents, tools, computers, private networks, and cloud services become replaceable execution resources.

The product must not depend on one model vendor, one operating system surface, or one transport. A goal should survive resource upgrades, device changes, network loss, process death, and protocol evolution without losing its identity, policy, evidence, or user ownership.

## Five-Year Requirements

### 1. Ambient, Multimodal Context

- Fuse user-approved screen state, UI semantics, voice, camera, notifications, location, motion, device state, files, and nearby devices into a bounded context graph.
- Distinguish observation from durable memory. Continuous context must expire unless a policy explicitly promotes it.
- Use event-driven sensing and hardware-assisted wake paths before continuous CPU processing.
- Keep sensitive raw media local whenever a derived signal is sufficient.

### 2. On-Device Intelligence Fabric

- Discover OS-managed models, application-provided models, and bundled specialist models at runtime.
- Route short, private, offline, and latency-sensitive work to on-device inference.
- Use cloud or trusted computers for context-heavy and frontier reasoning without making them the policy authority.
- Treat model availability, context limits, thermal load, memory pressure, battery state, and accelerator support as live routing inputs.

Android already positions AICore and Gemini Nano as system-managed on-device inference surfaces, while custom models can use LiteRT. SignalASI should expose one model contract above these implementations rather than binding planning logic to a specific runtime.

### 3. OS-Native Capability Discovery

- Support Android AppFunctions as a first-class capability source when the platform API is available.
- Preserve the existing bounded Android API and Accessibility adapters for older devices and apps that do not publish AppFunctions.
- Normalize AppFunctions, native tools, Intents, Accessibility actions, MCP tools, skills, and device commands into one versioned capability descriptor.
- Require exact schemas, declared side effects, risk, permissions, expected evidence, and compensation support.

Android AppFunctions is an experimental Android 16+ platform and Jetpack API that indexes application functions and exposes them to authorized agents. SignalASI should adopt it through a replaceable adapter because the API is still evolving.

### 4. Durable Autonomous Work

- Represent work as a persistent graph of goals, tasks, turns, tool calls, artifacts, approvals, checkpoints, and evidence.
- Continue background work through WorkManager, foreground execution, remote leases, and event triggers according to Android limits.
- Support deadlines, budgets, pause, resume, branch, retry, cancellation, compensation, and human handoff.
- Separate interactive latency from long-running completion. The user should receive immediate acknowledgment while durable work continues independently.

Android background quotas continue to tighten, including quota interactions for long-running workers. SignalASI must schedule by execution class instead of assuming an indefinitely alive process.

### 5. Federated Agent Collaboration

- Use MCP for agent-to-tool and context interoperability and A2A-compatible contracts for agent-to-agent task exchange.
- Negotiate capabilities instead of hard-coding product names such as Codex, Hermes, or Claude Code.
- Pass structured task envelopes, artifacts, evidence, deadlines, and policy constraints.
- Keep one user-owned supervisor and one local policy decision point even when many remote agents collaborate.

### 6. Personal World Model and Memory

- Maintain temporal entities, relationships, preferences, routines, projects, places, devices, and commitments in a local encrypted graph.
- Combine semantic retrieval with exact filters, time, provenance, access labels, and source citations.
- Support forgetting, correction, retention, export, encrypted synchronization, and private sessions.
- Learn only low-risk preferences by default. Identity, health, finance, secrets, and private communications require explicit memory policy.

### 7. Verifiable Delegation and Identity

- Give every device, agent, tool, model, task, and artifact a stable cryptographic identity.
- Issue narrow, expiring capability grants bound to task, scope, target, data class, and maximum side effect.
- Require signed receipts and evidence for consequential remote actions.
- Support revocation, key rotation, recovery, hardware-backed keys, biometric approval, and verifiable credentials.

### 8. Smart Environments

- Integrate Home Assistant and Matter-compatible ecosystems through normalized device traits.
- Discover stable device identities and capabilities, subscribe to state changes, batch compatible commands, and verify post-action state.
- Model shared-home roles, room boundaries, safety devices, locks, cameras, and energy systems with stricter policy tiers.
- Never treat network reachability as authorization.

### 9. Adaptive Resource Economics

- Score resources by capability, trust, data boundary, observed reliability, latency, quality, context capacity, cost, token use, energy, thermal pressure, and concurrency.
- Learn from observed execution receipts, not provider claims alone.
- Use circuit breakers, bounded fallback, hedged reads for safe requests, and deadline-aware cancellation.
- Never cross a privacy boundary merely because a faster or stronger model is available.

### 10. Rich, Interactive Results

- Keep the versioned structured block document as the canonical result format.
- Add live task timelines, citations, maps, charts, tables, media, diffs, forms, approvals, device controls, and artifact viewers as safe native blocks.
- Stream block patches with stable IDs and preserve them as durable records.
- Do not execute arbitrary HTML or JavaScript in the result surface.

## Target Architecture

```text
Experience Layer
  Agent UI | Voice | Notifications | Wearables | Accessibility
        |
Personal Supervisor
  Goal graph | Sessions | Context | Memory | Verification
        |
Local Policy Decision Point
  Identity | Consent | Risk | Data labels | Budgets | Audit
        |
Capability Fabric
  Native APIs | AppFunctions | Accessibility | MCP | Skills | A2A | Matter
        |
Adaptive Resource Router
  Phone models | Trusted computers | Private services | Cloud resources
        |
Execution and Evidence Plane
  Durable workers | Leases | Checkpoints | Receipts | Artifacts | Provenance
```

## Delivery Sequence

### Now

- Add data sensitivity, resource trust, energy profile, background support, context capacity, and live device constraints to routing.
- Add OS-native tools with bounded schemas and explicit local safety confirmation.
- Keep structured output, durable tasks, memory, MCP, skills, and remote execution under one supervisor.

### Next 12 Months

- Add an Android AppFunctions discovery and invocation adapter behind the existing native-tool registry.
- Add OS-managed on-device model adapters and model capability probing.
- Add signed capability grants, remote execution leases, and richer evidence receipts.
- Add Home Assistant and Matter trait adapters with post-action verification.

### Years 2-3

- Add a temporal personal world model, encrypted multi-device synchronization, proactive routines, and cross-device task migration.
- Add A2A-compatible discovery and collaboration, verifier agents, and policy-constrained agent teams.
- Add energy-aware continuous perception and wearable or vehicle surfaces.

### Years 4-5

- Make SignalASI a portable personal intelligence identity that can supervise heterogeneous devices and agents without surrendering memory or policy ownership.
- Support user-controlled marketplaces for signed models, tools, skills, and agents with reproducible capability and safety evaluation.
- Provide local-first autonomy that remains useful offline and can selectively scale to trusted private or cloud compute.

## External Technical Direction

- Android AppFunctions: https://developer.android.com/ai/appfunctions
- Android on-device AI: https://developer.android.com/ai/overview
- Android Gemini Nano and AICore: https://developer.android.com/ai/gemini-nano
- Android persistent background work: https://developer.android.com/develop/background-work/background-tasks/persistent
- Model Context Protocol authorization: https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization
- Agent2Agent specification: https://github.com/a2aproject/A2A/blob/main/docs/specification.md
- Google Home APIs and Matter: https://developers.home.google.com/apis/android/device

