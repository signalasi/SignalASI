# Per-device Blob Relay settings

## Scope

Desktop 1.0.21 adds persisted Relay configuration for each paired phone in the
gateway panel's **Attachment Relay** section. It builds on the authenticated
`blob_relay_config` capability exchange and the durable input Blob receiver.
Android's protocol and ASR/local model code are unchanged in this phase.

This does not deploy a public Relay. An operator must supply a reachable HTTPS
Relay origin and its 64-character hexadecimal provisioning credential. A saved
configuration is not evidence that the Relay is healthy or that a phone received
the update. The UI distinguishes saved settings from a queued control update.

## Storage and identity

- Each route has a separate `blob-pair-settings/<sha256(route)>.secure.json` file
  under the existing Desktop runtime data directory.
- Files use the existing device-bound encrypted secure-state implementation with
  purpose `blob.pair-configuration.v1`; no plaintext credential JSON is written.
- Configuration and capability opt-in bind to the route, phone Signal name,
  remote identity fingerprint, and Desktop identity fingerprint.
- A replacement identity on the same route cannot inherit the previous device's
  override or opt-in. Old editor submissions are rejected using the full binding
  digest and expected revision.
- Revoke, forget, and clear-pairing operations remove the affected settings files.
  Cleanup runs outside the pairing registry lock to avoid inverted lock ordering.
- Devices without an override retain the existing environment-based defaults.
  An explicit disabled override stays disabled even if the global environment
  enables a Relay later.

## Operator API and UI

`GET /api/blob/settings/{route}` and `PUT /api/blob/settings/{route}` require the
existing loopback operator boundary. Electron exposes these through narrow IPC
methods. Responses contain no provisioning credential, only its presence flag.
Request validation and storage errors are redacted rather than reflecting input
or arbitrary exception text.

Writes use compare-and-set revisions. A blank credential keeps an existing one
only when the origin is unchanged; a new origin requires a new credential.
Disabling clears the stored origin and credential. Revision numbers increase
even if the wall clock moves backward.

The renderer reads configuration only when its section is opened. Switching
devices invalidates in-flight responses. Closing or locking clears sensitive
fields; resuming an open gateway reloads them without returning the credential.
Saving clears the typed credential immediately. Unrelated periodic device
refreshes do not reset an unchanged form.

## Distribution and receive path

Only a paired phone that explicitly requests Blob configuration is marked as
Blob-capable for its current identity. Subsequent operator changes are sent to
that device through the existing durable encrypted control lane. They do not
become chat messages, system notifications, or model context.

Settings persist before publication. If the phone has not opted in, or queueing
fails, the saved revision is returned on its next authenticated capability
request. No credential is broadcast to every paired phone.

The production receiver resolves the trusted Relay origin by route and Signal
source, both at enqueue and before executing a resumed job. A caller-provided
offer cannot select another device's Relay. Receiver workers remain lazy unless
existing durable jobs require restart recovery.

## Boundaries and follow-up

- This phase does not automatically migrate in-flight transfers when the Relay
  origin changes. Existing jobs revalidate against the current origin and may
  require the existing failure/replanning path and a new transfer.
- A queued update is not a phone acknowledgement or a successful transfer.
- Public Relay deployment, actual paired-device MQTT/HTTPS acceptance, and the
  complete production outbound artifact path remain separate acceptance work.
- Saved JavaScript strings cannot be guaranteed to be zeroized by the runtime;
  the UI avoids credential read-back and promptly drops typed references.
- Existing identities, pairing secrets, messages, and phone model installations
  must not be reset to test these settings.

## Automated coverage

Backend tests cover encrypted persistence, restart reads, two-device isolation,
identity replacement, stale editors, revision changes, credential rotation,
explicit disable, malformed state, storage failures, capability opt-in, pairing
cleanup, receive-origin isolation, and redacted API failures. Real HTTPS expiry
recovery still runs through the production bridge adapter with complete paired
identity/configuration fixtures.

Renderer tests cover closed-panel behavior, stale asynchronous responses,
credential clearing, device removal, queued versus saved states, and bounded
error messages. A local Playwright check uses the real renderer HTML/CSS with
mocked settings IPC at 1280px and 640px widths; it is not a live Electron or
phone end-to-end claim.
