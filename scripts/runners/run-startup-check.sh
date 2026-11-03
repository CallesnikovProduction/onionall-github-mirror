#!/usr/bin/env bash
# Linux counterpart of run-startup-check.ps1. Fixed M0 project; do not run concurrently.
set -Eeuo pipefail
if [[ ${1:-} == --help ]]; then
    echo 'Run the empty-frontier M0 startup check. Requires built JAR and Docker. Retains .runtime/m0-*.log; no persistent volume. Do not run concurrently.'
    exit 0
fi
(( $# == 0 )) || { echo 'Unexpected arguments; use --help.' >&2; exit 2; }
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
cd -- "$root"
[[ -s target/onionall-brawler-0.1.0-SNAPSHOT.jar ]] || { echo 'Build the project first.' >&2; exit 1; }
docker info >/dev/null
compose=(docker compose -f compose.m0.yaml)
"${compose[@]}" config --quiet
mkdir -p .runtime
"${compose[@]}" pull
cleanup() {
    local status=$? cleanup_status=0
    trap - EXIT ERR INT TERM
    set +e
    "${compose[@]}" down --timeout 60 || cleanup_status=$?
    if (( cleanup_status != 0 )); then
        echo 'M0 cleanup failed; inspect the fixed M0 project.' >&2
        if (( status == 0 )); then status=$cleanup_status; fi
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
run_status=0
"${compose[@]}" up --abort-on-container-exit --exit-code-from crawler 2>&1 | tee .runtime/m0-console.log || run_status=$?
"${compose[@]}" logs --no-color > .runtime/m0-services.log 2>&1 || { if (( run_status == 0 )); then run_status=1; fi; }
if (( run_status != 0 )); then exit "$run_status"; fi
echo 'M0 process passed; inspect startup/RPC/shutdown logs before acceptance.'
