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

### Build the Android QEMU engine

The native engine is built separately from runtime packs. On a Linux host with Docker, Debian
package tools, `patchelf`, LLVM tools, Node.js, and standard archive utilities, run:

```bash
npm run runtime:build-android-qemu
```

The builder verifies a fixed Termux package-builder source snapshot, replaces its package namespace
with the SignalASI Android application id, and cross-compiles a minimal QEMU 10.2.1 AArch64 system
emulator and all required libraries from source. Termux is a build framework here, not an app or
runtime dependency.

The release bundle is generated under:

```text
build/runtime/android-jni-libs/arm64-v8a/
build/runtime/android-assets/runtime/qemu/
```

The ELF collector rejects non-AArch64 inputs, unsafe dependency names, escaping symbolic links,
missing dependencies, and search paths other than `$ORIGIN`. It renames the executable to
`libsignalasi_qemu.so`, records the complete hashed dependency closure, and adds the build notice
and provenance manifest as generated assets. Android uses extracted native-library packaging so
the process controller can launch this executable from the application native-library directory.

Generated binaries stay ignored. A release pipeline must retain corresponding source archives,
patches, license texts, manifest, and SBOM; boot the exact APK engine against `linux-base`; finish
the authenticated Guest handshake; and pass cancellation, concurrency, quota, and artifact tests.

The standard SignalASI Android distribution bundles this QEMU engine and the signed `linux-base`
and `python-uv` packs. Prepare those APK inputs after building and signing the two packs:

```bash
npm run runtime:prepare-android-defaults -- \
  --asset-root build/runtime/android-assets \
  --jni-root build/runtime/android-jni-libs \
  --certificate /secure/runtime-signing-cert.pem \
  --pack release/linux-base-1.0.0-arm64-v8a.sarpack \
  --pack release/python-uv-0.11.29-arm64-v8a.sarpack

cd apps/android
./gradlew :app:assembleRelease -Psignalasi.requireEmbeddedRuntime=true
```

The APK signature protects the bundled archive index and trust-anchor asset. Each `.sarpack` still
passes its normal X.509 signature, digest, dependency, architecture, protocol, and size checks on
first-launch installation. A release build and a bundled debug build fail when the engine or either
default pack is absent. `.github/workflows/android-runtime-apk.yml` performs the complete Linux
build; development runs may use an ephemeral certificate, while production requires the persistent
runtime signing certificate from repository secrets.

### Build a toolchain image

For a language, compiler, or FFmpeg pack, first prepare its fixed-ABI source root and build a
reproducible SquashFS image on Linux. For example:

```bash
npm run runtime:build-image -- \
  --pack-id ffmpeg \
  --version 8.1.2 \
  --source build/runtime/ffmpeg-root \
  --output release/ffmpeg.img \
  --license GPL-2.0-or-later
```

The builder validates required executable entrypoints, filesystem safety, bounded source size, and
required capabilities. It normalizes timestamps, writes `signalasi-pack.json`, and emits
`ffmpeg.img.config.json` for the signing step. It deliberately does not download an unpinned
toolchain or accept arbitrary executable layouts.

The first pinned toolchain recipe builds the Python/uv pack from Astral's official ARM64 musl
release. It verifies the release archive and both upstream license files before packaging:

```bash
npm run runtime:build-python-uv -- release/python-uv-0.11.29-arm64-v8a.img
```

The Guest uses the Python interpreter from the matching `linux-base`, disables uv self-modification
and Python downloads, and runs uv offline unless a future host-mediated package-fetch tool places
verified wheels into the task workspace.

The Node.js pack uses the official Node.js 24 LTS ARM64 release on the glibc-based Guest. Its `tsx`
entrypoint delegates to Node's built-in stable TypeScript type stripping, so the base pack does not
silently install mutable npm dependencies:

```bash
npm run runtime:build-node-js -- release/node-js-24.18.0-arm64-v8a.img
```

The Go pack preserves the official relocatable toolchain tree, including its standard library,
compiler, linker, and BSD license. It therefore resolves `GOROOT` from the mounted pack without
depending on a compiler in `linux-base`:

```bash
npm run runtime:build-go -- release/go-1.26.5-arm64-v8a.img
```

The Rust pack uses the official offline standalone installer and excludes only the documentation.
It declares the `cpp` pack as a dependency because native Rust executables require a linker:

```bash
npm run runtime:build-rust -- release/rust-1.97.1-arm64-v8a.img
```

The C/C++ pack uses the official Zig ARM64 distribution as a self-contained Clang/LLD toolchain
and exposes conventional `cc` and `c++` drivers. Compiler caches remain in the isolated task
workspace:

```bash
npm run runtime:build-cpp -- release/cpp-zig-0.16.0-arm64-v8a.img
```

The Java pack uses the Eclipse Temurin 25 LTS ARM64 JDK, including both `java` and `javac`:

```bash
npm run runtime:build-java -- release/java-25.0.3_9-arm64-v8a.img
```

The FFmpeg recipe cross-compiles an offline, static ARM64 `ffmpeg` and `ffprobe` pair. Network
protocols and unpinned external codec libraries are disabled; media must enter the workspace
through a host-mediated SignalASI tool:

```bash
npm run runtime:build-ffmpeg -- release/ffmpeg-8.1.2-arm64-v8a.img
```

### Sign the image

The builder requires JDK 17 `jar`, the signing certificate, and its matching unencrypted or
passphrase-supported PEM private key:

```bash
npm run runtime:build-pack -- \
  --config release/ffmpeg.img.config.json \
  --output release/ffmpeg-8.1.2-arm64-v8a.sarpack \
  --certificate /secure/runtime-signing-cert.pem \
  --key /secure/runtime-signing-key.pem
```

The command creates the `.sarpack` archive and a neighboring `.sarpack.metadata.json` file. It
streams image hashing, signs an unambiguous length-prefixed manifest payload, verifies the private
key against the certificate, and removes staging data even when packaging fails.

This command signs and packages an existing image. It does not invoke the separate Android QEMU or
language-toolchain builders. Those inputs must come from their audited, reproducible pipelines with
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
