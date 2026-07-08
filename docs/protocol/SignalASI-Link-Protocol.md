# SignalASI Link Protocol

SignalASI Link defines pairing, identity, encrypted message envelopes, delivery traces, and agent contact metadata.

## Status

Version: v1.0.3

The protocol is implemented by the Android app and the Desktop connector. Additive fields are allowed when older clients can ignore them safely. Removing fields or renaming public types requires a protocol version bump.

## Transport

Default MQTT relay:

- Broker: `broker.emqx.io`
- Port: `1883`
- QoS: `1`
- Phone to Desktop topic: `signalasichat/android/send`
- Desktop to phone topic: `signalasichat/android/recv`
- Desktop status topic: `signalasichat/android/pc`

The MQTT broker is a relay, not a trust anchor. Message content must be encrypted after pairing.

## Pairing QR Payload

Desktop exposes the QR page at `/signalasi/verify`. The QR payload uses this shape:

```json
{
  "type": "signalasi_verify",
  "version": 1,
  "device": "pc",
  "desktop_id": "desktop_<fingerprint-prefix>",
  "desktop_name": "Workstation",
  "device_id": 1,
  "identity_key": "<base64>",
  "identity_key_sha256": "<hex>",
  "created_at": 1720000000
}
```

Android must recompute `identity_key_sha256` from `identity_key` before accepting the QR payload. Old route names and old Hermes pairing payload types are invalid for new clients.

## Pairing Claim

After the QR is verified, Android publishes a public control message to `signalasichat/android/send`:

```json
{
  "type": "signalasi_pairing_claim",
  "pairing_token": "<token>",
  "device": "android",
  "signalasi_id": "signalasi:<fingerprint-prefix>",
  "identity_fingerprint": "<phone-identity-sha256>",
  "signal_bundle": {
    "scheme": "signal",
    "identityKey": "<base64>"
  }
}
```

Desktop validates the token, records the new phone fingerprint, replaces the peer signal bundle, and publishes a pairing confirmation. If a previous phone was paired, Desktop publishes a revocation message for the previous trust state.

## Signal Envelope

Encrypted messages use this outer envelope:

```json
{
  "version": 1,
  "scheme": "signal",
  "from": "signalasi:<sender>",
  "to": "desktop_<fingerprint-prefix>",
  "signal_type": "prekey",
  "message_type": 3,
  "body": "<base64-libsignal-message>",
  "time": 1720000000000
}
```

The decrypted plaintext is a JSON payload. Common plaintext fields include:

- `type`
- `contact_id`
- `content`
- `client_message_id`
- `source_message_id`
- `delivery_trace`
- `reply_topic`
- `time`

Android refuses encrypted-contact publishing when no Signal session exists for the target contact.

## Delivery Trace

Delivery trace entries use this shape:

```json
{
  "stage": "desktop_decrypted",
  "at": 1720000000000,
  "detail": "SignalASI Link"
}
```

Clients keep the latest trace entries and display them as delivery evidence. Common stages include:

- `created`
- `persisted`
- `queued`
- `mqtt_published`
- `desktop_received`
- `desktop_decrypted`
- `agent_started`
- `agent_replied`
- `desktop_reply_publish_queued`
- `desktop_reply_broker_ack`
- `desktop_broker_ack`
- `desktop_agent_push_queued`
- `desktop_pairing_confirmed`
- `desktop_pairing_revocation_queued`
- `received`
- `decrypted`
- `cloud_request`
- `cloud_reply`
- `cloud_error`

Delivery acknowledgements use `type: delivery_ack` and include the source message ID plus the updated delivery trace.

## Agent Contact Metadata

Connector status messages may include `connector_agents`, where each agent has:

- `id`
- `name`
- `kind`
- `status`
- `detail_code`
- `detail_params`
- `setup_code`
- `setup_params`
- `pairing_code`
- `pairing_params`

Android presents Desktop agents as contacts and labels them as agent, model, or device depending on contact metadata.

## Compatibility Rules

- Public route is `/signalasi/verify`.
- Pairing QR type is `signalasi_verify`.
- Pairing claim type is `signalasi_pairing_claim`.
- Topic namespace is `signalasichat/android/`.
- Old `/signal/verify`, `/signalagi/verify`, `hermes_signal_verify`, and `hermes_pairing_claim` names are not valid for new clients.
- Clients must ignore unknown additive fields.
- Clients must reject missing identity keys, mismatched fingerprints, and unpaired phone delivery.
