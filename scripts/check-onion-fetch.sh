#!/usr/bin/env bash
# Public entrypoint; implementation grouped by responsibility.
set -Eeuo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
exec bash "$script_dir/runners/check-onion-fetch.sh" "$@"
