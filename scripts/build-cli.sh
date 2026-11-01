#!/usr/bin/env bash
set -Eeuo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd -- "$root"
if [[ -n ${JAVA_HOME:-} ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
command -v javac >/dev/null || { echo 'JDK 25 is required to build the CLI.' >&2; exit 69; }
[[ $(javac -version 2>&1) =~ javac\ ([0-9]+) ]] && (( BASH_REMATCH[1] >= 25 )) || { echo 'JDK 25 or newer is required.' >&2; exit 69; }
maven=(mvn -B)
if [[ ${ONIONALL_OFFLINE:-0} == 1 ]]; then maven+=(-o); fi
if [[ -d .runtime/maven-linux-repository ]]; then maven+=(-Dmaven.repo.local=.runtime/maven-linux-repository); fi
"${maven[@]}" verify dependency:build-classpath -Dmdep.outputFile=target/cli-classpath.txt
stage=$(mktemp -d "$root/target/onionall-stage.XXXXXX")
trap 'rm -rf -- "$stage"' EXIT
mkdir -p "$stage/lib" "$stage/bin" "$stage/classes/dev/onionall/brawler"
for package in cli tor tools; do
    [[ -d target/classes/dev/onionall/brawler/$package ]] || { echo "Missing compiled package: $package" >&2; exit 1; }
    cp -R -- "target/classes/dev/onionall/brawler/$package" "$stage/classes/dev/onionall/brawler/"
done
jar --create --file "$stage/lib/onionall-cli.jar" -C "$stage/classes" .
rm -rf -- "$stage/classes"
# Explicit runtime boundary: no Flux, Tika, URLFrontier, gRPC or test libraries.
# Config and Metadata/Protocol require the Storm API and StormCrawler core.
while IFS= read -r dependency || [[ -n $dependency ]]; do
    name=${dependency##*/}
    case "$name" in
        commons-cli-[0-9]*.jar|commons-lang3-[0-9]*.jar|commons-io-[0-9]*.jar|commons-codec-[0-9]*.jar|picocli-[0-9]*.jar|kotlin-stdlib-[0-9]*.jar|annotations-[0-9]*.jar|storm-client-[0-9]*.jar|storm-shaded-deps-[0-9]*.jar|stormcrawler-core-[0-9]*.jar|crawler-commons-[0-9]*.jar|okhttp-[0-9]*.jar|okhttp-jvm-[0-9]*.jar|okio-[0-9]*.jar|okio-jvm-[0-9]*.jar|jsoup-[0-9]*.jar|jackson-core-[0-9]*.jar|jackson-databind-[0-9]*.jar|jackson-annotations-[0-9]*.jar|slf4j-api-[0-9]*.jar|guava-[0-9]*.jar|failureaccess-[0-9]*.jar)
            cp -- "$dependency" "$stage/lib/$name" ;;
    esac
done < <(tr ':' '\n' < target/cli-classpath.txt)
[[ -n $(find "$stage/lib" -name 'picocli-*.jar' -print -quit) ]] || { echo 'Picocli dependency missing.' >&2; exit 1; }
install -m 755 scripts/cli-launcher.sh "$stage/bin/onionall"
printf '%s\n' 'onionall-cli-v1' > "$stage/.onionall-cli"
# The manifest identifies files owned by this distribution for safe uninstall.
(cd -- "$stage" && find bin lib -type f -print | LC_ALL=C sort) > "$stage/.files"
if [[ -e target/onionall ]]; then
    [[ -f target/onionall/.onionall-cli && ! -L target/onionall ]] || { echo 'Refusing to replace unrecognized target/onionall.' >&2; exit 1; }
    rm -rf -- target/onionall
fi
mv -- "$stage" target/onionall
trap - EXIT
printf 'CLI distribution: %s/target/onionall\n' "$root"
