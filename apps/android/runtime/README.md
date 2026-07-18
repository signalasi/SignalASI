# Android On-Device Runtime Sources

This directory contains source and reproducible build definitions for the SignalASI Linux Guest.
It does not contain release binaries, private signing keys, downloaded toolchains, or user data.

- `guest/` implements the authenticated Guest broker.
- `buildroot-external/` defines the AArch64 Linux base image and native task launcher.
- Runtime images are built under ignored `build/` paths, signed as `.sarpack` release artifacts,
  and installed by the Android runtime-pack manager.

The Android APK must not report this runtime as ready unless the native QEMU engine, a verified
`linux-base` image, and the authenticated Guest health handshake are all present.
