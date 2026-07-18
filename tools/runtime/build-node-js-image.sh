#!/usr/bin/env bash
set -euo pipefail

node_version="24.18.0"
node_archive_sha256="58c9520501f6ae2b52d5b210444e24b9d0c029a58c5011b797bc1fe7105886f6"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "$script_dir/../.." && pwd)"
work_root="${SIGNALASI_RUNTIME_BUILD_DIR:-$repository_root/build/runtime/node-js}"
download_dir="${SIGNALASI_RUNTIME_DOWNLOAD_DIR:-$repository_root/build/runtime/downloads}"
output="${1:-$repository_root/build/runtime/release/node-js-$node_version-arm64-v8a.img}"

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "The node-js image must be built on Linux." >&2
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
archive="$download_dir/node-v$node_version-linux-arm64.tar.xz"
if [[ -z "$work_root" || "$work_root" == "/" || "$work_root" == "$repository_root" ]]; then
  echo "Refusing to use an unsafe runtime build directory." >&2
  exit 2
fi

mkdir -p "$download_dir" "$work_root" "$(dirname "$output")"
if [[ ! -f "$archive" ]]; then
  temporary="$archive.partial"
  rm -f "$temporary"
  curl --fail --location --retry 3 \
    "https://nodejs.org/dist/v$node_version/node-v$node_version-linux-arm64.tar.xz" \
    --output "$temporary"
  mv "$temporary" "$archive"
fi
printf '%s  %s\n' "$node_archive_sha256" "$archive" | sha256sum --check --status || {
  echo "Node.js archive integrity check failed." >&2
  exit 3
}

source_root="$work_root/source-root"
rm -rf "$source_root"
mkdir -p "$source_root"
tar --extract --xz --file "$archive" --directory "$source_root" --strip-components=1
printf '%s\n' \
  '#!/bin/sh' \
  'script_dir=${0%/*}' \
  'exec "$script_dir/node" "$@"' \
  >"$source_root/bin/tsx"
chmod 0755 "$source_root/bin/tsx"

node "$repository_root/tools/runtime/build-runtime-image.mjs" \
  --pack-id node-js \
  --version "$node_version" \
  --source "$source_root" \
  --output "$output" \
  --license "MIT"
