#!/usr/bin/env bash
set -Eeuo pipefail
: "${M2_RUN_REL:?Use scripts/m2.sh}"
[[ ${M2_VERIFY_BEFORE_CRAWL:-0} == 0 || ${M2_VERIFY_BEFORE_CRAWL:-0} == 1 ]] || { echo 'M2_VERIFY_BEFORE_CRAWL must be 0 or 1' >&2; exit 2; }
# Verify from this exact container/client-address isolation context, not a
# different Compose probe. One finite series; any failure prevents Storm start.
if [[ ${M2_VERIFY_BEFORE_CRAWL:-0} == 1 ]]; then
    java -cp "/apache-storm/lib/*:/apache-storm/lib-common/*:$M2_RUN_REL/crawler-m2.jar" \
        dev.onionall.brawler.m2.RecoverySeriesProbe "${M2_SERIES_OUTPUT:-resumed-series.json}"
fi
exec storm local -c storm.log.dir=/tmp/storm-logs -c storm.local.dir=/tmp/storm-local -c dev.zookeeper.path=/tmp/storm-zookeeper "$M2_RUN_REL/crawler-m2.jar" org.apache.storm.flux.Flux "$M2_RUN_REL/${M2_TOPOLOGY:-topology.flux}" --local-ttl "${M2_CRAWL_TTL:-120}"
