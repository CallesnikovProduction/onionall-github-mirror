#!/usr/bin/env bash
set -Eeuo pipefail

if [[ ${1:-} == --help ]]; then
    printf '%s\n' 'Build the pinned Nimbus lifecycle image and run its regressions. Settings: JAVA_HOME, STORM_LIFECYCLE_REVISION, STORM_BASE, STORM_LIFECYCLE_IMAGE, STORM_NIMBUS_SOURCE. Requires Docker; no user volumes.'
    exit 0
fi
(( $# == 0 )) || { echo 'Unexpected arguments; use --help.' >&2; exit 2; }
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
cd -- "$root"
: "${JAVA_HOME:?Select JDK25 via JAVA_HOME}"
export PATH="$JAVA_HOME/bin:$PATH"
for tool in java javac jar docker python3 patch sha256sum; do command -v "$tool" >/dev/null; done
[[ $(javac -version 2>&1) =~ javac\ 25[.] ]] || { echo 'Select JDK25 for the pinned Storm build.' >&2; exit 2; }
revision=${STORM_LIFECYCLE_REVISION:-2}
[[ $revision == 1 || $revision == 2 ]] || { echo 'STORM_LIFECYCLE_REVISION must be 1 or 2' >&2; exit 2; }
build="$root/.runtime/storm-lifecycle-build-cleanup$revision"
mkdir -p "$build/libs" "$build/original" "$build/classes" "$build/context" "$build/probe"
base=${STORM_BASE:-storm:3.1.0-jre25}
candidate=${STORM_LIFECYCLE_IMAGE:-onionall-storm:3.1.0-jre25-cleanup$revision}
name="onionall-brawler-m1-inspect-$(python3 -c 'import uuid;print(uuid.uuid4().hex[:12])')"
trap 'docker rm -f "$name" >/dev/null 2>&1 || true' EXIT
# No container network, mount, persistent state, or host configuration changes.
if [[ -n ${STORM_LIBRARIES:-} ]]; then
    cp -R -- "$STORM_LIBRARIES/lib" "$STORM_LIBRARIES/lib-common" "$build/libs/"
else
    docker image inspect "$base" > "$build/base-image.json"
    docker run --rm --name "$name" --network none --entrypoint tar "$base" -C /apache-storm -cf - lib lib-common | tar -xf - -C "$build/libs"
fi
source_sha=0154b08f750c29ef7c049c3560c50520213fe256bcae2acf566d44b702d27460
jar_sha=68c542091e40d0d974601fe852787965f2723e7c45bbd6fd981c48ff3307ef26
source_file="$build/original/Nimbus.java"
if [[ -n ${STORM_NIMBUS_SOURCE:-} ]]; then
    cp -- "$STORM_NIMBUS_SOURCE" "$source_file"
elif [[ ! -f $source_file ]]; then
    curl --fail --show-error --location --max-time 30 \
      https://raw.githubusercontent.com/apache/storm/v3.1.0/storm-server/src/main/java/org/apache/storm/daemon/nimbus/Nimbus.java \
      -o "$source_file"
fi
printf '%s  %s\n' "$source_sha" "$source_file" | sha256sum --check --status
printf '%s  %s\n' "$jar_sha" "$build/libs/lib/storm-server-3.1.0.jar" | sha256sum --check --status
cp -- "$source_file" "$build/Nimbus.java"
patch --batch --fuzz=0 "$build/Nimbus.java" < docker/storm-lifecycle/nimbus-cleanup.patch
if [[ $revision == 2 ]]; then
    patch --batch --fuzz=0 "$build/Nimbus.java" < docker/storm-lifecycle/nimbus-cleanup-drain.patch
fi
javac --release 25 -cp "$build/libs/lib/*:$build/libs/lib-common/*" -d "$build/classes" "$build/Nimbus.java"
# Remove only generated outputs owned by this build, never external runtime state.
cp -- "$build/libs/lib/storm-server-3.1.0.jar" "$build/context/storm-server-3.1.0.jar"
jar --update --date=2026-11-02T00:00:00Z --file "$build/context/storm-server-3.1.0.jar" -C "$build/classes" .
javac --release 25 -cp "$build/libs/lib/*:$build/libs/lib-common/*" -d "$build/probe" test/storm-lifecycle/NimbusCleanupLockProbe.java test/storm-lifecycle/NimbusCleanupShutdownProbe.java
# The same real Nimbus lock probe must fail against baseline and pass the backport.
if java -cp "$build/probe:$build/libs/lib/*:$build/libs/lib-common/*" NimbusCleanupLockProbe > "$build/probe-baseline.log" 2>&1; then
    echo 'Baseline unexpectedly serialized cleanup; refusing redundant backport.' >&2; exit 1
fi
python3 - "$build/probe-baseline.log" <<'PY_CHECK'
from pathlib import Path
import sys
assert 'Real Nimbus cleanup entered while submission publication lock was held' in Path(sys.argv[1]).read_text(), 'Baseline failed for an unrelated reason'
PY_CHECK
java -cp "$build/probe:$build/context/storm-server-3.1.0.jar:$build/libs/lib/*:$build/libs/lib-common/*" NimbusCleanupLockProbe | tee "$build/probe-patched.log"
if [[ $revision == 2 ]]; then
if java -cp "$build/probe:$build/libs/lib/*:$build/libs/lib-common/*" NimbusCleanupShutdownProbe legacy > "$build/shutdown-baseline.log" 2>&1; then
    echo 'Baseline unexpectedly drained active cleanup; refusing false shutdown proof.' >&2; exit 1
fi
python3 - "$build/shutdown-baseline.log" <<'PY_CHECK'
from pathlib import Path
import sys
assert 'Active Nimbus cleanup was interrupted instead of drained' in Path(sys.argv[1]).read_text(), 'Shutdown baseline failed for an unrelated reason'
PY_CHECK
java -cp "$build/probe:$build/context/storm-server-3.1.0.jar:$build/libs/lib/*:$build/libs/lib-common/*" NimbusCleanupShutdownProbe | tee "$build/shutdown-patched.log"
fi
if [[ ${STORM_PATCH_ONLY:-0} == 1 ]]; then
    printf 'Patched Storm JAR and lock/drain probes passed: %s\n' "$build/context/storm-server-3.1.0.jar"
    exit 0
fi
cp docker/storm-lifecycle/Dockerfile "$build/context/Dockerfile"
docker build --network none --pull=false --build-arg "STORM_BASE=$base" --build-arg "LIFECYCLE_REVISION=$revision" -t "$candidate" "$build/context"
docker image inspect "$candidate" > "$build/candidate-image.json"
sha256sum "$source_file" "$build/libs/lib/storm-server-3.1.0.jar" "$build/context/storm-server-3.1.0.jar" > "$build/integrity.txt"
printf 'Candidate built: %s\nEvidence: %s\n' "$candidate" "$build"
