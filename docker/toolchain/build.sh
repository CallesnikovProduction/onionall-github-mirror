#!/usr/bin/env bash
# Invoked only in the toolchain image; no host toolchain or runtime state cleanup.
set -Eeuo pipefail
mode=${1:?build or test}
clean=${2:-}
[[ $mode == build || $mode == test ]] || exit 2
[[ -z $clean || $clean == --clean ]] || exit 2
repo="$PWD/.runtime/maven-linux-repository"
mkdir -p "$repo"
maven=(./mvnw -B "-Dmaven.repo.local=$repo")
if [[ ${ONIONALL_OFFLINE:-0} == 1 ]]; then maven+=(-o); fi
# The root POM and crawler POM share output; serialize and clean only build outputs.
if [[ $clean == --clean ]]; then
    "${maven[@]}" clean
    "${maven[@]}" -f frontier-server/pom.xml clean
fi
"${maven[@]}" verify
bash scripts/cli/build-cli.sh
java frontier-server/build-support/FrontierBuild.java prepare "$URLFRONTIER_BASE_JAR" frontier-server
"${maven[@]}" -f frontier-server/pom.xml verify
# The established shell path includes all seven conformance drivers.
if [[ $mode == test ]]; then
    bash frontier-server/build.sh --test
    python3 -B -m unittest discover -s test/scripts -v
fi
