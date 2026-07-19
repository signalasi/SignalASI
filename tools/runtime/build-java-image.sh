#!/usr/bin/env bash
set -euo pipefail

java_version="25.0.3+9"
java_archive_version="25.0.3_9"
java_archive_sha256="3e4287cb98870ba824ed698854bdc27cff984254caf66dd12cc291e7bfdde26b"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "$script_dir/../.." && pwd)"
source "$script_dir/runtime-download.sh"
work_root="${SIGNALASI_RUNTIME_BUILD_DIR:-$repository_root/build/runtime/java}"
download_dir="${SIGNALASI_RUNTIME_DOWNLOAD_DIR:-$repository_root/build/runtime/downloads}"
output="${1:-$repository_root/build/runtime/release/java-$java_archive_version-arm64-v8a.img}"

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "The Java image must be built on Linux." >&2
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
archive_name="OpenJDK25U-jdk_aarch64_linux_hotspot_$java_archive_version.tar.gz"
archive="$download_dir/$archive_name"
if [[ -z "$work_root" || "$work_root" == "/" || "$work_root" == "$repository_root" ]]; then
  echo "Refusing to use an unsafe runtime build directory." >&2
  exit 2
fi

mkdir -p "$download_dir" "$work_root" "$(dirname "$output")"
download_verified_runtime_input \
  "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.3%2B9/$archive_name" \
  "$archive" \
  "$java_archive_sha256" \
  "Temurin $java_version"

source_root="$work_root/source-root"
rm -rf "$source_root"
mkdir -p "$source_root"
tar --extract --gzip --file "$archive" --directory "$source_root" --strip-components=1

node "$repository_root/tools/runtime/build-runtime-image.mjs" \
  --pack-id java \
  --version "$java_version" \
  --source "$source_root" \
  --output "$output" \
  --license "GPL-2.0-only WITH Classpath-exception-2.0"
