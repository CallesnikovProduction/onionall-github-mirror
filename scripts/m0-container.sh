#!/usr/bin/env bash
set -euo pipefail

jar=target/onionall-brawler-0.1.0-SNAPSHOT.jar
test -s "$jar" || { echo "Build the project first: mvn clean package" >&2; exit 1; }

# Storm's CLI already requires Python. Wait for the frontier listener, not a
# fixed delay. This checks TCP readiness only; inspect the subsequent gRPC logs.
python3 - <<'PY'
import socket
import time

deadline = time.monotonic() + 60
while True:
    try:
        with socket.create_connection(("127.0.0.1", 7071), timeout=1):
            break
    except OSError:
        if time.monotonic() >= deadline:
            raise SystemExit("URLFrontier did not listen on localhost:7071 within 60s")
        time.sleep(1)
PY

echo "M0: running unchanged Flux topology against an empty temporary frontier"
exec storm local "$jar" org.apache.storm.flux.Flux crawler.flux --local-ttl 30
