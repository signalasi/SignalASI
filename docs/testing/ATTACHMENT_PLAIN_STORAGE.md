# Attachment Local Storage

Android 1.0.23 (869) and Desktop 1.0.23 remove the additional AES layer on
locally stored attachment content. This is a development-format change,
intended for a clean installation. There is no old attachment migration or
compatibility reader.

## Scope

- Android Agent artifacts, incoming/outgoing peer attachments, thumbnails,
  rich-output media, and voice attachments are stored as their original bytes.
- Desktop peer attachments are copied byte-for-byte into the private attachment
  directory. Metadata and chat text continue using encrypted database storage.
- Signal encryption, MQTT opaque-envelope encryption, TLS, authentication,
  routing, chunk hashes, whole-file SHA-256, acknowledgement, and redelivery
  are unchanged.
- Blob relay ciphertext remains ciphertext. Its checkpoint contains transport
  keys and bearer tokens, so it still uses authenticated local encryption via
  `BlobCheckpointCipher`; this is not an attachment-content encryption layer.
- The Android non-exported, grant-only content provider exposes read-only,
  seekable descriptors. Only attachment directories can be resolved; private
  database/preferences paths and traversal are rejected.

## Security And Development Reset

Attachments no longer have application-level confidentiality at rest. A party
with access to the application's private files can read them. Android sandbox
permissions and device storage protections still apply. This does not make
MQTT attachment traffic plaintext.

Old AES attachment bytes are not supported by this change. Use a clean Android
installation and a fresh Desktop test profile when accepting it. Do not delete
other device data or shared Desktop profiles as part of automated tests.

## Verification

- `AttachmentLocalStoreTest`: exact byte preservation, empty/buffer-boundary
  files, length mismatch, callback failure, staging cleanup, lazy chunk reads,
  and destination overwrite protection.
- `PeerMessageAttachmentStoreTest`: original voice payload persistence and
  attachment lifetime.
- `BlobStagingTest`: checkpoint secrets remain encrypted and authenticated;
  transport ciphertext/hash/binding behavior remains intact.
- `AttachmentLocalStoreDeviceTest`: seekable descriptor, original bytes,
  traversal rejection, and non-attachment private file rejection.
- `PeerOriginalAttachmentInstrumentedTest`: original attachment preparation
  and bounded thumbnail rendering, updated for local plaintext storage.
- Desktop `test_peer_attachment_storage`, `test_peer_chat_store`,
  `test_artifact_delivery`, and `test_input_attachment_transfer`: hashes,
  unmodified file payloads, chunking, redelivery, and encrypted text records.

Native AI video generation, 240p transcoding, and other voice-development work
are deliberately excluded from this PR.

## Local Results (2026-09-06)

- Android targeted JVM tests: 16 passed, zero failures/errors/skips.
- Android main and instrumentation Kotlin compilation: passed.
- Desktop targeted Python tests: 30 passed.
- Repository check and Kotlin source-size policy: passed.
- Unit-test compilation used `-Pgalaxyssi.requireEmbeddedRuntime=false`; it
  does not prove a packaged APK contains every embedded Linux/runtime asset.
- Physical-device tests and a clean reinstall were not executed for this PR.
  No production Desktop profile, phone data, or pairing was reset.
