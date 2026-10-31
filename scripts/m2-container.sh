#!/usr/bin/env bash
set -Eeuo pipefail
: "${M2_RUN_REL:?Use scripts/m2.sh}"
exec storm local -c storm.log.dir=/tmp/storm-logs -c storm.local.dir=/tmp/storm-local -c dev.zookeeper.path=/tmp/storm-zookeeper "$M2_RUN_REL/crawler-m2.jar" org.apache.storm.flux.Flux "$M2_RUN_REL/${M2_TOPOLOGY:-topology.flux}" --local-ttl "${M2_CRAWL_TTL:-120}"
