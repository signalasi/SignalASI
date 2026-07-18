#!/usr/bin/env bash
set -euo pipefail

buildroot_version="2026.05.1"
buildroot_sha256="ae7f706f087b9ae9083a10a587368dfbf53103c28bf81c2d690198dc4090cb58"
source_date_epoch="${SOURCE_DATE_EPOCH:-1781395200}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "$script_dir/../.." && pwd)"
external_tree="$repository_root/apps/android/runtime/buildroot-external"
work_root="${SIGNALASI_RUNTIME_BUILD_DIR:-$repository_root/build/runtime/linux-base}"
download_dir="${SIGNALASI_RUNTIME_DOWNLOAD_DIR:-$repository_root/build/runtime/downloads}"
archive="$download_dir/buildroot-$buildroot_version.tar.xz"
source_dir="$work_root/source"
output_dir="$work_root/output"
image_output="${1:-$work_root/linux-base.img}"

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "The linux-base image must be built on a Linux filesystem." >&2
  exit 2
fi

for command in curl sha256sum tar make install realpath; do
  command -v "$command" >/dev/null || {
    echo "Missing build dependency: $command" >&2
    exit 2
  }
done

repository_root="$(realpath -m "$repository_root")"
work_root="$(realpath -m "$work_root")"
download_dir="$(realpath -m "$download_dir")"
image_output="$(realpath -m "$image_output")"
archive="$download_dir/buildroot-$buildroot_version.tar.xz"
source_dir="$work_root/source"
output_dir="$work_root/output"
if [[ -z "$work_root" || "$work_root" == "/" || "$work_root" == "$repository_root" ]]; then
  echo "Refusing to use an unsafe runtime build directory." >&2
  exit 2
fi
if [[ "$source_dir" != "$work_root/source" || "$output_dir" != "$work_root/output" ]]; then
  echo "Runtime build paths are inconsistent." >&2
  exit 2
fi

mkdir -p "$download_dir" "$work_root" "$(dirname "$image_output")"
if [[ ! -f "$archive" ]]; then
  curl --fail --location --retry 3 \
    "https://buildroot.org/downloads/buildroot-$buildroot_version.tar.xz" \
    --output "$archive"
fi
printf '%s  %s\n' "$buildroot_sha256" "$archive" | sha256sum --check --status || {
  echo "Buildroot archive integrity check failed." >&2
  exit 3
}

rm -rf "$source_dir" "$output_dir"
mkdir -p "$source_dir" "$output_dir"
tar --extract --xz --file "$archive" --directory "$source_dir" --strip-components=1

export SOURCE_DATE_EPOCH="$source_date_epoch"
make -C "$source_dir" O="$output_dir" BR2_EXTERNAL="$external_tree" signalasi_aarch64_defconfig
make -C "$source_dir" O="$output_dir" BR2_EXTERNAL="$external_tree" \
  -j"${SIGNALASI_RUNTIME_BUILD_JOBS:-$(nproc)}"

install -m 0644 "$output_dir/images/Image" "$image_output"
sha256sum "$image_output"
