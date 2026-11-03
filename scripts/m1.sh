#!/usr/bin/env bash
# Compatibility entrypoint; use run-controlled-crawl.sh.
set -Eeuo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
exec bash "$script_dir/run-controlled-crawl.sh" "$@"
