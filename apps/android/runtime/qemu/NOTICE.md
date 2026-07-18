# SignalASI Android QEMU Runtime Notice

The optional SignalASI Android Linux runtime launches an unmodified-device, no-root QEMU system
process built from QEMU 10.2.1 and Android compatibility patches maintained by the Termux project.
QEMU is distributed under GPL-2.0-only. Its source URL, exact digest, build framework commit, patch
set, and build commands are pinned in this repository.

The generated bundle may also contain dynamically linked libraries built from the pinned Termux
package graph. Their license metadata remains available in that build graph. Release engineering
must retain the generated bundle manifest, corresponding sources, build scripts, and license texts
with every distributed runtime artifact.

SignalASI does not require the Termux application and does not execute a Termux userspace. The
Termux package builder is used only as an Android NDK cross-compilation framework.
