#!/usr/bin/env bash
set -euo pipefail

ffmpeg_version="8.1.2"
ffmpeg_archive_sha256="464beb5e7bf0c311e68b45ae2f04e9cc2af88851abb4082231742a74d97b524c"
zig_version="0.16.0"
zig_archive_sha256="70e49664a74374b48b51e6f3fdfbf437f6395d42509050588bd49abe52ba3d00"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "$script_dir/../.." && pwd)"
source "$script_dir/runtime-download.sh"
work_root="${SIGNALASI_RUNTIME_BUILD_DIR:-$repository_root/build/runtime/ffmpeg}"
download_dir="${SIGNALASI_RUNTIME_DOWNLOAD_DIR:-$repository_root/build/runtime/downloads}"
output="${1:-$repository_root/build/runtime/release/ffmpeg-$ffmpeg_version-arm64-v8a.img}"

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "The FFmpeg image must be built on Linux." >&2
  exit 2
fi
for command in curl sha256sum tar node realpath mv make nproc install llvm-strip; do
  command -v "$command" >/dev/null || {
    echo "Missing build dependency: $command" >&2
    exit 2
  }
done

repository_root="$(realpath -m "$repository_root")"
work_root="$(realpath -m "$work_root")"
download_dir="$(realpath -m "$download_dir")"
output="$(realpath -m "$output")"
ffmpeg_archive="$download_dir/ffmpeg-$ffmpeg_version.tar.xz"
zig_archive="$download_dir/zig-x86_64-linux-$zig_version.tar.xz"
if [[ -z "$work_root" || "$work_root" == "/" || "$work_root" == "$repository_root" ]]; then
  echo "Refusing to use an unsafe runtime build directory." >&2
  exit 2
fi

mkdir -p "$download_dir" "$work_root" "$(dirname "$output")"
download_verified_runtime_input \
  "https://ffmpeg.org/releases/ffmpeg-$ffmpeg_version.tar.xz" \
  "$ffmpeg_archive" \
  "$ffmpeg_archive_sha256" \
  "FFmpeg $ffmpeg_version"
download_verified_runtime_input \
  "https://ziglang.org/download/$zig_version/zig-x86_64-linux-$zig_version.tar.xz" \
  "$zig_archive" \
  "$zig_archive_sha256" \
  "Zig host compiler $zig_version"

source_tree="$work_root/ffmpeg-source"
zig_root="$work_root/zig-host"
install_root="$work_root/install-root"
source_root="$work_root/source-root"
rm -rf "$source_tree" "$zig_root" "$install_root" "$source_root"
mkdir -p "$source_tree" "$zig_root" "$install_root" "$source_root/bin" "$source_root/share/licenses/ffmpeg"
tar --extract --xz --file "$ffmpeg_archive" --directory "$source_tree" --strip-components=1
tar --extract --xz --file "$zig_archive" --directory "$zig_root" --strip-components=1

pushd "$source_tree" >/dev/null
./configure \
  --prefix="$install_root" \
  --arch=aarch64 \
  --target-os=linux \
  --enable-cross-compile \
  --cc="$zig_root/zig cc -target aarch64-linux-musl" \
  --cxx="$zig_root/zig c++ -target aarch64-linux-musl" \
  --ar="$zig_root/zig ar" \
  --ranlib="$zig_root/zig ranlib" \
  --strip=llvm-strip \
  --pkg-config=false \
  --disable-autodetect \
  --disable-doc \
  --disable-debug \
  --disable-ffplay \
  --disable-network \
  --disable-shared \
  --enable-static \
  --extra-ldflags=-static
make -j"$(nproc)"
make install
popd >/dev/null

install -m 0755 "$install_root/bin/ffmpeg" "$source_root/bin/ffmpeg"
install -m 0755 "$install_root/bin/ffprobe" "$source_root/bin/ffprobe"
install -m 0644 "$source_tree/COPYING.LGPLv2.1" "$source_root/share/licenses/ffmpeg/COPYING.LGPLv2.1"
install -m 0644 "$source_tree/COPYING.LGPLv3" "$source_root/share/licenses/ffmpeg/COPYING.LGPLv3"

node "$repository_root/tools/runtime/build-runtime-image.mjs" \
  --pack-id ffmpeg \
  --version "$ffmpeg_version" \
  --source "$source_root" \
  --output "$output" \
  --license "LGPL-2.1-or-later"
