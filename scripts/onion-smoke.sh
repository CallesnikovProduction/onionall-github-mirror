#!/usr/bin/env bash
# Compatibility entrypoint; use check-onion-fetch.sh.
set -Eeuo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
exec bash "$script_dir/check-onion-fetch.sh" "$@"
