#!/usr/bin/env bash
set -Eeuo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd -- "$root"
if [[ -n ${JAVA_HOME:-} ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
mkdir -p .runtime/downloads
maven=(mvn -o -B)
if [[ -d .runtime/maven-linux-repository ]]; then
    maven+=(-Dmaven.repo.local=.runtime/maven-linux-repository)
fi
"${maven[@]}" test-compile dependency:build-classpath \
    -Dmdep.outputFile=.runtime/downloads/smoke-classpath.txt \
    > .runtime/downloads/ahmia-build.log 2>&1 || {
    echo 'Smoke compilation failed; see .runtime/downloads/ahmia-build.log' >&2; exit 1;
}
java -cp "target/classes:$(cat .runtime/downloads/smoke-classpath.txt)" \
    dev.onionall.brawler.tools.OnionSmoke 2>&1 | tee .runtime/downloads/ahmia-smoke.log
