#!/usr/bin/env bash
set -Eeuo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd -- "$root"
python=${PYTHON:-python3}
javac=${JAVA_HOME:+$JAVA_HOME/bin/}javac
jar_tool=${JAVA_HOME:+$JAVA_HOME/bin/}jar
for tool in docker "$python" "$javac" "$jar_tool"; do
    command -v "$tool" >/dev/null || { echo "Required command unavailable: $tool" >&2; exit 1; }
done
[[ -s target/onionall-brawler-0.1.0-SNAPSHOT.jar ]] || { echo 'Build first: mvn clean verify' >&2; exit 1; }
project="onionall-brawler-m1-$("$python" -c 'import uuid; print(uuid.uuid4().hex[:12])')"
export M1_RUN_REL=".runtime/m1/$project"
export M1_RUN_DIR="$root/$M1_RUN_REL"
run_dir=$M1_RUN_DIR
mkdir -p -- "$run_dir/classes"
compose=(docker compose -p "$project" -f compose.m1.yaml)
cleanup_needed=false
verified=false

compose_log() {
    local log=$1
    shift
    "${compose[@]}" "$@" 2>&1 | tee "$run_dir/$log"
}
cleanup() {
    local status=$? cleanup_status=0 remaining
    trap - EXIT ERR INT TERM
    set +e
    if $cleanup_needed; then
        "${compose[@]}" ps -a --format json > "$run_dir/services-final.json"
        "${compose[@]}" logs --no-color > "$run_dir/services.log" 2>&1
        compose_log cleanup.log down --timeout 60 || cleanup_status=1
        remaining=$("${compose[@]}" ps -aq) || cleanup_status=1
        [[ -z $remaining ]] || cleanup_status=1
        docker volume ls --filter "label=com.docker.compose.project=$project" --format '{{.Name}}' > "$run_dir/retained-volumes.txt" || cleanup_status=1
        docker volume inspect "${project}_frontier-data" > "$run_dir/retained-volume.json" || cleanup_status=1
        if (( cleanup_status != 0 )); then
            echo "Cleanup could not be verified; inspect $project. Volume retained." >&2
            status=1
        elif $verified && (( status == 0 )); then
            "$python" - "$run_dir" "$project" "$M1_RUN_REL" <<'PY'
import json, pathlib, sys
path, project, relative = sys.argv[1:]
result = dict(assertions_passed=True, containers_removed=True, project=project,
              evidence=relative, volume_retained=project + '_frontier-data')
(pathlib.Path(path) / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
PY
            status=$?
        fi
    fi
    if (( status != 0 )); then
        echo "M1 failed (exit $status). Evidence: $run_dir; persistent volumes are never deleted." >&2
    else
        echo "M1 assertions and cleanup passed. Evidence: $run_dir"
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'echo "M1 command failed at line $LINENO: $BASH_COMMAND" >&2' ERR
trap 'exit 130' INT
trap 'exit 143' TERM

ready() {
    local phase=$1 probe_name="$project-probe-$1" ids
    docker network inspect "${project}_controlled" > "$run_dir/network-$phase.json"
    ids=$("${compose[@]}" ps -aq frontier fixture)
    local -a services=()
    mapfile -t services <<< "$ids"
    [[ ${#services[@]} == 2 && -n ${services[0]} && -n ${services[1]} ]]
    docker inspect "${services[@]}" > "$run_dir/services-$phase.json"
    "$python" - "$run_dir/services-$phase.json" "${project}_controlled" <<'PY'
import json, sys
services = json.load(open(sys.argv[1], encoding='utf-8'))
if len(services) != 2:
    raise SystemExit('M1 requires two services')
for service in services:
    name = service['Config']['Labels']['com.docker.compose.service']
    network = service['NetworkSettings']['Networks'].get(sys.argv[2], {})
    if not service['State']['Running'] or name not in network.get('Aliases', []):
        raise SystemExit(name + ' stopped or missing network alias')
PY
    local code=0
    compose_log "ready-$phase.log" run --name "$probe_name" --no-deps probe ready || code=$?
    docker inspect "$probe_name" > "$run_dir/probe-$phase.json" || return 1
    docker rm "$probe_name" > "$run_dir/probe-cleanup-$phase.log" 2>&1 || return 1
    return "$code"
}

docker info > "$run_dir/docker-info.log" 2>&1 || { echo "Docker API unavailable or denied; run this runner in a host terminal. See $run_dir/docker-info.log" >&2; exit 1; }
"$python" test/m1/prepare.py "$run_dir"
"$javac" --release 25 -cp target/onionall-brawler-0.1.0-SNAPSHOT.jar -d "$run_dir/classes" test/m1/*.java
cp -- target/onionall-brawler-0.1.0-SNAPSHOT.jar "$run_dir/crawler-m1.jar"
"$jar_tool" --update --file "$run_dir/crawler-m1.jar" -C "$run_dir/classes" . -C test/m1/resources .
"${compose[@]}" config --quiet
"${compose[@]}" --profile tools config --format json > "$run_dir/compose.json"
compose_log pull.log --profile tools pull
docker image inspect crawlercommons/url-frontier:2.4 storm:3.1.0-jre25 python:3.12-slim > "$run_dir/images.json"
cleanup_needed=true
compose_log startup.log up -d --wait --wait-timeout 60 frontier fixture
compose_log filter-check.log run --rm --no-deps probe filter-check
compose_log delay-check.log run --rm --no-deps probe delay-check
ready initial
"${compose[@]}" run --rm --no-deps probe snapshot empty.json
"$python" - "$run_dir/empty.json" <<'PY'
import json, sys
if json.load(open(sys.argv[1], encoding='utf-8-sig'))['entries'] != []:
    raise SystemExit('Fresh volume was not empty')
PY
"${compose[@]}" run --rm --no-deps probe seed root
compose_log crawl-first.log run --rm --no-deps crawler
printf '%s\n' '{"first_exit_code":0}' > "$run_dir/crawl-runtime.json"
"${compose[@]}" run --rm --no-deps probe snapshot crawled.json
"${compose[@]}" run --rm --no-deps probe seed pending
"${compose[@]}" run --rm --no-deps probe snapshot before.json
compose_log frontier-stop.log stop --timeout 30 frontier
compose_log frontier-before.log logs --no-color frontier
# Only the container is removed; the same named RocksDB volume is reopened.
"${compose[@]}" rm -f frontier
"${compose[@]}" up -d --wait --wait-timeout 60 frontier
ready recreated
"${compose[@]}" run --rm --no-deps probe snapshot after.json
compose_log crawl-second.log run --rm --no-deps crawler
printf '%s\n' '{"first_exit_code":0,"second_exit_code":0}' > "$run_dir/crawl-runtime.json"
"${compose[@]}" run --rm --no-deps probe snapshot final.json
"$python" test/m1/verify.py "$run_dir" 2>&1 | tee "$run_dir/verification.log"
verified=true
