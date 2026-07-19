#!/usr/bin/env bash
set -euo pipefail

playwright_version="1.61.0"
playwright_image="mcr.microsoft.com/playwright:v${playwright_version}-noble"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "$script_dir/../.." && pwd)"
work_root="${SIGNALASI_RUNTIME_BUILD_DIR:-$repository_root/build/runtime/browser-automation}"
output="${1:-$repository_root/build/runtime/release/browser-automation-playwright-$playwright_version-arm64-v8a.img}"

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "The browser automation image must be built on Linux." >&2
  exit 2
fi
for command in docker node realpath; do
  command -v "$command" >/dev/null || {
    echo "Missing build dependency: $command" >&2
    exit 2
  }
done

repository_root="$(realpath -m "$repository_root")"
work_root="$(realpath -m "$work_root")"
output="$(realpath -m "$output")"
source_root="$work_root/source-root"
if [[ -z "$work_root" || "$work_root" == "/" || "$work_root" == "$repository_root" ]]; then
  echo "Refusing to use an unsafe runtime build directory." >&2
  exit 2
fi

rm -rf "$source_root"
mkdir -p "$source_root/bin" "$source_root/lib" "$source_root/share/licenses/browser-automation" "$(dirname "$output")"

docker run --rm --platform linux/arm64 \
  -e PLAYWRIGHT_VERSION="$playwright_version" \
  -v "$source_root:/out" \
  "$playwright_image" \
  bash -euo pipefail -c '
    npm install --prefix /tmp/signalasi --omit=dev --ignore-scripts "playwright@${PLAYWRIGHT_VERSION}"
    mkdir -p /out/lib/node_modules /out/lib/runtime-libs /out/share
    cp -aL /tmp/signalasi/node_modules/playwright /tmp/signalasi/node_modules/playwright-core /out/lib/node_modules/
    cp -aL /ms-playwright /out/lib/ms-playwright
    [[ ! -d /etc/fonts ]] || cp -aL /etc/fonts /out/lib/fontconfig
    [[ ! -d /usr/share/fonts ]] || cp -aL /usr/share/fonts /out/share/fonts

    while IFS= read -r binary; do
      ldd "$binary" 2>/dev/null | awk '
        /=> \/[^ ]+/ { print $3 }
        /^\/[[:graph:]]+/ { print $1 }
      '
    done < <(find /ms-playwright -type f -perm /111 -print) | sort -u | while IFS= read -r library; do
      [[ -f "$library" ]] || continue
      case "$(basename "$library")" in
        ld-linux-*|libc.so.*) continue ;;
      esac
      destination="/out/lib/runtime-libs/$(basename "$library")"
      if [[ -e "$destination" ]]; then
        cmp -s "$library" "$destination" || {
          echo "Runtime library collision: $(basename "$library")" >&2
          exit 2
        }
      else
        cp -aL "$library" "$destination"
      fi
    done

    find /out -xdev -type f -perm /6000 -exec chmod a-s {} +
    chmod -R o-w /out
  '

cat >"$source_root/bin/signalasi-browser" <<'EOF'
#!/bin/sh
set -eu
pack_root=${0%/bin/signalasi-browser}
export NODE_PATH="$pack_root/lib/node_modules"
export PLAYWRIGHT_BROWSERS_PATH="$pack_root/lib/ms-playwright"
export LD_LIBRARY_PATH="$pack_root/lib/runtime-libs${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export FONTCONFIG_PATH="$pack_root/lib/fontconfig"
export FONTCONFIG_FILE="$pack_root/lib/fontconfig/fonts.conf"
exec node "$@"
EOF

cat >"$source_root/bin/playwright" <<'EOF'
#!/bin/sh
set -eu
pack_root=${0%/bin/playwright}
export NODE_PATH="$pack_root/lib/node_modules"
export PLAYWRIGHT_BROWSERS_PATH="$pack_root/lib/ms-playwright"
export LD_LIBRARY_PATH="$pack_root/lib/runtime-libs${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export FONTCONFIG_PATH="$pack_root/lib/fontconfig"
export FONTCONFIG_FILE="$pack_root/lib/fontconfig/fonts.conf"
exec node "$pack_root/lib/node_modules/playwright/cli.js" "$@"
EOF
chmod 0755 "$source_root/bin/signalasi-browser" "$source_root/bin/playwright"

cp "$source_root/lib/node_modules/playwright/LICENSE" \
  "$source_root/share/licenses/browser-automation/PLAYWRIGHT-LICENSE"
printf '%s\n' \
  "Playwright ${playwright_version} and its pinned Chromium distribution." \
  "Third-party notices are preserved inside the Playwright and browser directories." \
  >"$source_root/share/licenses/browser-automation/NOTICE"

node "$repository_root/tools/runtime/build-runtime-image.mjs" \
  --pack-id browser-automation \
  --version "$playwright_version" \
  --source "$source_root" \
  --output "$output" \
  --license "Apache-2.0 AND BSD-3-Clause AND LGPL-2.1-or-later" \
  --dependency node-js
