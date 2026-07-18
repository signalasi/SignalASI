#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
runtime_dir=$(CDPATH= cd -- "$script_dir/../../../.." && pwd)

install -D -m 0755 \
  "$runtime_dir/guest/signalasi_guest_agent.py" \
  "$TARGET_DIR/usr/libexec/signalasi_guest_agent.py"

rm -rf "$TARGET_DIR/usr/libexec/__pycache__"
