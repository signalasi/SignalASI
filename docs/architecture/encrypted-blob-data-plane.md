# Independent encrypted Blob data plane

## Status and integration boundary

This phase provides a runnable, self-hosted binary relay and a worker-side
Desktop Python client. It does **not yet switch** Android/Desktop Agent,
artifact, or contact attachments away from MQTT. Those call sites continue
using their existing transport until the next integration phase. No external
relay is selected, started, or provisioned automatically; no user data was
uploaded to a third-party host during development.

The target remains: authenticated Signal control messages carry small offers,
requests and durable receipts; HTTPS carries attachment ciphertext. A relay
must be reachable from both devices, so localhost/LAN-only tests do not establish
cross-public-network operation. Do not expose Desktop's loopback control API or
reuse its legacy plaintext `file_server.py` as a public relay.

## Components

- `blob_protocol.py`: strict ciphertext manifest and missing bitmap validation.
- `blob_crypto.py`: immutable encrypted chunk staging and device-encrypted
  transfer checkpoints, including keys and resume capabilities.
- `blob_store.py`: SQLite WAL content-addressed ciphertext storage, atomic
  duplicate writes, capability checks, expiry and bounded garbage collection.
- `blob_relay.py`: standalone FastAPI application and CLI, with bounded bodies
  and worker-thread storage operations. There is no public listing API.
- `blob_client.py`: certificate-validating HTTPS connection pool, binary upload,
  missing-block resume, resumable download, authentication and revocation.
- `core/protocol/blob-*-v1.schema.json`: public/private wire contracts.
- `core/protocol/fixtures/blob-aead-v1.json`: fixed test-only interoperability
  vector. Its published key is never used for real transfers.

## Encryption and identity

Each preparation generates a new random 256-bit AES key, 128-bit Blob ID and
64-bit nonce prefix. A chunk nonce is `prefix || uint32be(index)`, totaling
96 bits. AES-256-GCM appends a 16-byte tag. Full plaintext chunks are 1 MiB;
the last may be shorter. An empty file has one authenticated empty chunk.
The existing 1 GiB attachment ceiling gives at most 1,024 chunks.

AAD is the following unambiguous binary sequence:

1. ASCII `GalaxySSI-Blob-AEAD-v1` followed by one zero byte.
2. 16-byte Blob ID and 32-byte binding hash.
3. Big-endian uint64 total plaintext size and uint32 chunk size (1,048,576).
4. 32-byte whole-plaintext SHA-256.
5. Big-endian uint32 chunk index and uint32 plaintext chunk length.

The binding hash is SHA-256 of ASCII JSON with sorted keys, compact separators,
and escaped non-ASCII characters. Bindings have 1-16 lowercase ASCII identifier
keys and nonempty string values of at most 256 characters, not floating-point
numbers or arbitrary nested JSON. The caller must supply the same immutable
identity on preparation and consumption. The Agent integration must bind its
route/conversation/goal/task/run/turn/action plus attachment identity; peer
messages must bind their route, conversation, message and attachment identity.
Never derive the expected binding from the received offer itself.

A source is hashed before encryption and checked again during encryption. If it
changes, preparation fails without a usable checkpoint. A partially prepared
directory cannot be reused; retries start with fresh keys/nonces. Completed
preparation is immutable: upload retries read the persisted ciphertext rather
than encrypting changed source bytes with reused nonces.

The relay sees ciphertext hashes, ciphertext sizes, random session IDs, request
timing and network addresses. It receives no encryption key, plaintext file
hash, filename, MIME type or conversation content. This is not a traffic-analysis
anonymity guarantee. Read/write capabilities are stored only as SHA-256 hashes.
The provisioning credential is server configuration, not part of any offer.

Deduplication is by **ciphertext** hash. Retries and retained references reuse
identical ciphertext. Separately preparing the same plaintext uses new keys and
produces different hashes; there is no convergent encryption or global plaintext
equality oracle.

AES-GCM requires unique nonces per key; see the
[cryptography AEAD API](https://cryptography.io/en/latest/hazmat/primitives/aead/).
The implementation uses APIs supported by the repository's cryptography 43-45
dependency range, not newer `encrypt_into` APIs.

## HTTP contract

All routes start with `/v1/blobs/{random_id}`. Tokens use an Authorization Bearer
header, never query parameters. Production clients require HTTPS, certificate
validation and hostname validation, and reject redirects. Tests explicitly opt
into literal-loopback HTTP. Responses are `no-store`; access logging is disabled
by the CLI. Reverse proxies must also avoid logging authorization or bodies.

| Method and suffix | Credential | Purpose |
| --- | --- | --- |
| PUT (no suffix) | Provisioning | Idempotently create manifest + distinct read/write capabilities |
| GET (no suffix) | Read | Fetch public ciphertext manifest |
| GET `/missing` | Write | Fetch ordered missing-block bitmap and completion state |
| PUT `/chunks/{index}` | Write | Store raw ciphertext, atomically hash-check and deduplicate |
| GET `/chunks/{index}` | Read | Fetch one authenticated-by-hash ciphertext block |
| DELETE (no suffix) | Write | Revoke session and remove its references |

The manifest is compact sorted-key ASCII JSON. Its SHA-256 root is pinned in the
private Signal offer, so a relay cannot silently substitute or reorder chunks.
The bitmap is hex-encoded bytes, low bit first, with 1 meaning missing; unused
high bits must be zero. Its maximum is 128 bytes before hex encoding. No attachment
bytes undergo JSON or Base64 wrapping on this channel.

The relay acknowledges PUT only after the SQLite transaction commits. `FULL`
synchronous mode is used for this new independent store. This does not prove
hardware power-loss behavior. `complete` means all declared ciphertext chunks
are stored, **not** that the recipient decrypted, persisted or read the file.
The existing durable end-to-end receipt remains the eventual cleanup authority.

Sender checkpoints precede creation requests. A lost creation/PUT response can
be retried with the same ID, capabilities and ciphertext. A receiver retains
encrypted chunks and verifies each local chunk before skipping it on resume.
Whole-file AEAD/hash verification completes before download success is returned.
Plaintext consumption is streaming; callers must not acknowledge a partial
iterator or commit an incomplete artifact.

`upload(on_offer=...)` can queue the private control offer after relay creation
but **before** the first bulk chunk, so the eventual UI can show an attachment
card while upload is in progress. Repeated callbacks use the same Blob identity
and must be deduplicated by the control queue. A concurrent reader may receive
`chunk_not_ready` until that chunk has arrived. A returned upload offer after
the method completes means relay upload finished, not recipient delivery.
The worker client currently transfers missing chunks sequentially per file
over a reused connection; resource-adaptive per-file parallelism is not claimed.

Each staging directory has a nonblocking OS file lock. Competing workers get
`transfer_busy` rather than replacing capabilities. After acquiring ownership,
the client reloads the latest encrypted checkpoint. Process death releases the
lock automatically while retaining chunks and checkpoint for another worker.

Stored chunk corruption invalidates the CAS row and exposes it as missing again.
The receiver returns `corrupt_chunk_requires_repair`; the later control-plane
integration must request sender repair rather than pretend download succeeded.
Expired/revoked offers fail explicitly. The relay does not silently renew them.

## Resource and privacy boundaries

Manifest requests are limited to 128 KiB; ciphertext requests to 1 MiB + 16 bytes,
including chunked HTTP bodies. Four bulk operations are admitted by default;
status/control requests do not acquire their bulk semaphore. The standalone
server also bounds concurrent connections. These are operator storage/network
capacity settings, not Agent action-count or goal-duration budgets.

The default relay quota is 10 GiB of live ciphertext, with 10,000 sessions and a
seven-day TTL. Database metadata, WAL and filesystem allocation add overhead;
the quota is not a hard OS disk limit. Expired sessions and unreferenced chunks
are collected in bounded batches. SQLite reuses freed pages; deletion does not
promise immediate filesystem shrinkage or secure erase on flash storage.

Private transfer checkpoints use the existing device-bound secure-state store.
Staging files contain ciphertext only. Small plaintext/key objects necessarily
exist during Python cryptographic calls; mutable preparation buffers are wiped,
but Python immutable bytes/strings cannot be guaranteed zeroized. Do not describe
this phase as complete runtime-memory hardening. Local staging is retained for
resume; the future receipt integration owns its final cleanup.

## Run a self-hosted relay

Use Python 3.11 or newer, install the backend requirements, and set `GALAXYSSI_BLOB_PROVISION_TOKEN`
to a securely generated 32-byte hex secret, then run from the backend directory:

```powershell
python blob_relay.py --database "$env:LOCALAPPDATA\GalaxySSI\blob-relay\blobs.sqlite3"
```

The default listener is `127.0.0.1:18766`. Put a certificate-validating HTTPS
reverse proxy in front of this service only, without exposing ports 8765/18765.
Configure body limits for at least 1 MiB + 16 bytes and disable request-body
logging. Provision the origin/credential through trusted application settings;
do not embed deployment credentials in APKs, source, chat text, or QR labels.

No persistent production service is started by tests or Desktop startup in this
phase. Android OkHttp wiring, capability negotiation, sender/receiver durable
receipt cleanup, contact/Agent artifact integration and S20U public-network
chaos acceptance are still required before claiming MQTT/data-plane separation.
