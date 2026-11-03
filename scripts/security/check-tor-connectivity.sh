#!/usr/bin/env bash
set -Eeuo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
exec "${PYTHON:-python3}" "$root/test/m2/tor_preflight.py" "$root/.runtime/m2/connectivity-preflight" "$@"
