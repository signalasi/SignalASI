#!/usr/bin/env bash
set -euo pipefail

qemu_version="10.2.1"
qemu_source_sha256="a3717477d8e2c84d630bfffbc20f6cd3293eb45aa1e6dac6d0cc27689991c9e1"
builder_commit="17c04d8e63e4744a91590854cac9ee44f143e5e2"
builder_archive_sha256="2ed1b8464d4ff468483612af549dea69c01372d9559d1d78504fb5068163a615"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "$script_dir/../.." && pwd)"
work_root="${SIGNALASI_QEMU_BUILD_DIR:-$repository_root/build/runtime/android-qemu}"
download_dir="${SIGNALASI_RUNTIME_DOWNLOAD_DIR:-$repository_root/build/runtime/downloads}"
builder_archive="$download_dir/termux-packages-$builder_commit.tar.gz"
builder_source="$work_root/termux-packages"
staging_directory="$work_root/staging"
jni_root="${SIGNALASI_ANDROID_JNI_DIR:-$repository_root/build/runtime/android-jni-libs}"
jni_directory="$jni_root/arm64-v8a"
asset_root="${SIGNALASI_ANDROID_RUNTIME_ASSET_DIR:-$repository_root/build/runtime/android-assets}"
asset_directory="$asset_root/runtime/qemu"
bundle_root="$work_root/bundle"
bundle_directory="$bundle_root/arm64-v8a"
bundle_manifest="$bundle_root/signalasi-qemu-bundle.json"
published_manifest="$jni_root/signalasi-qemu-bundle.json"

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "The Android QEMU engine must be built on Linux." >&2
  exit 2
fi
for command in curl sha256sum tar docker dpkg-deb patchelf llvm-readelf node realpath sed find install; do
  command -v "$command" >/dev/null || {
    echo "Missing build dependency: $command" >&2
    exit 2
  }
done

repository_root="$(realpath -m "$repository_root")"
work_root="$(realpath -m "$work_root")"
download_dir="$(realpath -m "$download_dir")"
jni_root="$(realpath -m "$jni_root")"
asset_root="$(realpath -m "$asset_root")"
builder_archive="$download_dir/termux-packages-$builder_commit.tar.gz"
builder_source="$work_root/termux-packages"
staging_directory="$work_root/staging"
jni_directory="$jni_root/arm64-v8a"
asset_directory="$asset_root/runtime/qemu"
bundle_root="$work_root/bundle"
bundle_directory="$bundle_root/arm64-v8a"
bundle_manifest="$bundle_root/signalasi-qemu-bundle.json"
published_manifest="$jni_root/signalasi-qemu-bundle.json"

require_generated_path() {
  local target="$1"
  local permitted_root="$2"
  if [[ -z "$target" || "$target" == "/" || "$target" == "$repository_root" ||
        "$target" != "$permitted_root"/* ]]; then
    echo "Refusing to use an unsafe generated directory: $target" >&2
    exit 2
  fi
}

reset_generated_directory() {
  local target="$1"
  local permitted_root="$2"
  require_generated_path "$target" "$permitted_root"
  if [[ -e "$target" ]]; then
    if [[ ! -f "$target/.signalasi-generated" ]]; then
      echo "Refusing to replace an unmarked directory: $target" >&2
      exit 2
    fi
    rm -rf "$target"
  fi
  mkdir -p "$target"
  : >"$target/.signalasi-generated"
}

mkdir -p "$download_dir" "$work_root" "$jni_root" "$asset_root"
if [[ ! -f "$builder_archive" ]]; then
  temporary_archive="$builder_archive.partial"
  rm -f "$temporary_archive"
  curl --fail --location --retry 3 \
    "https://github.com/termux/termux-packages/archive/$builder_commit.tar.gz" \
    --output "$temporary_archive"
  mv "$temporary_archive" "$builder_archive"
fi
printf '%s  %s\n' "$builder_archive_sha256" "$builder_archive" | sha256sum --check --status || {
  echo "Termux package-builder snapshot integrity check failed." >&2
  exit 3
}

reset_generated_directory "$builder_source" "$work_root"
tar --extract --gzip --file "$builder_archive" --directory "$builder_source" --strip-components=1

sed -i \
  's/^TERMUX_APP__PACKAGE_NAME="com\.termux"$/TERMUX_APP__PACKAGE_NAME="com.signalasi.chat"/' \
  "$builder_source/scripts/properties.sh"
grep -q '^TERMUX_APP__PACKAGE_NAME="com.signalasi.chat"$' "$builder_source/scripts/properties.sh" || {
  echo "Cannot configure the Android builder package namespace." >&2
  exit 3
}

upstream_package="$builder_source/packages/qemu-system-x86-64-headless"
signalasi_package="$builder_source/packages/signalasi-qemu"
cp -a "$upstream_package" "$signalasi_package"
find "$signalasi_package" -maxdepth 1 -type f -name '*.subpackage.sh' -delete
install -m 0644 \
  "$repository_root/apps/android/runtime/qemu/termux-build.sh" \
  "$signalasi_package/build.sh"
grep -q "TERMUX_PKG_VERSION=\"$qemu_version\"" "$signalasi_package/build.sh" || {
  echo "The QEMU package version does not match the release builder." >&2
  exit 3
}
grep -q "TERMUX_PKG_SHA256=$qemu_source_sha256" "$signalasi_package/build.sh" || {
  echo "The QEMU source digest does not match the release builder." >&2
  exit 3
}

(
  cd "$builder_source"
  CI=true \
    CONTAINER_NAME="signalasi-qemu-builder-$builder_commit" \
    scripts/run-docker.sh ./build-package.sh -a aarch64 signalasi-qemu
)

reset_generated_directory "$staging_directory" "$work_root"
mapfile -t packages < <(find "$builder_source/output" -maxdepth 1 -type f \
  \( -name '*_aarch64.deb' -o -name '*_all.deb' \) -print | sort)
if [[ ${#packages[@]} -eq 0 ]]; then
  echo "The Android package builder produced no Debian packages." >&2
  exit 3
fi
for package in "${packages[@]}"; do
  dpkg-deb --extract "$package" "$staging_directory"
done

prefix="$staging_directory/data/data/com.signalasi.chat/files/usr"
entry="$prefix/bin/qemu-system-aarch64"
if [[ ! -x "$entry" || ! -d "$prefix/lib" ]]; then
  echo "The built QEMU package is incomplete." >&2
  exit 3
fi

reset_generated_directory "$bundle_root" "$work_root"
node "$repository_root/tools/runtime/collect-android-elf-bundle.mjs" \
  --entry "$entry" \
  --output "$bundle_directory" \
  --manifest "$bundle_manifest" \
  --library-dir "$prefix/lib" \
  --readelf llvm-readelf \
  --patchelf patchelf \
  --qemu-version "$qemu_version" \
  --qemu-source-sha256 "$qemu_source_sha256" \
  --builder-commit "$builder_commit" \
  --builder-archive-sha256 "$builder_archive_sha256"

reset_generated_directory "$jni_directory" "$jni_root"
cp -a "$bundle_directory/." "$jni_directory/"
install -m 0644 "$bundle_manifest" "$published_manifest"
reset_generated_directory "$asset_directory" "$asset_root"
install -m 0644 "$repository_root/apps/android/runtime/qemu/NOTICE.md" "$asset_directory/NOTICE.md"
install -m 0644 "$bundle_manifest" "$asset_directory/bundle.json"

node "$repository_root/tools/runtime/normalize-android-elf-bundle.mjs" \
  --jni-root "$jni_root" \
  --asset-root "$asset_root" \
  --readelf llvm-readelf \
  --patchelf patchelf

sha256sum "$jni_directory/libsignalasi_qemu.so"
printf 'Android JNI bundle: %s\nRuntime assets: %s\n' "$jni_root" "$asset_root"
