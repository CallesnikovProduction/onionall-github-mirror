#!/usr/bin/env bash
set -euo pipefail
: "${M1_RUN_REL:?Use scripts/m1.ps1}"
exec storm local "$M1_RUN_REL/crawler-m1.jar" org.apache.storm.flux.Flux "$M1_RUN_REL/topology.flux" --local-ttl 45
