#!/usr/bin/env bash
set -euo pipefail

go_version="1.26.5"
go_archive_sha256="fe4789e92b1f33358680864bbe8704289e7bb5fc207d80623c308935bd696d49"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "$script_dir/../.." && pwd)"
source "$script_dir/runtime-download.sh"
work_root="${SIGNALASI_RUNTIME_BUILD_DIR:-$repository_root/build/runtime/go}"
download_dir="${SIGNALASI_RUNTIME_DOWNLOAD_DIR:-$repository_root/build/runtime/downloads}"
output="${1:-$repository_root/build/runtime/release/go-$go_version-arm64-v8a.img}"

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "The Go image must be built on Linux." >&2
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
archive="$download_dir/go$go_version.linux-arm64.tar.gz"
if [[ -z "$work_root" || "$work_root" == "/" || "$work_root" == "$repository_root" ]]; then
  echo "Refusing to use an unsafe runtime build directory." >&2
  exit 2
fi

mkdir -p "$download_dir" "$work_root" "$(dirname "$output")"
download_verified_runtime_input \
  "https://go.dev/dl/go$go_version.linux-arm64.tar.gz" \
  "$archive" \
  "$go_archive_sha256" \
  "Go $go_version"

source_root="$work_root/source-root"
rm -rf "$source_root"
mkdir -p "$source_root"
tar --extract --gzip --file "$archive" --directory "$source_root" --strip-components=1

node "$repository_root/tools/runtime/build-runtime-image.mjs" \
  --pack-id go \
  --version "$go_version" \
  --source "$source_root" \
  --output "$output" \
  --license "BSD-3-Clause"
