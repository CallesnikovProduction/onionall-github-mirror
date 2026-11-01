# M2 Tor gateway: implementation and verification

Status: **BLOCKED**, final bridge-enabled verification in progress.
The bridge-enabled client and native wrappers now supersede the direct-only
preflight setup below; see [bridge setup](m2-bridges.md) and
[the hardening audit](m2-hardening.md). Historical direct-bootstrap evidence
below remains preserved and must not be interpreted as current connectivity. See the verification summary
in each ignored `.runtime/m2/onionall-brawler-m2-<id>/verification.json`.
A true build or bootstrap check alone does not constitute M2 acceptance.

## Build and run on Omarchy

Prerequisites on the migration branch: JDK25+, Maven, Python3.12+, Bash, Docker Engine and Compose.
Storm3.1 uses JRE25; helpers compile with release25. The repository remains a single Maven module.
Historical Java17 observations below are not current runtime requirements.
See [the migration report](jvm25-storm31-migration.md) for scoped tests and blockers. Network access to Maven Central, Debian packages,
Docker registry and the public Tor network is necessary for a fresh run.

```sh
mvn -B clean verify
bash scripts/m2.sh
```

An optional `M2_MAVEN_REPOSITORY` chooses an alternate writable Maven cache.
The runner builds the pinned Tor image, creates an isolated Compose project,
waits for both Tor daemons to report bootstrap 100%, reads only the generated
public onion hostname, packages test-only probes/resources into a copied JAR,
and starts the existing StormCrawler/URLFrontier pipeline. It retains structured
snapshots, fixture requests, negative-scenario results, network probes and image
inspection under `.runtime/m2/`. Private onion keys never enter that directory;
they remain in the uniquely named `onion-state` Docker volume.

See [ADR 0001](adr/0001-tor-only-transport.md) for the inspected dependency
behavior and integration decisions. Both M0/M1 runners remain supported and
unchanged. Linux is the authoritative M2 integration platform; a native Windows
M2 runner has not been validated. Do not substitute WSL-specific paths or claim
Windows M2 support from Windows M1 acceptance.

## Lifecycle and safety

The runner installs EXIT/INT/TERM cleanup. It collects logs and brings down only
its project. It never passes `--volumes` or deletes retained frontier/Tor/onion
state. For manual cleanup after interrupted execution, use the project name and
paths printed by the runner:

```sh
M2_RUN_DIR="$PWD/.runtime/m2/<project>" M2_RUN_REL=".runtime/m2/<project>" docker compose -p '<project>' -f compose.m2.yaml down --timeout 60
```

Do not use `down -v`, prune, or delete onion keys as ordinary cleanup. Retained
volumes intentionally accumulate per run and require a separate deliberate
retention decision. Do not commit `.runtime/`, Docker data, logs, generated JARs,
keys or cookie authentication files. Raw Storm logs can include credentials and
environment details; share only sanitized summaries.

## Failure diagnosis

- Docker denied: check access in the host terminal; do not change daemon socket
  permissions or disable security controls as a workaround.
- Image/package failure: inspect build-images.log. The image installs official
  Debian Tor `0.4.9.11-0+deb12u1`; a removed package should fail rather than silently
  change version. Base Debian tag is versioned; resolved digests are captured.
- Bootstrap incomplete: inspect startup.log and services.log. A running Tor
  listener is insufficient. Both Tor clients need access to the public network.
  Do not relax network isolation to make bootstrap pass.
- Onion unreachable: bootstrap success does not guarantee published descriptor
  reachability; preserve failed evidence and retry only within bounded waits.
- ROBOTS_UNAVAILABLE: no page fetch is authorized when robots retrieval fails.
  This is a retryable fetch failure, not permission to ignore robots.
- SCOPE_REJECTED / PROXY_POLICY_VIOLATION: inspect the controlled test case;
  never broaden scope or permit metadata overrides to obtain a passing run.
- REQUEST_TIMEOUT / SOCKS_FAILURE / ONION_UNREACHABLE: inspect gateway health,
  scenario and frontier evidence; there is no direct fallback.

## Evidence requirements

Acceptance requires real StormCrawler HTML fetching via generated v3 onion,
robots, discovery/deduplication/redirect policy, document pacing, graceful
RocksDB recreation and outage/recovery scheduling, actual blocked direct egress
and DNS attempts, metadata bypass rejection, complete negative matrix, unchanged
M1 regression and successful cleanup. Local SOCKS wire tests prove client
behavior only. No summary field may be true without its supporting evidence.

Known implementation limit: resolving the configured SOCKS gateway hostname via
JVM InetSocketAddress uses platform DNS without an application-level deadline.
Destination onion validation and SOCKS requests do not resolve onion names
locally. Runtime namespace probes must confirm Docker DNS behavior separately.


## Observed verification — 2026-10-31

- Maven verify: BUILD SUCCESS, 15 JVM tests, no skips/failures. Tests exercise
  actual loopback SOCKS wire behavior, policy, robots, redirects, bounds and errors.
- Thirteen Python M2 gate regression tests pass. M1 Python: 31 pass, one optional
  localhost smoke skipped. All three Compose configurations validate.
- Pinned Tor image builds successfully. Both Tor configuration files validate on
  host Tor 0.4.9.14 (host syntax check, not pinned-image acceptance).
- Real M2 Docker attempt: `.runtime/m2/onionall-brawler-m2-cbb947ba4633/`.
  Both pinned Tor 0.4.9.11 daemons reached bootstrap 10%, then repeatedly logged
  relay TLS EOF/handshake errors and some connection timeouts. The 360-second
  startup gate failed. This establishes a bootstrap blocker in this environment;
  it does not establish the exact network/provider cause. No bridges or external
  proxies were added as an unreviewed workaround.
- cleanup.json: containers_removed=true, network_removed=true,
  volume_retained=true. tor-readiness.json summarizes actual logs without relay
  identifiers. verification.json remains m2_verified=false; missing downstream
  evidence is reported explicitly. No real onion fetch or M2 recovery is claimed.
- Actual crawler namespace preflight in isolation-preflight.json: direct internet
  socket denied, fixture hostname unavailable, local onion getaddrinfo denied,
  gateway SOCKS reachable. These observations do not replace the full gate.
- Separate complete M1 Docker regression passed against the new built JAR:
  `.runtime/m1/onionall-brawler-m1-f050361a4b55/`: all three flags true, six records,
  minimum document gap 2.002163184s, retained RocksDB and successful cleanup.
- Linux permission defect reproduced: root with all capabilities dropped cannot
  write host-owned evidence mode755. Host UID/GID mapping fixes it. Storm Flux
  dry-run under arbitrary UID12000 with no capabilities and writable /tmp
  directories passed; M2 mounts were narrowed to necessary inputs only.

The source was tightened after the blocked startup attempt (namespace inspection,
outage gate, UID/mount handling); those changes have local regression/config
checks, not a completed Tor integration run. Retry the full current runner on a
host/network where Tor bootstrap succeeds; do not edit JSON flags manually.

## Commit checkpoints

- 9b5938d — validated fixed SOCKS transport and scope policy, Kotlin compilation,
  15 JVM tests and architecture decision; pushed to Entire origin/main.
- 1b70246 — Docker Tor gateway/onion fixture and isolated networks; image built;
  pushed to Entire origin/main.
- 0c8fed8 — live integration/security/outage harness and Linux permissions/mounts;
  thirteen verifier tests pass; pushed to Entire origin/main.

The delivery documentation commit records a blocked milestone, not completion.


## Final dependency review

Read-only dependency:tree and shaded-JAR provenance checks passed. Kotlin compiler
and packaged stdlib are 2.3.21; packaged OkHttp is 5.4.0 and Okio is 3.17.0.
All 43,714 base classes inspected have classfile target Java17 or earlier. This
checks bytecode, not every API/runtime path. No linkage defect was observed in
15 JVM tests or the complete M1 Docker regression.

Existing convergence/shading risks remain: fat urlfrontier-client2.4 overlaps
transitive API/gRPC/Protobuf/Gson classes; selected Protobuf3.25.5 and Gson2.8.9
are below some transitive requests. BouncyCastle bcjmail1.85 coexists with
provider/pkix/util1.84, and commons-logging overlaps jcl-over-slf4j. Tika parser
paths are unverified. These are potential compatibility issues, not demonstrated
vulnerabilities or causes of the observed Tor TLS bootstrap failure. No broad
upgrade was made without evidence. A full vulnerability/container scan was not
performed. Docker tags and non-Tor Debian packages are not digest/snapshot locked.

Detailed reports stay ignored at `.runtime/m2/dependency-review/`: review.md,
tree.txt, shaded.json and kotlin-origin.json. New M2 TLS uses the JDK/OkHttp
standard trust and hostname checks; trust-all configuration is rejected.

## Recovery diagnostics — 2026-10-31

No architecture/Kotlin/dependency changes. Evidence: `.runtime/m2/recovery/`.
Original M2 relay TLS EOF/timeouts were rechecked against bounded comparisons:

- Pinned Tor0.4.9.11 on a normal Docker outbound bridge: bootstrap0/5/10/14,
  no100 within95s; relay TLS_ERROR/EOF. No internal crawler network involved.
- Native host Tor0.4.9.14/OpenSSL3.6.5 with a clean regular torrc and isolated
  data directory: bootstrap0/5/10, no100 within75s. Initial host attempts reading
  installed config/default User and /dev/null failed configuration validation;
  only the clean regular-file run is used for the comparison.
- Container TCP reaches tested relay endpoints96.9.98.90:443 and128.31.0.39:9201.
  Standard Python TLS rejects their non-public certificate chains, as expected
  for Tor relay certificates; separate diagnostic certificate retrieval shows
  random-name relay-style issuers. This is not proof of Tor link authentication
  or evidence that certificate validation should be disabled in the crawler.
- Container HTTPS example.com verifies successfully with default trust.
- Host NTP active, system clock synchronized; UTC/container timestamps agree.
- Full host nftables inspection was unavailable: netlink permission denied and
  sudo requires a password. No firewall/security settings were changed.

Strongest supported diagnosis: incomplete Tor link/bootstrap connectivity affects
both host and container, across Tor/OpenSSL versions. M2 Compose-only networking,
blanket TCP denial and obvious clock skew are not supported explanations. A
protocol-selective host/network restriction is plausible, but the exact censor,
firewall rule or upstream actor is NOT established. General TCP/TLS success is
not Tor bootstrap success. M2 remains BLOCKED; no full-stack retry was made.

### Lightweight preflight

The Bash runner now invokes a real Tor-only preflight before Maven and the full
stack. It uses the existing pinned image, no host mounts or exposed listeners,
a temporary data directory, a90s default deadline and unique container cleanup.
Only observed bootstrap100 permits continuation; this remains a necessary rather
than sufficient M2 gate. Result/logs live under each run's preflight directory.
The45s diagnostic invocation returned ready=false/progress10 and removed its
container. Sixteen M2 Python tests and Bash syntax/diff checks pass; Maven was
not rebuilt because no JVM source changed.

On a fresh host first build the existing image, then run preflight independently:

```sh
docker build -f docker/m2/Dockerfile -t onionall-brawler-tor:0.4.9.11-deb12u1 .
python3 -B test/m2/tor_preflight.py .runtime/m2/connectivity-preflight
```

Only after preflight succeeds run `bash scripts/m2.sh`. Do not repeatedly start
360s full-stack waits while preflight is failing. Diagnostic logs and temporary
native Tor data remain ignored; no runtime data is committed.

### Optional bridges

Tor documents bridges as a potential remedy for failed directory TLS handshakes:
https://support.torproject.org/tor-browser/encountering-issues/reading-tor-logs/
An obfs4/lyrebird client transport is a reasonable next controlled experiment,
not a verified fix. The current image does not include that transport. A proper
opt-in needs a pinned client package and private bridge configuration available
to BOTH Tor daemons, tested first through the same connectivity gate. Obtain
bridge lines through Tor's official channels; keep them in ignored restricted
runtime files/secrets, never source/config examples or command-line logs.
No bridge endpoint/credential was collected, embedded or enabled in this change;
no implicit fallback, TLS relaxation or crawler egress change was introduced.
