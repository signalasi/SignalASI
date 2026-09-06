# Blob artifact return implementation checkpoint

## Status

Work in progress on `feat/blob-artifact-return-20260906`, initially based on main
`c3d0fafd5` and now fast-forwarded to `a50010274`, including merged pairing and
Desktop smoke fixes #2834/#2835. Older sections below record historical checks;
the receiver section describes current implementation work. This is not a
completed production feature or a release note.
No Android installation or deployment of this unfinished Blob output branch has
occurred. At the user's request, the separate clean merged-main worktree was
started as Desktop 1.0.22; the visible version, healthy backend, and 19/19 MQTT
subscriptions were verified. Existing pairing and chat state were retained.

The existing production `_publish_task_artifacts` still sends `artifact_chunk`
messages through MQTT. Android `AgentDesktopArtifactStore.ingest` still receives
those legacy chunks. Neither path has been switched by this checkpoint.

## Implemented foundations

- `blob_artifact_contract.py`: bounded output manifests and offers. Every scope,
  execution generation, filename, MIME type, URI, and content hash contributes
  to the transfer identity; the AEAD binding includes that identity. Validate
  the authenticated route, Desktop identity, and trusted Relay origin before
  accepting any offer. Only a recipient `stored` receipt authorizes cleanup;
  relay-upload completion and MQTT publication do not.
  `make_scoped_manifest` generates an immutable rich-link URI from the original
  artifact metadata: unchanged retries keep it, while a new recipient, Desktop,
  turn, generation, filename, or content hash produces a different URI.
- `blob_artifact_journal.py`: encrypted SQLite jobs with indexed bounded claims,
  duplicate suppression, claim fencing, owner-controlled restart recovery, and
  a receipt-versus-upload race transition. A receipt from another route or
  replacement identity cannot authorize cleanup. Old workers cannot override
  the receipt or a new claim. Retry counts control backoff, not Agent budgets.
  This journal uses SQLite's default rollback journal with synchronous FULL;
  it does not change WAL mode during concurrent construction.
- `StagedBlob.prepare_stream`: bounded encrypted staging from a caller-supplied
  stream, including compressed images held in memory or decrypted input. It
  supports short reads, verifies size and SHA-256 before committing a checkpoint,
  checks cancellation, and never reuses a partially staged key/nonce directory.
  Existing file preparation delegates its second pass to this implementation.
- `blob_artifact_sender.py`: independent bounded workers, exclusive process
  ownership, receipt-fenced upload/cleanup, resumable TLS transport, and durable
  failure observations. A corrupt staging checkpoint or chunk is observed, not
  retried forever or silently replaced. Duplicate receipts cannot race a still
  running upload worker into deleting its staging directory.
- Android `BlobArtifactContract` and the shared `blob-artifact-v1.json` fixture
  validate the same Chinese metadata, generation, transfer ID, and AEAD binding.
  The canonical JSON encoder now handles booleans and null identically to Python.
- Android `BlobVerifiedInputStream` and `BlobArtifactStorage`: bounded plaintext
  streaming directly into encrypted artifact files, whole-file hash/length
  validation before publication, atomic encrypted metadata replacement, per-URI
  file locks, immutable generation identity, and encrypted Downloads metadata.
  Corrupt duplicate local ciphertext is repaired before acknowledging storage.
  The existing artifact store has a worker-only Blob ingestion entry point and
  resolves the new records through existing preview/play/save functionality.
  Rich blocks with explicit mismatched artifact/hash/transfer IDs fail closed.

## Verification so far

The latest Python focused run passed **62 tests and 105 subtests in 39.79 seconds**
on main `e9063560a` plus this work. Coverage includes existing Blob crypto,
real TLS transport, process recovery, and the output contracts/journals/workers.
An intermediate sender test failed because assertions belonging to the source
change case had been misplaced during editing; they were restored to that case,
and corruption now explicitly asserts that its diagnostic checkpoint is retained.

Android contract and existing protocol tests passed **16 tests** before adding
the storage integration. The first expanded compile overlapped a source update
and consequently could not find the newly added `markSaved` method. A stable
source rerun compiled successfully and ran 35 tests, exposing one real failure:
the existing encrypted input stream threw again during close after corrupt
ciphertext, masking the original read failure and preventing repair. The new
verified stream now preserves the primary exception, closes idempotently, and
the storage verifier treats invalid encrypted lengths as repairable corruption.
The final stable-source regression passed **37 tests, zero failures/errors**:
7 artifact-contract, 8 artifact-storage, 9 existing protocol, 9 staging, and 4
verified-stream tests. This also covers authenticating the empty-file ciphertext
and avoiding duplicate closes that hide an earlier failure. Gradle completed in
5m 25s, including compilation, with existing deprecation warnings. Its heap
override is local to the test command; repository/phone runtime settings remain
unchanged. Repository checks and the Kotlin source-size check also passed;
whitespace validation was repeated on the final source state.
Neither these unit tests nor callback-based TLS tests prove paired-phone output
delivery. Production MQTT offer/receipt routing is still not connected.

A 152 MiB generated stream produces 152 encrypted chunks, reopens, and verifies
the entire plaintext SHA-256. Reads are bounded to 1 MiB, and Python-tracked peak
allocation is below 12 MiB. This is not a measurement of total process/native
memory, phone memory, public-network speed, or a live paired-device transfer.

## Required before enabling production

1. Connect the independent sender callbacks to current pairing/Relay settings
   and durable task observations. Preserve the full captured task/turn/generation
   identity when forwarding failures; corrupt unscoped journal records stay local.
2. Preserve source/workspace ownership until a verified recipient receipt.
   Make source-ledger acknowledgement, Relay revocation, and staging cleanup
   retryable/idempotent across process death and receipt races.
3. Wire and device-test the Android receiver journal and coordinator described
   below. Persist the encrypted offer before transport acknowledgement, then
   download using independent bounded workers and resumable ciphertext staging.
4. Wire receiver completion to the new encrypted storage entry point and existing
   file cards, playback, previews, Save to Downloads, and completion callbacks.
   Generate immutable scoped artifact URIs before building the Desktop rich
   reply; do not reuse an old URI across execution generations. Test real Android
   Keystore/content-provider behavior and memory on S20U. The existing source
   ledger's artifact-ID-only key and multi-recipient cleanup also need attention.
5. Negotiate explicit output-receiver capability. Wire the small offer/receipt
   paths into MQTT, including recovery on reconnect/startup. Do not enable the
   output sender merely because a phone supports the earlier input-upload path.
6. Remove the legacy 64 MiB preparation ceiling only for the new Blob path while
   keeping current non-Blob behavior functional until a Relay is configured.
7. Verify both directions with real HTTPS and encrypted control messages on the
   default S20U test phone; include process death, duplicate/wrong-scope receipts,
   large artifacts plus concurrent text, and final saved-file integrity.
8. Fetch/merge latest main, bump Android and Desktop versions, run repository and
   full backend/Android gates, then submit an independent English PR.

No public Relay endpoint is currently provisioned by this branch. Local HTTPS
acceptance must not be described as public-network delivery acceptance.

## Storage-policy integration on main f1537541a

PR #2833 has merged. This branch now uses `AttachmentLocalStore` for artifact
payloads, and `BlobCheckpointCipher` for authenticated recovery metadata.
Artifact payloads are byte-exact `.bin` files, not local AES containers.
Transport chunks and key/token checkpoints remain encrypted. The historical
verification descriptions above describe the previous implementation, not the
current payload policy.

After adaptation, Android compilation and all 37 focused tests passed on
2026-09-07 (3m 6s). Tests assert byte-exact payload length/content, encrypted
metadata, corruption repair, cancellation cleanup, recreation and scoped
duplicate handling. No deleted `AttachmentAtRestCipher` references remain in
the Blob source/tests. This is still a foundation checkpoint: receiver journal,
coordinator, production callbacks and real phone output delivery remain open.
No installation or production runtime change was made.

Parallel release follow-up: pairing PR #2834 contains Android/Desktop 1.0.24.
Its push Windows package check passed, while the PR-triggered package run failed
in the peer image viewer UI smoke (viewer hidden, image absent). The failed log
was inspected, not silently rerun. Background refresh and privacy cleanup can
replace smoke-only in-memory fixtures; the precise trigger remains unproven and
requires a separate test-isolation/diagnostic fix. No Blob release version or PR
has been published.

## Receiver journal and pipeline on main c1234f202

The branch was fast-forwarded to merged pairing PR #2834 before this work.
The independent Desktop smoke/staging fix PR #2835 subsequently passed all CI
checks and merged. No production Desktop or phone state was changed here.

- Added an encrypted SQLite receiver journal with bounded indexed claims,
  owner-controlled recovery, claim fencing, duplicate suppression, durable
  cancellation, terminal failure observation, and receipt replay after local
  file verification. Checkpoint bodies contain captured route, Desktop identity,
  both fingerprints, Relay origin, and the scoped artifact offer.
- Added an independent receiver coordinator. Persistence callbacks run only after
  journal commit. The owner-lock directory must match the journal directory.
  Bulk work uses separate workers, checks cancellation and current ownership,
  and preserves diagnostic staging on terminal failure. Retries use backoff,
  not a fixed action-count limit.
- Added the receiver pipeline: download and verify, publish local artifact,
  enqueue stored receipt, then cleanup staging. Local publication and receipt
  callbacks remain explicit integration boundaries, not production wiring.
  Missing/corrupt local payloads return to download; revoked identities and stale
  claims cannot publish a file or authorize Desktop cleanup.
- Invalid authenticated metadata (including bad tags and truncated headers) is
  reported as local record corruption, not endlessly retried as a network error.
  Malformed journal phases are quarantined without poisoning other due jobs.
- Initial focused verification: 47 JVM tests passed and 12 new instrumentation
  tests compiled. Those 12 SQLite/Keystore tests have not run on a phone yet.
- Real loopback HTTPS runner: 104 JVM tests passed with no skips. A 3,145,801-byte
  artifact was interrupted after its first chunk; all four chunks were downloaded
  exactly once across recovery. Publication preceded the stored receipt, staging
  was then removed, and a duplicate download step succeeded from the verified
  local file after Relay revocation. This does not prove production MQTT receipt
  delivery, callback persistence, or phone process-death recovery.

Final checks after synchronizing main `a50010274`: Python suites passed 62 tests
in 25.09 seconds. The HTTPS runner passed 105 JVM tests with no skips (2m 51s
Gradle run). The final metadata-truncation/journal-phase hardening was followed
by 48 focused JVM tests with no failures or skips and successful instrumentation
compilation (3m 18s). There are now 13 receiver journal instrumentation cases,
still not executed on a phone. Repository and whitespace checks passed.
No APK was installed and no Blob output capability was advertised.

### Transport revision recovery verified

The output offer now requires an authenticated monotonic transport revision.
The sender persists its allocation before generating replacement keys and uses
a durable restage phase. The receiver fences older claims, rejects identity
changes, ignores stale offers, and stages each revision separately. A committed
enqueue returns its accepted revision atomically; no post-commit lookup is
required to confirm persistence. Receipt/failure callbacks receive sanitized
scope, without Blob read tokens or decryption descriptors.

Final rerun: 107 JVM tests passed, zero failures/errors/skips, and all 18 receiver
SQLite/Keystore instrumentation tests compiled (not executed on a phone).
Real loopback HTTPS verified a 3,145,801-byte artifact interrupted after its first
chunk: old revision GET counts `[1,0,0,0]`, replacement `[1,1,1,1]`, exact final
hash and local storage before receipt. No production capability was enabled.

### Multi-recipient source ownership

The existing artifact delivery ledger keyed only by artifact ID and could
overwrite a first phone's ownership when the same output was registered for a
second phone. Cleanup checked only one phone's entries. It now keys by artifact
ID plus route, preserves existing on-disk owners, and requires every recipient
and artifact in the task workspace to reach `stored` before cleanup. Replayed
registration preserves receipts and explicit retention. Completed receipts stay
idempotent until bounded TTL pruning; pending recipients never lose sources
merely because seven days elapsed.

Receipts are flushed before deleting source files. Failed deletion remains
retryable; a crash after deletion but before completion persistence can safely
repeat cleanup. A corrupt ownership ledger fails closed rather than being
silently replaced. Blob cleanup also requires its commit callback to return
explicit `True`, retaining Relay/staging data on an uncommitted result.

Verification: 92 Python tests passed in 19.132 seconds, including 15 new ownership
cases and a real HTTPS uncommitted-receipt test. Repository and whitespace checks
passed. Real HTTP fixture timings are local test evidence, not public Relay or
phone performance claims. No APK installation or production Desktop restart.
An additional 42 existing workspace, conversation-artifact and MQTT turn-routing
tests passed. Receipt TTL is measured from completion rather than registration,
so a transfer finishing after a long outage retains its replayable confirmation.

Production integration still remains: output capability negotiation, captured
identity/source registration before publication, MQTT offer/receipt handlers,
Android lifecycle coordinator, durable UI publication/failure callbacks, and
paired phone process-death/large-file-plus-text acceptance. Do not advertise
Blob output support until these are connected and verified end to end.

### Authenticated production control ingress

Desktop now consumes `artifact_blob_capability` through the actual MQTT ingress
after Signal sender verification and before replay binding/transport ACK. The
encrypted per-pair record is independent of input-upload opt-in and Relay
credentials, has strict monotonic revisions, and is reset by pair identity
replacement or revocation. Ordinary chat does not import the output handlers or
perform capability/journal I/O. Android does not advertise this capability yet.

`artifact_blob_receipt` is also handled before transport ACK. The existing output
journal must already exist; receipts cannot create jobs or initialize workers.
The journal verifies the envelope conversation as well as the manifest, route,
Signal sender and both fingerprints before committing cleanup. Unknown or
mismatched receipts are consumed without changing ownership or reaching Agent
dispatch. Persistence failure prevents replay binding and acceptance. Duplicate
valid receipts preserve an already-running cleanup claim.

Verification: 135 Python tests passed in 15.206 seconds, including actual
`mqtt_bridge.on_message` dispatch with real isolated secure configuration and
SQLite files. Dispatch fixtures replace Signal decryption, so this is not a
phone E2E result. This phase adds 26 targeted capability/ingress tests. See
`docs/protocol/blob-artifact-receiver-negotiation.md` for the control contract.

Still required: Android receiver lifecycle/capability publication, outbound
artifact preparation and rich URI integration, scoped source ownership per
execution/transfer (in addition to per-recipient ownership), sender lifecycle
and failure observations, durable file-card publication, and device acceptance.
No release version was assigned, no production restart or APK install occurred.

### Android runtime receiver integration

`AndroidBlobArtifactReceives` now connects the authenticated Desktop MQTT offer
to the receiver coordinator. Small envelope validation/copying is followed by
independent background initialization, pair/Relay/task-scope validation and an
encrypted SQLite enqueue. Only the enqueue commit callback binds the ciphertext
replay record and permits the transport ACK, after rechecking the paired route
and both fingerprints. A failed write is not presented as successful delivery.

The pipeline uses the existing `AgentDesktopArtifactStore` storage root. Its
publication callback stages a deterministic, sanitized `artifact_available`
event in the durable inbound store; terminal failures similarly stage
`artifact_download_failed` with a bounded error code. Active listeners refresh
existing cards, and listener-less events remain available for inbound replay.
The artifact file and immutable metadata persist independently of UI delivery.
No duplicate chat message is inserted by this adapter.

Stored receipts carry the manifest conversation/turn/generation and use the
explicit paired Desktop route through the existing durable control publisher.
Relay configuration updates and MQTT attachment recovery wake only an existing
receiver database. Initialization and bulk work do not run on the UI/MQTT thread.

The production Android output capability is still not advertised. Outbound
Desktop preparation/publication, per-transfer source ownership, sender lifecycle,
download-progress display, and device acceptance are still required before
enabling the completed output path. JVM ingress-policy tests and compilation
are not evidence of real phone MQTT/SQLite/Keystore behavior.

Verification on this checkpoint: 114 JVM tests passed with zero failures, errors
or skips, including seven new ingress/publication policy cases. Instrumentation
sources compiled successfully. The loopback HTTPS test again verified the
3,145,801-byte bidirectional payload and interrupted/rekeyed output recovery with
exact hashes and storage before receipt. Repository and whitespace gates passed.
No phone tests, installation, production restart or release claim was made.

### Transfer-scoped workspace source adapter

The ownership ledger now supports explicit transfer scopes in addition to
artifact and phone route. Two turns/generations targeting the same phone retain
independent source leases. The legacy `artifact_receipt` path cannot select a
Blob lease by inserting a transfer ID into its payload; only the validated Blob
job's cleanup callback supplies the trusted scope. Legacy redelivery excludes
Blob-owned leases, preventing duplicate MQTT chunk replay.

`blob_artifact_source.py` validates prepared artifact metadata against its scoped
manifest, registers a workspace source lease, and provides real source-open and
receipt-commit callbacks for `BlobArtifactSender`. Transformed/compressed bytes
are persisted atomically to hidden content-addressed task files before lease
registration, preserving the user's original file and avoiding recompression on
retry. Existing corrupt snapshots fail explicitly, and failed snapshot writes
leave no partial file or false source registration.

Verification: 132 Python tests passed in 11.061 seconds without duplicate
imported test-class execution. A real isolated HTTPS Relay delivered one task
file to two independent recipients using the actual source and cleanup adapters;
the first stored receipt preserved the workspace and the second removed it.
Additional cases cover old-turn receipts, legacy/Blob ownership coexistence,
source-path/route mismatches, snapshot corruption, persistence failure and
legacy recovery exclusion. Repository and whitespace gates passed.

The source adapter is not yet the production task publisher. The dispatch step
must persist a recoverable publication intent across source registration and
job enqueue, otherwise a crash between the two stores could leave an orphaned
lease. It must also register every artifact in the batch before any receipt can
trigger cleanup, rewrite rich-output URIs before publication, and connect sender
lifecycle/failure observation. Do not enable capability advertisement yet.

### Durable batch registration barrier

`blob_artifact_batches.py` stores an encrypted, immutable batch intent and every
held output job in one SQLite transaction. Source preparation can create a
byte-exact snapshot but does not acquire a delivery lease. The sender registers
all source leases through one ownership-ledger update, then atomically activates
the held jobs. A crash between these stores leaves a recoverable batch rather
than a sending job with incomplete source ownership. Repeated registration is
idempotent; failed checkpoint writes retain the current claim for retry.

Receipts cannot activate held jobs. Another already-enqueued batch for the same
task blocks source cleanup until its registration has completed, including when
the recipients differ. Damaged intents or membership records are quarantined;
terminal source failures produce scoped failure jobs without deleting files.
The production publisher must still enqueue every intended recipient batch
before allowing any output receipt to clean the workspace. This barrier cannot
infer batches that have not yet been submitted, and mixed legacy/Blob publishing
still needs coordinated ownership at the publisher boundary.

Verification: 93 Blob artifact tests passed in 13.537 seconds. A separate
82-test source/ownership/sender/ingress run passed in 10.568 seconds; these suites
overlap and must not be added together as unique tests. New cases cover partial
transaction rollback, stale claims, all-or-none activation, source registration
followed by a failed checkpoint, missing batch members, corrupt membership,
cross-recipient cleanup, and local SQLite confidentiality. Pairing and
first-message integration regression also passed 20 tests in 1.945 seconds.

Latest fetched main remains `a50010274`. No phone access, production restart,
installation, release version or capability advertisement was performed.
Outbound preparation/rich URI integration, production sender lifecycle and
durable failure/UI publication still need completion before device acceptance.

### Production output lifecycle adapter

`blob_artifact_bridge.py` now supplies the sender's live identity/configuration,
source, durable control publication, and failure callbacks. Desktop bridge
startup resumes an existing output journal; a new store is created only by an
explicit output enqueue. Receipt acceptance wakes an existing sender without
creating a worker or database. Initialization retries on a separate thread and
does not hold the lock used by MQTT receipt wakeups. Stop fences initialization;
a subsequent start waits for a stopping sender rather than stealing its claims.

Offers use explicit conversation/task/turn/generation fields and stable UUID
envelopes per transfer/revision. The existing Signal publisher persists control
ciphertext before attempting MQTT. An exhausted control envelope is reported as
a delivery failure rather than being treated as queued success or repeatedly
re-encrypted. Disabling Relay pauses transfer even when its configured origin is
cleared; re-enabling the same origin resumes it. Identity changes remain fenced.

Failures first persist an encrypted local incident with the immutable manifest,
then queue a scoped `artifact_download_failed` control event. This path does not
depend on `AgentTaskManager.add_event`, which ignores already-terminal tasks.
Revoked/replaced peers receive no old-task diagnostics; the local incident and
journal remain available. Corrupt checkpoints produce local, bounded diagnostics
without invented task scopes or remote publication. A diagnostics UI and model
replanning integration are still required; persistence alone is not that UI.

The runtime adapter adds 17 focused tests, including actual bridge startup while
Blob initialization is blocked and actual durable control queuing with an
offline MQTT client (Signal encryption is replaced in that isolated test).
The 50-test lifecycle/subscription/pairing/durable-delivery suite passed in
5.103 seconds. The final 110-test Blob run passed in 16.149 seconds, including
the startup-hook test. Repository and whitespace gates passed. These runs
overlap; do not sum them as unique cases.

The normal task/peer artifact publisher still needs source preparation, scoped
URI rewriting and recoverable publication-intent integration before it calls
this adapter. Android capability advertisement remains disabled. No production
restart, APK installation, public Relay deployment or real-phone acceptance was
performed for this checkpoint.

### Normal Agent final-result publication

The normal remote Agent `publish_result` callback now delegates artifact-bearing
results to `blob_artifact_publication.py`. A receiver with an authenticated
output capability and enabled Relay gets a durable batch with the rewritten
logical reply. Other receivers retain the existing chunk delivery path. Plain
chat without output files does not import or initialize this adapter.

Prepared artifact URIs are scoped by phone, conversation, turn and execution
generation before publication. Rich-block URIs, source URIs and exact Markdown
link destinations are rewritten on a copied payload. Card metadata describes
the actual transport bytes, including compressed-image size/hash and original
size/hash. The user's original file and caller-owned reply are not changed.

The encrypted batch intent now includes the logical reply. Source registration
precedes reply publication, and reply queue persistence precedes activation of
the upload jobs. A failed reply queue write or activation checkpoint leaves the
jobs held; replay uses the same logical reply and stable UUID. The canonical
result archive contains that UUID and the scoped links before control queuing.
A conflicting existing archive cannot silently supply a recovery hash for a
different reply: the batch produces a scoped failure and retains its source.

Verification: 121 Blob artifact tests passed in 19.636 seconds. The 47-test
publication/batch/runtime/Agent-recovery subset also passed in 5.426 seconds;
the runs overlap. Eleven new publication tests cover real task files, encrypted
batch recovery, legacy fallback, compressed metadata, reply scope validation,
archive identity and queue/checkpoint failures. This is local integration
evidence, not a real Provider or phone acceptance result.

Remaining before enabling output capability: direct peer/contact publication,
manual result replay using canonical scoped links instead of legacy rerendering,
large-output finalization limits, receiver progress/card interactions and paired
device recovery tests. Android capability advertisement remains disabled; no
production restart, APK installation or new release was made in this phase.

### Canonical manual result replay

`republish_agent_task_result` now consults the persisted Blob publication before
the legacy source scan and rich-output builder. The lookup validates encrypted
intent, membership, job digests and every route/conversation/task/turn/generation
field. It is indexed by the task key and does not scan the full output journal.
The current paired identity is checked again before queuing the original reply.

This works after a successful source handoff removed the Desktop workspace and
after the phone acknowledged the separate result-page archive. Explicit replay
uses the existing logical result outbox's replay behavior; it does not call a
Provider, prepare new source files, rewrite URIs or acquire new delivery leases.
Pending registration stays queued and wakes recovery instead of bypassing the
registration barrier. Failed, damaged or ambiguous records return bounded error
codes without falling back to legacy rerendering.

A committed publication wins over failed duplicate intents for the same scope.
Two conflicting committed records are rejected rather than chosen by row order.
Different execution generations retain independent canonical cards. A missing
Blob journal still permits the existing non-Blob replay path without creating
Blob state.

Verification: 136 Blob artifact tests passed in 20.995 seconds, including 15 new
replay cases and an invocation of the real `republish_agent_task_result` entry.
Repository and tracked-diff whitespace checks passed. Tests use isolated files,
archives and queues; this is not Android UI, public Relay or Provider acceptance.
No installation or production restart occurred. Direct peer/contact publication,
large-output finalization limits, receiver progress/UI and phone E2E recovery
remain before enabling output capability.

### Contact publication and recoverable local history

The real `mqtt_bridge.publish_peer_message` entry now selects Blob publication
only for a receiver with an authenticated artifact capability and an enabled
private Relay configuration. Plain text and unnegotiated peers retain the
existing path. Contact images are not compressed, and audio descriptors retain
their duration without invoking an Agent or ASR.

The original contact message UUID is shared by the local row, source scope and
durable control envelope. The complete publication and local projection metadata
are encrypted in the same SQLite batch transaction as held transfer jobs. Local
database contention after that commit returns a queued response; recovery can
create the missing row before publishing the card. Local filesystem paths are
removed before the wire payload is queued. Duplicate names retain distinct
artifact identities and attachment order.

Contact history supports idempotent projection with conflict checks and deletion
tombstones. A delayed publication cannot recreate a deleted conversation row.
Conditional delivery-state updates cannot downgrade an already delivered/read
message or notify the UI on a no-op. Failures before publication update local
queued state and persist an encrypted incident, without creating an orphan
remote failure. Published cards can receive a scoped attachment error. Deleted
cards and revoked identities do not receive these error publications.

Validation: 17 new contact tests invoke the actual send entry and isolated
SQLite stores. The complete Blob artifact suite passed 153 tests in 25.427
seconds; 53 legacy attachment, contact storage and phone-tool routing regressions
passed in 4.213 seconds. Repository and whitespace checks passed. Latest main
was fetched and remains `a50010274`. No phone installation, pairing reset,
production restart or capability advertisement occurred.

This is not an end-to-end contact transfer acceptance result. Remaining work
includes completion/receipt projection into contact delivery state, large-output
preparation limits, Android progress and card interactions, Relay provisioning,
and paired-device process-death/reconnection tests. Audio tests validate metadata
preservation, not audible playback quality. Deletion tombstones prevent local
history resurrection; they do not claim to unsend an already published message.

### Recipient persistence and contact completion

Contact delivery now has an idempotent stored-observation callback in the output
worker's cleanup phase. It runs after a fully validated recipient receipt and
before source cleanup or Relay revocation. Batch membership lookup uses the
transfer index, validates the encrypted publication and all member digests,
and marks the contact card delivered only when every member is in the
receipt-authorized cleanup/done state. Sending a card, publishing MQTT traffic
or receiving only one of several files does not complete the message.

The local state projection is retried after database contention or process death
before cleanup can finish. Replaying an already projected completion produces no
new UI event, preserves read status, and never recreates a deleted row. A later
valid receipt can recover a previously failed transfer. Source cleanup failure
does not reverse successful recipient persistence. The Desktop renderer now
maps delivered/read explicitly, with Chinese translations, rather than showing
these states as queued. Read is displayed only when stored as such; no read
receipt is inferred from file persistence.

An isolated HTTPS Relay test invokes the real contact send entry, uploads and
downloads 1,200,000 original bytes, verifies byte equality, flushes/fsyncs the
receiver file, and then submits its scoped receipt. The original local card
becomes delivered and the output job completes. This is an actual encrypted
HTTP data transfer, but the receiver is a test client, not the Android App, and
the Signal/MQTT publication boundary is mocked.

Validation: 15 new persistence/projection tests; full Blob suite 168 tests passed
in 32.139 seconds; 53 contact/legacy/routing regressions passed in 3.812 seconds;
15 renderer/contact/voice tests passed. Repository checks passed. No APK was
installed, no production process was restarted, and output capability remains
unadvertised. Android card/progress integration, size-limit completion, deployed
Relay configuration and paired-device acceptance are still required.

### Android loaded-card progress integration

The receiver pipeline now emits local download progress from verified chunk
storage. Events contain the original message ID, peer/Agent distinction, exact
artifact URI, transfer ID, digest, size and execution scope; they do not contain
Relay credentials, local paths or synthetic chat-message IDs. Download progress
is capped at 99 until the verified artifact has been committed and the existing
durable availability event is published.

A separate contact presentation path updates loaded attachments only when
contact, original remote message, URI, transfer ID, digest and size all match.
It never uses attachment ordinal fallback and never creates an empty message
when progress arrives before the card. It preserves voice metadata and existing
attachment identity. Progress is not written to chat history for every chunk.
Completed cards ignore late progress/failure events; unchanged state produces no
extra adapter update.

The RecyclerView diff distinguishes percentage-only changes from content,
membership and state transitions. Percentage-only payloads update active image
and file rings/text without resetting thumbnails or rebuilding the message row.
Completion and other substantive changes still require a full bind. Existing
completed or failed sibling attachments are not presented as downloading.

This phase covers loaded contact cards, not complete presentation recovery.
Cold-start/reopened-card hydration from durable transfer state, off-screen
terminal-state projection, Agent rich-output progress and voice progress UI
remain to be integrated. Real-device scrolling/rendering verification is also
pending. Output capability remains unadvertised, with no APK installation or
production restart in this phase.

Validation: the Android/Python interoperability runner passed 131 JVM tests
without failures or skips, compiled the instrumentation tests, and verified
3,145,801 bytes in each direction over real loopback HTTPS. Artifact persistence
preceded its receipt; resumed/rekeyed downloads retained the expected per-chunk
request counts. After the final completed-state and sibling-view guards, the
17 presentation/row-snapshot tests passed again against the final source, and
Android instrumentation Kotlin compilation passed. Repository and whitespace
checks passed. No physical Android UI or playback validation is claimed.

### Large-file preparation aligned with negotiated Blob capacity

The normal Agent-result and direct contact send entries now select preparation
capacity from the current authenticated artifact-receiver capability and private
Relay enablement. Negotiated output preparation permits the protocol's 1 GiB
maximum. Existing MQTT-only preparation stays at 64 MiB. Cross-turn absolute and
relative artifact imports now permit 1 GiB so a large referenced output is not
discarded before Blob preparation is reached.

File digests remain streaming with 1 MiB reads; uncompressed contact images and
files do not acquire a whole-file bytes buffer. The preparation chunk-count
check uses the selected capacity rather than the old 256-chunk ceiling. Legacy
chunk generation explicitly rejects a prepared artifact above 64 MiB, and the
publication fallback rejects oversized artifacts if Blob capability/configuration
has disappeared after preparation. Such files must not silently become thousands
of MQTT packets. Compressed Agent images retain their existing transport policy.

Validation includes the real contact send entry with a 159,383,569-byte file,
its local imported copy, scoped control card and registered Blob source job.
The Python traced allocation peak was 2,125,873 bytes. An actual 1,073,741,824-byte
file passed streamed preparation with a traced peak of 2,120,742 bytes and no
transport bytes buffer. These are Python allocation measurements, not whole
process RSS, Android memory or over-the-network throughput. Source and imported
cross-turn hashes matched, and over-limit files were rejected before hashing or
copying in the tested paths.

The complete Blob suite passed 180 tests in 36.488 seconds, including 12 new
large-preparation cases. Another 63 attachment/contact/workspace/routing tests
and 36 execution-harness/conversation-artifact tests passed. Repository and
whitespace checks passed. Latest main was fetched and no production processes
or devices were changed. Remaining acceptance includes visible delivery failure
handling when no large-file transport is available, Android off-screen/cold-start
state hydration, Relay deployment, and actual paired-device large transfers.

### Visible preparation failures and explicit delivery-only recovery

The normal Agent final callback now treats missing, empty, unpreparable and
oversized output files as explicit delivery failures instead of silently removing
them. Unconfigured large-file transport is distinguished from the 1 GiB protocol
limit. Contact sends retain a failed local message with its imported attachment;
the API and Desktop error text explain the cause without exposing a source path.

Agent pre-publication failures persist an encrypted, execution-scoped intent with
the original reply, output inventory, recipient identity binding and streaming
source hashes. The failure notice goes through the durable Signal control outbox,
not the immutable final-result archive. Otherwise an error archived as the final
answer would conflict with a later successful Blob card in the same generation.
No file cleanup or legacy MQTT bulk fallback occurs on this failure path.

The existing explicit result-republish entry checks a canonical Blob batch first,
then resumes a deferred intent. A restored transport queues the original output
without invoking the provider or generating the file again. Identity changes,
source content changes, corrupted checkpoints and unavailable storage reject
recovery explicitly and retain the checkpoint. Per-intent locks serialize the
same retry; source hashing/preparation does not hold the global phone-send lock.

Verification on main a500102744156069f2d24faec4be1a5c47f6c40e plus this worktree:
- 201 Blob tests passed in 40.224 seconds, including actual production callback
  code executed with isolated provider/transport boundaries, real workspace
  files, failure notices, explicit replay and final archive acceptance.
- 103 related artifact, contact, workspace, routing, Agent recovery and execution
  regressions passed; 15 Desktop contact/voice tests passed.
- The real 159,383,569-byte contact preparation used 2,125,332 bytes of traced
  Python heap; the 1 GiB preparation used 2,120,582 bytes. These are not RSS or
  mobile/network throughput measurements.

This does not advertise the receiver capability or enable production Blob
delivery yet. Remaining work includes Android off-screen/cold-start projection,
real UI confirmation that notices and recovered cards replace the correct turn,
Relay provisioning, paired-device transfer/restart tests, and release/PR gates.
No APK, phone installation, pairing reset or production Desktop restart was done.

### S20U durable contact attachment projection validation

Terminal peer attachment events now persist in an encrypted auxiliary table in
the existing chat database transaction. They project onto the original inbound
message by contact, remote message hash and transfer identity. Events that arrive
before their card are retained for the later upsert. Reopening the database and
stale UI writes cannot revert completion. Progress remains transient; ordinary
text and legacy attachments do not query the Blob projection table. Deletion,
contact clearing and history replacement clean up the related projection.

The first physical SM-G9880 run found a real v4-to-v5 migration defect:
`writeMetadataIfAbsent` treated SQLite's ignored duplicate insert result as a
database failure. The fix uses parameterized `INSERT OR IGNORE`, preserving
existing metadata and propagating actual SQL errors. Instrumentation also checks
the absent-key migration and the non-Blob fast path. No production data reset or
pairing replacement is part of these tests.

The initial 152 MiB HTTPS transfer over ADB reverse verified the file hash,
152 upload chunks accepted exactly once, intentional process death and recovery,
and a 3,145,801-byte return file. Checkpoint recovery took 16 ms; preparation
sampled PSS growth was 14,206 KiB. This run FAILED the control-latency benchmark:
534 ms maximum exceeded the unchanged 500 ms gate (main callback maximum 6 ms,
397 probes, no probe failures). It is retained as failed evidence, not counted
as a full pass. This fixture does not exercise production pairing or MQTT.
