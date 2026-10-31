#!/usr/bin/env bash
set -Eeuo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd -- "$root"
python=${PYTHON:-python3}
for command in "$python" docker mvn javac jar; do
    command -v "$command" >/dev/null || { echo "Required command unavailable: $command" >&2; exit 1; }
done
project="onionall-brawler-m2-$("$python" -c 'import uuid; print(uuid.uuid4().hex[:12])')"
export M2_RUNTIME_USER="$(id -u):$(id -g)"
export M2_RUN_REL=".runtime/m2/$project"
export M2_RUN_DIR="$root/$M2_RUN_REL"
run_dir=$M2_RUN_DIR
mkdir -p -- "$run_dir/classes"
compose=(docker compose -p "$project" -f compose.m2.yaml)
cleanup_needed=false
failed_line=0
compose_log() {
    local name=$1
    shift
    "${compose[@]}" "$@" 2>&1 | tee "$run_dir/$name"
}
cleanup() {
    local code=$? cleanup_code=0 remaining='' network_count=1 volume=false
    trap - EXIT ERR INT TERM
    set +e
    if $cleanup_needed; then
        "${compose[@]}" logs --no-color > "$run_dir/services.log" 2>&1
        compose_log cleanup.log down --timeout 60 || cleanup_code=1
        remaining=$("${compose[@]}" ps -aq) || cleanup_code=1
        [[ -z $remaining ]] || cleanup_code=1
        network_count=$(docker network ls --filter "label=com.docker.compose.project=$project" --format '{{.ID}}' | wc -l) || cleanup_code=1
        docker volume inspect "${project}_frontier-data" > "$run_dir/retained-volume.json" 2>&1 && volume=true
        "$python" - "$run_dir" "$cleanup_code" "$remaining" "$network_count" "$volume" <<'PY'
import json, pathlib, sys
path, status, containers, networks, volume = sys.argv[1:]
result = dict(containers_removed=status == '0' and not containers,
              network_removed=networks.strip() == '0', volume_retained=volume == 'true')
(pathlib.Path(path)/'cleanup.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
PY
    fi
    "$python" - "$run_dir" "$code" "$failed_line" <<'PY'
import json, pathlib, sys
path, code, line = sys.argv[1:]
state = dict(exit_code=int(code), failed_line=int(line))
(pathlib.Path(path)/'runner-state.json').write_text(json.dumps(state), encoding='utf-8')
PY
    "$python" -B test/m2/bootstrap_evidence.py "$run_dir"
    "$python" -B test/m2/verify.py "$run_dir" > "$run_dir/verification.log" 2>&1
    local verification_code=$?
    cat "$run_dir/verification.log"
    (( code == 0 && cleanup_code == 0 && verification_code == 0 )) || code=1
    echo "M2 evidence: $run_dir (exit $code); persistent volumes and onion keys are never deleted."
    exit "$code"
}
trap cleanup EXIT
trap 'failed_line=$LINENO; echo "M2 command failed at line $LINENO" >&2' ERR
trap 'exit 130' INT
trap 'exit 143' TERM

docker info > "$run_dir/docker-info.log" 2>&1 || { echo 'Docker API unavailable; run bash scripts/m2.sh in a normal host terminal.' >&2; exit 1; }
maven=(mvn -B)
if [[ -n ${M2_MAVEN_REPOSITORY:-} ]]; then
    maven+=("-Dmaven.repo.local=$M2_MAVEN_REPOSITORY")
fi
"${maven[@]}" verify 2>&1 | tee "$run_dir/build.log"
# Include provided Storm classes when compiling test-only probes; they are not shaded.
"${maven[@]}" org.apache.maven.plugins:maven-dependency-plugin:3.10.0:build-classpath -Dmdep.outputFile="$run_dir/classpath.txt" 2>&1 | tee "$run_dir/dependencies.log"
probe_cp="target/onionall-brawler-0.1.0-SNAPSHOT.jar:$(cat "$run_dir/classpath.txt")"
javac --release 17 -cp "$probe_cp" -d "$run_dir/classes" test/m2/*.java
cp -- target/onionall-brawler-0.1.0-SNAPSHOT.jar "$run_dir/crawler-m2.jar"
"${compose[@]}" --profile tools config --quiet
"${compose[@]}" --profile tools config --format json > "$run_dir/compose.json"
compose_log build-images.log build
compose_log pull.log --profile tools pull --ignore-buildable
cleanup_needed=true
compose_log startup.log up -d --wait --wait-timeout 360 fixture onion tor frontier
"${compose[@]}" exec -T onion cat /var/lib/tor/hidden_service/hostname > "$run_dir/onion-hostname.txt"
"$python" -B test/m2/prepare.py "$run_dir"
jar --update --file "$run_dir/crawler-m2.jar" -C "$run_dir/classes" . -C "$run_dir/resources" .
docker image inspect onionall-brawler-tor:0.4.9.11-deb12u1 crawlercommons/url-frontier:2.4 storm:2.8.9-jre17 python:3.12-slim > "$run_dir/images.json"
fixture_id=$("${compose[@]}" ps -q fixture)
docker inspect "$fixture_id" > "$run_dir/fixture-inspect.json"
fixture_ip=$("$python" - "$run_dir/fixture-inspect.json" <<'PY'
import json, sys
networks=json.load(open(sys.argv[1]))[0]['NetworkSettings']['Networks']
if len(networks) != 1:
    raise SystemExit('Fixture must have exactly one isolated network')
print(next(iter(networks.values()))['IPAddress'])
PY
)
network_check() {
    local phase=$1 name="$project-network-$1" code=0
    shift
    compose_log "network-$phase.log" run --name "$name" --no-deps --user "$M2_RUNTIME_USER" --entrypoint python3 crawler /workspace/test/m2/network_probe.py "$fixture_ip" "$@" || code=$?
    docker inspect "$name" > "$run_dir/network-container-$phase.json" || return 1
    docker network inspect "${project}_crawler" > "$run_dir/network-config-$phase.json" || return 1
    docker rm "$name" > "$run_dir/network-cleanup-$phase.log" || return 1
    return "$code"
}
network_check initial
compose_log policy.log run --rm --no-deps --entrypoint java probe -cp '/apache-storm/lib/*:/evidence/crawler-m2.jar' dev.onionall.brawler.m2.TorPolicyProbe
compose_log ready.log run --rm --no-deps probe ready
"${compose[@]}" run --rm --no-deps probe snapshot empty.json
"$python" - "$run_dir/empty.json" <<'PY'
import json, sys
if json.load(open(sys.argv[1]))['entries']:
    raise SystemExit('New M2 frontier must be empty')
PY
"${compose[@]}" run --rm --no-deps probe seed root
compose_log crawl-first.log run --rm --no-deps crawler
printf '%s\n' '{"first_exit_code":0}' > "$run_dir/crawl-runtime.json"
"${compose[@]}" run --rm --no-deps probe snapshot crawled.json
"${compose[@]}" run --rm --no-deps probe seed pending
"${compose[@]}" run --rm --no-deps probe snapshot before.json
compose_log frontier-stop.log stop --timeout 30 frontier
compose_log frontier-before.log logs --no-color frontier
"${compose[@]}" rm -f frontier
"${compose[@]}" up -d --wait --wait-timeout 60 frontier
compose_log ready-recreated.log run --rm --no-deps probe ready
"${compose[@]}" run --rm --no-deps probe snapshot after.json
compose_log crawl-second.log run --rm --no-deps crawler
printf '%s\n' '{"first_exit_code":0,"second_exit_code":0}' > "$run_dir/crawl-runtime.json"
"${compose[@]}" run --rm --no-deps probe snapshot final.json
compose_log tor-stop.log stop --timeout 30 tor
network_check outage --outage
"${compose[@]}" up -d --wait --wait-timeout 360 tor
set +e
"$python" -B test/m2/scenarios.py "$project" "$run_dir"
scenario_code=$?
"$python" -B test/m2/outage_pipeline.py "$project" "$run_dir"
outage_code=$?
set -e
printf '{"scenario_exit_code":%s,"pipeline_exit_code":%s}\n' "$scenario_code" "$outage_code" > "$run_dir/scenario-exits.json"
# Run the existing regression unchanged, with its own unique project and volume.
set +e
bash scripts/m1.sh 2>&1 | tee "$run_dir/m1-regression.log"
m1_code=${PIPESTATUS[0]}
set -e
"$python" - "$run_dir" "$m1_code" <<'PY'
import json, pathlib, re, sys
path=pathlib.Path(sys.argv[1])
log=(path/'m1-regression.log').read_text()
matches=re.findall(r'Evidence: (.+)', log)
result=dict(exit_code=int(sys.argv[2]), evidence_directory=matches[-1].strip() if matches else '')
(path/'m1-regression.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
PY
