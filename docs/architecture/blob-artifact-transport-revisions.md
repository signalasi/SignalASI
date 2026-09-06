# Artifact output transport revisions

This output-only contract is under development and is not advertised by the
production pairing capability list yet. Existing input uploads are unchanged.

## Identity

`artifact_blob_offer` carries a required JSON integer `transport_revision` in
the inclusive range 1 through 9,007,199,254,740,991. The field is authenticated
by the existing end-to-end encrypted control envelope. Missing values, strings,
booleans and fractional values are rejected.

The artifact manifest, immutable URI, full task/turn/generation scope and
`transfer_id` describe the content being delivered. A transport revision describes
one set of Blob key, nonce, Relay blob ID and bearer capabilities. Rekeying does
not change the artifact identity. Chunk AEAD remains bound to the scoped artifact;
fresh keys and nonces isolate successive transport attempts.

## Sender recovery

The sender keeps its revision counter inside the authenticated, encrypted SQLite
job body. Zero means no transport has been prepared. Before generating a new
staging checkpoint, it atomically reserves the next revision. A crash between
reservation and staging can leave a gap; revisions need not be consecutive.
Reopening an intact checkpoint reuses its reserved revision and ciphertext.

When Relay data expires, the job first persists `restage`. That phase removes the
old staging data idempotently, then returns to `upload`. A crash before or after
deletion resumes the same phase. The next preparation reserves a higher revision.
A verified recipient receipt can supersede either upload or restage, and stale
claims cannot reset cleanup back to upload. Counter overflow is an explicit local
failure, never a wraparound to an old transport revision.

## Receiver recovery

- The identity digest includes the manifest, origin, route, Desktop ID and both
  captured fingerprints, but excludes transport credentials and revision.
- An equal revision must have exactly the same canonical offer body.
- A higher revision with the same identity replaces the encrypted offer and
  invalidates the old worker claim. A cancelled transfer remains cancelled.
- A lower revision cannot replace the body, reset the active claim or roll back
  the current state. A duplicate completed current revision reverifies the local
  file before replaying a stored receipt.
- Checkpoint revision columns must match the authenticated offer. Corrupt rows
  are quarantined rather than repeatedly scheduled.
- Ciphertext staging lives under `<transfer_id>/<transport_revision>/`. Old and
  new keys therefore cannot share chunk files. Final staging cleanup does not
  follow symbolic links and does not delete the committed artifact.

The enqueue transaction returns its committed revision with the transfer ID.
Control acknowledgement does not depend on a second database read. Only an
accepted newer revision cancels the old bulk request. Failure and receipt callback
contexts omit download keys and bearer tokens.

## Completion

The receipt still binds the immutable artifact identity and content hash, not
one transport revision: any verified, durably stored copy of that exact artifact
can authorize sender cleanup. Relay upload completion alone cannot.

Real HTTPS tests cover same-revision resume and rekeying after an interrupted
download. SQLite/Keystore instrumentation covers stale, duplicate and reordered
offers, cancellation, revision corruption and process-recreation claims. Passing
JVM/TLS tests or compiling instrumentation is not paired-phone acceptance.

Production integration still requires negotiated output capability, authenticated
MQTT offer/receipt routing, idempotent UI/task publication, source-ownership
cleanup, and device process-death tests.
