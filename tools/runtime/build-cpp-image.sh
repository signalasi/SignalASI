#!/usr/bin/env bash
set -euo pipefail

zig_version="0.16.0"
zig_archive_sha256="ea4b09bfb22ec6f6c6ceac57ab63efb6b46e17ab08d21f69f3a48b38e1534f17"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "$script_dir/../.." && pwd)"
source "$script_dir/runtime-download.sh"
work_root="${SIGNALASI_RUNTIME_BUILD_DIR:-$repository_root/build/runtime/cpp}"
download_dir="${SIGNALASI_RUNTIME_DOWNLOAD_DIR:-$repository_root/build/runtime/downloads}"
output="${1:-$repository_root/build/runtime/release/cpp-zig-$zig_version-arm64-v8a.img}"

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "The C/C++ image must be built on Linux." >&2
  exit 2
fi
for command in curl sha256sum tar node realpath mv; do
  command -v "$command" >/dev/null || {
    echo "Missing build dependency: $command" >&2
    exit 2
  }
done

repository_root="$(realpath -m "$repository_root")"
work_root="$(realpath -m "$work_root")"
download_dir="$(realpath -m "$download_dir")"
output="$(realpath -m "$output")"
archive="$download_dir/zig-aarch64-linux-$zig_version.tar.xz"
if [[ -z "$work_root" || "$work_root" == "/" || "$work_root" == "$repository_root" ]]; then
  echo "Refusing to use an unsafe runtime build directory." >&2
  exit 2
fi

mkdir -p "$download_dir" "$work_root" "$(dirname "$output")"
download_verified_runtime_input \
  "https://ziglang.org/download/$zig_version/zig-aarch64-linux-$zig_version.tar.xz" \
  "$archive" \
  "$zig_archive_sha256" \
  "Zig $zig_version"

source_root="$work_root/source-root"
rm -rf "$source_root"
mkdir -p "$source_root/bin"
tar --extract --xz --file "$archive" --directory "$source_root" --strip-components=1
for driver in cc c++; do
  cat >"$source_root/bin/$driver" <<EOF
#!/bin/sh
pack_root=\$(CDPATH= cd -- "\${0%/*}/.." && pwd)
exec "\$pack_root/zig" $driver "\$@"
EOF
  chmod 0755 "$source_root/bin/$driver"
done

node "$repository_root/tools/runtime/build-runtime-image.mjs" \
  --pack-id cpp \
  --version "$zig_version" \
  --source "$source_root" \
  --output "$output" \
  --license "MIT"
