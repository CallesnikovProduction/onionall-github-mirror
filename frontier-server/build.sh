#!/usr/bin/env bash
set -Eeuo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
module="$root/frontier-server"
if [[ -n ${JAVA_HOME:-} ]]; then
    if command -v cygpath >/dev/null 2>&1; then JAVA_HOME=$(cygpath -u "$JAVA_HOME"); fi
    export PATH="$JAVA_HOME/bin:$PATH"
fi
baseline=${URLFRONTIER_BASE_JAR:-$root/.runtime/autonomous-recovery/frontier-build-feasibility/onionall-brawler-m2-968f6efceb5d/urlfrontier-service.jar}
if command -v cygpath >/dev/null 2>&1; then baseline=$(cygpath -u "$baseline"); fi
[[ -f "$baseline" ]] || { echo 'Set URLFRONTIER_BASE_JAR to the pinned URLFrontier2.4 service JAR.' >&2; exit 1; }
baseline="$(cd -- "$(dirname -- "$baseline")" && pwd -P)/$(basename -- "$baseline")"
[[ $(sha256sum "$baseline" | cut -d' ' -f1) == ff1b774bf81343234e6f34878f8b7c9a826371b3e305e96bf0e204895e3562df ]] || { echo 'Baseline service JAR checksum mismatch.' >&2; exit 1; }
# Stage in a unique directory. Existing evidence and test databases are never removed.
mkdir -p "$module/target"
stage=$(mktemp -d "$module/target/build.XXXXXX")
# Relative argfile entries also work with native javac under Windows Git Bash.
cd -- "$module"
find src/main/java -name '*.java' -print | LC_ALL=C sort > "$stage/sources.txt"
javac --release 11 -cp "$baseline" -d "$stage/classes" @"$stage/sources.txt"
cp -- "$baseline" "$stage/urlfrontier-admission.jar"
jar --update --file "$stage/urlfrontier-admission.jar" --date=2026-11-03T00:00:00Z -C "$stage/classes" .
if [[ ${1:-} == --test ]]; then
    find src/test/java -name '*.java' -print | LC_ALL=C sort > "$stage/tests.txt"
    javac --release 11 -cp "$stage/classes:$baseline" -d "$stage/tests" @"$stage/tests.txt"
    classpath="$stage/classes:$stage/tests:$baseline"
    java --enable-native-access=ALL-UNNAMED -cp "$classpath" crawlercommons.urlfrontier.service.rocksdb.AdmissionConformance "$stage/conformance-db" > "$stage/conformance.log" 2>&1
    java --enable-native-access=ALL-UNNAMED -cp "$classpath" crawlercommons.urlfrontier.service.rocksdb.AdmissionMigrationConformance "$stage/migration" "$baseline" > "$stage/migration.log" 2>&1
    java --enable-native-access=ALL-UNNAMED -cp "$classpath" crawlercommons.urlfrontier.service.rocksdb.AdmissionShutdownConformance "$stage/shutdown-db" > "$stage/shutdown.log" 2>&1
    java --enable-native-access=ALL-UNNAMED -cp "$classpath" crawlercommons.urlfrontier.service.rocksdb.AdmissionRpcConformance "$stage/rpc-db" > "$stage/rpc.log" 2>&1
    java --enable-native-access=ALL-UNNAMED -cp "$classpath" crawlercommons.urlfrontier.service.rocksdb.AdmissionAmbiguousRpcConformance "$stage/ambiguous-rpc-db" > "$stage/ambiguous-rpc.log" 2>&1
    java --enable-native-access=ALL-UNNAMED -cp "$classpath" crawlercommons.urlfrontier.service.rocksdb.RecoveryCountersConformance "$stage/recovery-db" > "$stage/recovery.log" 2>&1
    java --enable-native-access=ALL-UNNAMED -cp "$classpath" crawlercommons.urlfrontier.service.rocksdb.AdmissionStateConformance "$stage/state-db" > "$stage/state.log" 2>&1
fi
cp -- "$stage/urlfrontier-admission.jar" "$module/target/urlfrontier-admission.jar"
sha256sum "$baseline" "$module/target/urlfrontier-admission.jar" > "$stage/artifacts.sha256"
printf '%s\n' "$stage" > "$module/target/latest-build.txt"
printf 'Candidate: %s\nEvidence: %s\n' "$module/target/urlfrontier-admission.jar" "$stage"
