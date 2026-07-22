# Mobile Agent Resource Orchestration

SignalASI treats every callable capability as a resource rather than hard-coding a single model or Agent. The mobile Agent can route work to on-device tools, trusted computers, private-network services, cloud providers, and device controllers while preserving one execution and safety model.

The orchestrator is split into two planes:

- The **control plane** analyzes the goal, applies trust and safety boundaries, selects resources, sets budgets, and validates results.
- The **data plane** executes Android actions, encrypted Agent messages, model API calls, MCP calls, skills, knowledge retrieval, and device commands.

No provider is allowed to choose its own trust level or bypass confirmation. A powerful model can propose work, but the phone remains the authority that resolves and executes it.

## Resource classes

| Class | Typical examples | Location |
| --- | --- | --- |
| On-device model | Mobile small language or vision model | Phone |
| Remote local model | Ollama, LM Studio, or vLLM on a trusted computer | Trusted desktop/private network |
| Cloud model | OpenAI, Anthropic, Gemini, DeepSeek, Qwen | Cloud |
| Local or remote Agent | Mobile runtime, Hermes, Codex, Claude Code, custom Agent | Phone/trusted desktop/cloud |
| Tool | Android intents, screen actions, weather, web search | Phone/cloud |
| MCP server | Local stdio MCP, desktop MCP, remote HTTP MCP | Phone/trusted desktop/cloud |
| Skill | Built-in workflow, desktop skill, cloud skill | Phone/trusted desktop/cloud |
| Device connector | Home Assistant or a custom device adapter | Private network/cloud |
| Knowledge | Local memory and indexed private documents | Phone |

Unavailable and unconfigured resources remain visible to diagnostics but are never selected for execution.

## Resource contract

Every resource is described by a common contract:

- Stable resource and target identifiers.
- Location and trust boundary.
- Availability state: unconfigured, disconnected, available, rate limited, or circuit open.
- Required and optional capabilities.
- Estimated cost, latency, and intelligence quality.
- Tool support and live-data support.
- Observed success rate and end-to-end latency.
- Safety class and confirmation requirements for side effects.

This contract allows the router to compare resources without pretending that a model, a deterministic phone tool, an MCP server, and Home Assistant are interchangeable.

## Routing pipeline

1. Analyze the goal for required capabilities: live data, tool use, code, screen operations, knowledge, device control, reasoning quality, privacy, latency, and token budget.
2. Build the current resource catalog from Android tools, configured cloud providers, paired Desktop contacts, MCP/skill contacts, local models, workflows, Home Assistant, and custom devices.
3. Exclude resources that are unconfigured, disconnected, outside a privacy boundary, missing a mandatory capability, or protected by an open circuit breaker.
4. Score remaining resources by capability coverage, observed reliability, privacy, requested mode, latency, cost, and intelligence quality.
5. Select one primary resource and up to four ordered fallbacks.
6. Execute through the normal safety and confirmation layer. Record success, failure, and latency for future routing.
7. Validate the result. Tasks requiring changing information must contain live grounding; device tasks must report an observed controller result; screen actions must be verified against the resulting screen.

The Android host compiles native tools, deterministic system actions, and external connectors into one `AgentRuntimeCapabilityMatrix` for every runtime context. A record can be available, require setup, be unavailable, or be policy-blocked. All records remain visible to Control Center diagnostics, while only currently available, non-blocked records are eligible for model catalogs or execution. Dynamic availability providers are resolved when a catalog or subset is created and again immediately before invocation, so revoking a permission, backgrounding a foreground-only operation, removing a runtime pack, or opening a policy circuit takes effect without restarting the Agent.

Camera and microphone access use explicit foreground capture activities. They return content URIs as conversation-bound artifacts and never expose silent background capture. Notification reads are bounded and redact sensitive rows before model access. Notification replies require a live, non-sensitive free-form `RemoteInput`, reject stale targets, and report only that Android accepted the dispatch; they do not claim delivery or receipt.

## Selection strategy

Hard constraints are evaluated before scoring:

- A private task cannot cross a cloud boundary.
- A live-data task cannot use a resource without live grounding or tools.
- Code, MCP, skill, knowledge, screen, and device tasks require their respective capabilities.
- Disconnected resources and open circuits cannot be selected.
- High-risk actions must retain confirmation even when a fallback is used.

Resources that pass the hard gates are scored by capability coverage, reliability, measured latency, expected cost, model quality, tool support, and locality. Large prompts add a cost penalty to paid resources. Observed latency has more weight in Fast mode, while model quality has more weight in Quality mode.

Typical decisions are:

| Task | Preferred route | Why |
| --- | --- | --- |
| Open an app or set an alarm | Deterministic Android tool | Fast, private, zero model tokens |
| Summarize a short private note | On-device or trusted local model | Keeps content within the selected trust boundary |
| Current weather or news | Live tool plus capable model/Agent | Requires fresh evidence rather than model memory |
| Repository implementation | Codex or Claude Code | Specialist code tools and long-running task support |
| Broad research | Hermes or research Agent | Search, MCP, and synthesis capabilities |
| Complex ambiguous reasoning | Strongest healthy configured model | Quality is more important than cost or latency |
| Smart-home state change | Local Home Assistant API | Direct controller result and explicit device risk policy |
| Repeated personal workflow | Local skill/workflow | Predictable execution and minimal token use |

## Composite execution

Many goals need more than one resource. SignalASI represents them as a validated action graph rather than asking one model to do everything. A typical flow is:

1. A local tool captures only the required screen or knowledge evidence.
2. A model or specialist Agent plans or transforms that evidence.
3. An MCP server or skill performs a bounded operation.
4. Android or Home Assistant executes the side effect after confirmation.
5. The phone observes the result and either completes, replans, or rolls back.

Outputs are untrusted data when handed from one Agent to another. They are size limited, sensitive values are redacted, graph depth and tool-call counts are capped, and every side effect is revalidated locally.

## Typed Home Assistant execution

Home Assistant participates in the same phone-native tool contract as Android system tools. The phone advertises four dynamically available tools:

- `signalasi.home_assistant.connection.status` checks connectivity without returning the endpoint or token.
- `signalasi.home_assistant.entities.list` returns a bounded, filtered inventory and redacts protected states.
- `signalasi.home_assistant.entity.read` reads one exact entity under remembered read consent.
- `signalasi.home_assistant.service.call` targets one entity, accepts bounded JSON service data, requires an idempotency key, and rereads controller state after deterministic service calls.

Ordinary entity control uses per-entity confirm-once consent. Locks, alarms, security devices, cameras, sirens, valves, automations, scripts, and security-named targets always require confirmation. Administrative core services and secret-bearing service parameters are rejected before network execution. A successful REST response proves only that Home Assistant accepted the service. A matching follow-up state proves the controller state, never the physical-world outcome.

## Policy modes

- **Balanced:** reliability first, then capability, privacy, latency, cost, and quality.
- **Fast:** favors deterministic local tools and low-latency local/private-network resources.
- **Economy:** favors free local tools and local models, then low-cost providers; prompts and context are kept compact.
- **Quality:** favors frontier models or specialist Agents when the task is complex.
- **Private:** excludes cloud resources and keeps data on the phone or explicitly trusted computers.

The mode can be inferred from the goal (for example, "quick", "save tokens", "use the strongest model", or "local only") and can later be exposed as a user setting.

## Failure handling

- A resource that fails three consecutive times opens a one-minute circuit breaker.
- Synchronous setup or pairing failures immediately advance to the next fallback.
- Asynchronous Agent failure advances to the next compatible resource without changing the task identity.
- Direct cloud-model calls retry across configured providers without changing the user task identity.
- A configured local Home Assistant endpoint can fall back to a paired controller Agent when the local endpoint fails.
- Cloud live-data tasks receive deterministic grounding before model generation. If a model declines to call a weather or web tool, the retrieved context still allows a grounded answer.
- A model response that only states that live data is unavailable is rejected and the next provider is tried.
- Remote Agent fallback preserves SignalASI encryption and message correlation.
- High-risk actions never bypass confirmation during fallback.

Fallback is deliberately bounded. Fast and Economy modes keep at most two alternatives, Balanced and Private keep three, and Quality keeps four. The orchestrator never retries forever, silently weakens a privacy requirement, or converts a protected side effect into an unconfirmed action.

## Token and context control

- Deterministic tools run before model calls when they can answer the task directly.
- Only the evidence required by the selected resource crosses a trust boundary.
- Local knowledge retrieval sends selected excerpts rather than the full knowledge base.
- Small or local models are preferred for short classification and formatting tasks.
- Frontier models are reserved for complex reasoning, code, or ambiguous multi-step planning.
- Fast and Economy tasks bypass a cloud planning call when the deterministic planner already produced a valid action.
- Fast and Economy planning prompts use reduced screen, app, connector, and context inventories.

Token cost is not optimized in isolation. The router first protects correctness and privacy, then minimizes expected total cost, including retries. A cheap resource with a high failure rate can cost more than a reliable resource, so observed health remains part of the decision.

## Availability and recovery state

The phone records per-resource successes, failures, consecutive failures, and average end-to-end latency. Three consecutive failures open a short circuit so new tasks immediately choose another compatible resource. A successful response closes the failure streak. Pairing, API configuration, encrypted session state, and controller configuration are checked before a target becomes eligible.

When all compatible resources are unavailable, SignalASI returns a setup or availability result instead of sending the task to an unrelated provider. This is especially important for private data and device operations. Control Center reports exact live tool states and partial category ratios instead of marking an entire category available when only one member works.

## Current implementation boundary

The Android runtime now catalogs built-in tools/workflows, cloud model providers, trusted Desktop Agents, remote local models, MCP/skill-style Desktop contacts, Home Assistant, and custom devices. Actual on-device model inference and native Android stdio MCP hosting require their respective runtimes to be installed; until then those resource types remain unavailable rather than silently falling back to an unrelated service.
