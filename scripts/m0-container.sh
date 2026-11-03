#!/usr/bin/env bash
# Compatibility entrypoint; use run-startup-container.sh.
set -Eeuo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
exec bash "$script_dir/run-startup-container.sh" "$@"
