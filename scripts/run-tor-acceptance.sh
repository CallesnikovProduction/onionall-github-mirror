#!/usr/bin/env bash
set -Eeuo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
exec "${PYTHON:-python3}" "$root/scripts/tor_acceptance_runner.py" "$@"
