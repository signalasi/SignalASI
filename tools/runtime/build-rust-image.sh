#!/usr/bin/env bash
set -euo pipefail

rust_version="1.97.1"
rust_archive_sha256="9a7a2c336b4787f1b72f6bab7c35d5b7af2fd03cbd39b4fc721466a70d402a7d"
target="aarch64-unknown-linux-gnu"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "$script_dir/../.." && pwd)"
source "$script_dir/runtime-download.sh"
work_root="${SIGNALASI_RUNTIME_BUILD_DIR:-$repository_root/build/runtime/rust}"
download_dir="${SIGNALASI_RUNTIME_DOWNLOAD_DIR:-$repository_root/build/runtime/downloads}"
output="${1:-$repository_root/build/runtime/release/rust-$rust_version-arm64-v8a.img}"

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "The Rust image must be built on Linux." >&2
  exit 2
fi
for command in curl sha256sum tar node realpath mv bash; do
  command -v "$command" >/dev/null || {
    echo "Missing build dependency: $command" >&2
    exit 2
  }
done

repository_root="$(realpath -m "$repository_root")"
work_root="$(realpath -m "$work_root")"
download_dir="$(realpath -m "$download_dir")"
output="$(realpath -m "$output")"
archive="$download_dir/rust-$rust_version-$target.tar.xz"
if [[ -z "$work_root" || "$work_root" == "/" || "$work_root" == "$repository_root" ]]; then
  echo "Refusing to use an unsafe runtime build directory." >&2
  exit 2
fi

mkdir -p "$download_dir" "$work_root" "$(dirname "$output")"
download_verified_runtime_input \
  "https://static.rust-lang.org/dist/rust-$rust_version-$target.tar.xz" \
  "$archive" \
  "$rust_archive_sha256" \
  "Rust $rust_version"

source_root="$work_root/source-root"
extracted="$work_root/extracted"
rm -rf "$source_root" "$extracted"
mkdir -p "$source_root" "$extracted"
tar --extract --xz --file "$archive" --directory "$extracted" --strip-components=1
bash "$extracted/install.sh" \
  --prefix="$source_root" \
  --without=rust-docs \
  --disable-ldconfig

node "$repository_root/tools/runtime/build-runtime-image.mjs" \
  --pack-id rust \
  --version "$rust_version" \
  --source "$source_root" \
  --output "$output" \
  --dependency cpp \
  --license "Apache-2.0 OR MIT"
