#!/usr/bin/env bash
# Compatibility entrypoint; use run-tor-acceptance.sh.
set -Eeuo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
exec bash "$script_dir/run-tor-acceptance.sh" "$@"
