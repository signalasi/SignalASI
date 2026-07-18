# Android On-Device Runtime Sources

This directory contains source and reproducible build definitions for the SignalASI Linux Guest.
It does not contain release binaries, private signing keys, downloaded toolchains, or user data.

- `guest/` implements the authenticated Guest broker.
- `buildroot-external/` defines the AArch64 Linux base image and native task launcher.
- `qemu/` defines the minimal QEMU 10.2.1 Android cross-build and redistribution notice. The
  Termux package builder is a pinned build-time framework only; the app does not require Termux.
- Toolchain images use a fixed read-only ABI: self-contained executable wrappers live in `bin/`,
  the image includes `signalasi-pack.json`, and every declared capability has a required entrypoint.
- Runtime images are built under ignored `build/` paths, signed as `.sarpack` release artifacts,
  and installed by the Android runtime-pack manager.

Required entrypoints are:

| Pack | Entrypoints |
| --- | --- |
| `python-uv` | `bin/python3`, `bin/uv` |
| `node-js` | `bin/node`, `bin/tsx` |
| `go` | `bin/go` |
| `rust` | `bin/rustc` |
| `cpp` | `bin/cc`, `bin/c++` |
| `java` | `bin/java`, `bin/javac` |
| `ffmpeg` | `bin/ffmpeg`, `bin/ffprobe` |

Pinned builders cover `python-uv`, `node-js`, `go`, `rust`, `cpp`, `java`, and `ffmpeg`; run the
matching `npm run runtime:build-*` command on Linux to produce an unsigned image and signing
config. These source recipes do not mean the binary packs have been published or installed.

Entrypoints must be relocatable files or wrappers whose dependencies remain inside the signed pack
or the matching `linux-base`. Absolute symlinks, setuid/setgid files, world-writable files, device
nodes, and escaping symlinks are rejected by the image builder.

The standard Android APK bundles the native QEMU engine plus signed `linux-base` and `python-uv`
archives. On first launch, the app verifies and installs both default packs into private storage.
It must not report the runtime as ready unless the engine, verified base image, Python/uv pack, and
authenticated Guest health handshake are all present. Other language and media packs remain
independently downloadable.

Build the native engine on Linux with Docker, `dpkg-deb`, `patchelf`, and LLVM tools:

```bash
npm run runtime:build-android-qemu
```

The command verifies the pinned package-builder archive, builds QEMU and its dependency graph from
source for Android ARM64, follows the exact ELF dependency closure, and emits ignored generated JNI
and notice directories under `build/runtime`. Gradle consumes those directories when present. A
source recipe without those generated files does not add a placeholder engine to the APK.
