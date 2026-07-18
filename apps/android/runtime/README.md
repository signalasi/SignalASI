# Android On-Device Runtime Sources

This directory contains source and reproducible build definitions for the SignalASI Linux Guest.
It does not contain release binaries, private signing keys, downloaded toolchains, or user data.

- `guest/` implements the authenticated Guest broker.
- `buildroot-external/` defines the AArch64 Linux base image and native task launcher.
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

The Android APK must not report this runtime as ready unless the native QEMU engine, a verified
`linux-base` image, and the authenticated Guest health handshake are all present.
