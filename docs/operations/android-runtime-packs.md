# Android Runtime Pack Operations

SignalASI runtime images are release artifacts, not source-tree assets. The Android app accepts a
pack only when its manifest, archive digest, catalog, architecture, host version, guest protocol,
dependencies, and size limits all verify.

## Trust model

Production runtime packs use a dedicated X.509 signing certificate. The public certificate is
embedded in:

`apps/android/app/src/main/res/raw/signalasi_runtime_trust_anchors.json`

The private key must remain outside the repository and CI workspace artifacts. `.pem`, `.key`,
`.p12`, `.jks`, and `.keystore` files are ignored. A debuggable APK additionally trusts its current
APK signing certificate for local development. A non-debuggable APK does not, so an empty embedded
anchor list deliberately makes production runtime installation unavailable.

Print the certificate value and key id to add to the trust-anchor file:

```bash
npm run runtime:print-trust-anchor -- --certificate /secure/runtime-signing-cert.pem
```

For rotation, release an app that trusts both the current and next public certificates, publish
new packs with the next key, and remove the old anchor in a later app release. Never silently load
trust anchors from downloaded data.

## Pack configuration

Create a JSON configuration outside the repository or under an ignored release directory:

```json
{
  "id": "linux-base",
  "version": "1.0.0",
  "architecture": "arm64-v8a",
  "image": "./linux-base.img",
  "image_file": "linux-base.img",
  "capabilities": ["shell.execute"],
  "dependencies": [],
  "license": "Apache-2.0",
  "minimum_host_version_code": 60,
  "guest_api_version": 1,
  "release_notes": "Initial guest runtime"
}
```

`archive_size_bytes` and `installed_size_bytes` may be supplied as signed upper bounds. When they
are omitted, the builder derives conservative bounds from the image. The catalog always records
the exact final archive size and SHA-256.

## Build a pack

### Build `linux-base`

The repository contains a pinned Buildroot external tree, kernel configuration, guest broker, and
native sandbox launcher. Build it on a Linux filesystem with the required host compiler tools:

```bash
npm run runtime:build-linux-base -- release/linux-base.img
```

The script verifies the Buildroot 2026.05.1 source archive, uses Linux 6.18.7, builds an AArch64
`virt` kernel with an embedded initramfs, and emits a SHA-256 for the image. The guest has no direct
network interface. Its root broker communicates through virtio-serial, while each untrusted task
runs under the per-task namespace and privilege boundary described in the architecture document.

Windows syntax checks and unit tests are useful but are not a substitute for this Linux build. A
release pipeline must boot the resulting image with the exact Android QEMU engine, complete the
authenticated health handshake, execute concurrency/cancellation/quota tests, generate an SBOM,
and only then sign and publish the pack.

### Sign the image

The builder requires JDK 17 `jar`, the signing certificate, and its matching unencrypted or
passphrase-supported PEM private key:

```bash
npm run runtime:build-pack -- \
  --config release/linux-base.json \
  --output release/linux-base-1.0.0-arm64-v8a.sarpack \
  --certificate /secure/runtime-signing-cert.pem \
  --key /secure/runtime-signing-key.pem
```

The command creates the `.sarpack` archive and a neighboring `.sarpack.metadata.json` file. It
streams image hashing, signs an unambiguous length-prefixed manifest payload, verifies the private
key against the certificate, and removes staging data even when packaging fails.

This command signs and packages an existing image. It does not build the Android QEMU executable or
language toolchains. Those inputs must come from separately audited, reproducible pipelines with
source, license, and SBOM records.

## Build the release catalog

Place every archive and metadata file for the release in one directory. Dependencies must be
present for the same architecture.

```bash
npm run runtime:build-catalog -- \
  --entries release/runtime-packs \
  --output release/android-runtime-catalog-v1.json \
  --base-url https://github.com/signalasi/SignalASI/releases/download/runtime-1.0.0/ \
  --version 1.0.0 \
  --expires-days 30 \
  --certificate /secure/runtime-signing-cert.pem \
  --key /secure/runtime-signing-key.pem \
  --trust-anchors apps/android/app/src/main/res/raw/signalasi_runtime_trust_anchors.json
```

The catalog builder rehashes every archive rather than trusting metadata, rejects duplicate packs,
missing dependencies, dependency cycles, invalid notes, and non-HTTPS destinations, then signs the
canonical catalog. Upload the catalog under the exact asset name
`android-runtime-catalog-v1.json`; the app's default catalog URL resolves that asset from the latest
GitHub release.

## Verification gates

Run both host and Android checks before publishing:

```bash
npm run test:runtime-tools
npm run test:guest-runtime
npm run check
cd apps/android
./gradlew :app:testDebugUnitTest
```

Do not publish a runtime catalog until the corresponding production app contains its public trust
anchor. Do not mark Linux or a language as ready until the native controller starts the guest and
the authenticated health handshake succeeds.
