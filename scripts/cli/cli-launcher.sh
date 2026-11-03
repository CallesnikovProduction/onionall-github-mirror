#!/usr/bin/env bash
set -Eeuo pipefail
launcher=$(readlink -f -- "${BASH_SOURCE[0]}")
app=$(cd -- "$(dirname -- "$launcher")/.." && pwd -P)
java_bin=${JAVA_HOME:+$JAVA_HOME/bin/}java
command -v "$java_bin" >/dev/null || { echo 'onionall: Java 25 is required; set JAVA_HOME or install a JDK/JRE 25.' >&2; exit 69; }
version=$("$java_bin" -version 2>&1)
if [[ ! $version =~ version\ \"([0-9]+) ]] || (( BASH_REMATCH[1] < 25 )); then
    echo 'onionall: Java 25 or newer is required; select it with JAVA_HOME.' >&2
    exit 69
fi
exec "$java_bin" -Dslf4j.internal.verbosity=ERROR -cp "$app/lib/*" dev.onionall.brawler.cli.Onionall "$@"
