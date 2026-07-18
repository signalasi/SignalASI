#!/usr/bin/env bash
set -euo pipefail

uv_version="0.11.29"
uv_archive_sha256="593d79a797ece3f1dfaaf3e0a973263422a135d9262c7dbc6cd75d9c11acc0b4"
apache_license_sha256="c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4"
mit_license_sha256="860e3d7a86b84e6a7012c7a635fc64df475cebc6cce34dfeb73a5982ec58176c"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "$script_dir/../.." && pwd)"
work_root="${SIGNALASI_RUNTIME_BUILD_DIR:-$repository_root/build/runtime/python-uv}"
download_dir="${SIGNALASI_RUNTIME_DOWNLOAD_DIR:-$repository_root/build/runtime/downloads}"
output="${1:-$repository_root/build/runtime/release/python-uv-$uv_version-arm64-v8a.img}"
archive="$download_dir/uv-aarch64-unknown-linux-musl-$uv_version.tar.gz"
apache_license="$download_dir/uv-$uv_version-LICENSE-APACHE"
mit_license="$download_dir/uv-$uv_version-LICENSE-MIT"

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "The python-uv image must be built on Linux." >&2
  exit 2
fi
for command in curl sha256sum tar install node realpath mv; do
  command -v "$command" >/dev/null || {
    echo "Missing build dependency: $command" >&2
    exit 2
  }
done

repository_root="$(realpath -m "$repository_root")"
work_root="$(realpath -m "$work_root")"
download_dir="$(realpath -m "$download_dir")"
output="$(realpath -m "$output")"
archive="$download_dir/uv-aarch64-unknown-linux-musl-$uv_version.tar.gz"
apache_license="$download_dir/uv-$uv_version-LICENSE-APACHE"
mit_license="$download_dir/uv-$uv_version-LICENSE-MIT"
if [[ -z "$work_root" || "$work_root" == "/" || "$work_root" == "$repository_root" ]]; then
  echo "Refusing to use an unsafe runtime build directory." >&2
  exit 2
fi

mkdir -p "$download_dir" "$work_root" "$(dirname "$output")"
download() {
  local url="$1"
  local destination="$2"
  if [[ ! -f "$destination" ]]; then
    local temporary="$destination.partial"
    rm -f "$temporary"
    curl --fail --location --retry 3 "$url" --output "$temporary"
    mv "$temporary" "$destination"
  fi
}
verify() {
  local expected="$1"
  local path="$2"
  printf '%s  %s\n' "$expected" "$path" | sha256sum --check --status || {
    echo "Downloaded runtime input failed integrity verification: $path" >&2
    exit 3
  }
}

download \
  "https://releases.astral.sh/github/uv/releases/download/$uv_version/uv-aarch64-unknown-linux-musl.tar.gz" \
  "$archive"
download "https://raw.githubusercontent.com/astral-sh/uv/$uv_version/LICENSE-APACHE" "$apache_license"
download "https://raw.githubusercontent.com/astral-sh/uv/$uv_version/LICENSE-MIT" "$mit_license"
verify "$uv_archive_sha256" "$archive"
verify "$apache_license_sha256" "$apache_license"
verify "$mit_license_sha256" "$mit_license"

source_root="$work_root/source-root"
extracted="$work_root/extracted"
rm -rf "$source_root" "$extracted"
mkdir -p "$source_root/bin" "$source_root/share/licenses/uv" "$extracted"
tar --extract --gzip --file "$archive" --directory "$extracted" --strip-components=1
install -m 0755 "$extracted/uv" "$source_root/bin/uv"
install -m 0755 "$extracted/uvx" "$source_root/bin/uvx"
printf '%s\n' '#!/bin/sh' 'exec /usr/bin/python3 "$@"' >"$source_root/bin/python3"
chmod 0755 "$source_root/bin/python3"
install -m 0644 "$apache_license" "$source_root/share/licenses/uv/LICENSE-APACHE"
install -m 0644 "$mit_license" "$source_root/share/licenses/uv/LICENSE-MIT"

node "$repository_root/tools/runtime/build-runtime-image.mjs" \
  --pack-id python-uv \
  --version "$uv_version" \
  --source "$source_root" \
  --output "$output" \
  --license "Apache-2.0 OR MIT"
