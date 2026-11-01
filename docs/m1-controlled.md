# M1 controlled crawl вЂ” implementation and verification status

**Status on 2026-10-31: M1 accepted on Windows (df35366e0994) and Omarchy Linux (e55203e97536); both user-run Docker tests passed offline verifier rechecks.**
M0 remains accepted in commit `164c2ba`. Nothing here establishes Tor transport,
onion discovery, a search index, crash recovery or a distributed Storm cluster.

## Scope

The only site is the repository-owned synthetic `http://fixture:8080`.
No seeds are read from the user or from a public service. No downloaded material
is retained: only synthetic fixture requests, frontier records and runtime logs.

| Route | Purpose |
| --- | --- |
| / | Seed, links to /a twice (including a fragment), robots-denied /blocked and /missing |
| /a | Links to /b and back to the root |
| /b | Link back to /a, testing cycle/deduplication behavior |
| /blocked | Returns synthetic content if requested; robots must prevent the request |
| /missing | Intentional HTTP 404 |
| /pending | Injected only after the first crawler stops, testing restart recovery |
| /robots.txt | Allows indexing, disallows /blocked, requests two-second spacing |
| /health | Docker readiness probe, excluded from crawl request records |

The root also links to `frontier:7071` and `outside.invalid` as negative filter
cases. They must be rejected, not fetched. Test-only filters allow precisely
the six fixture page paths; OkHttp's include rule permits only `172.30.71.10`.
An internal Docker network has no published ports. These are narrow synthetic
test boundaries, not a production network isolation or redirect test.

M0 files `compose.m0.yaml`, `crawler.flux` and `crawler-conf.yaml` are unchanged.
The M1 helper preserves component IDs, parallelism and stream wiring, adds a test
configuration include and substitutes the stock SimpleFetcherBolt for the M1
fetcher only. A separate copy of the shaded JAR receives test resources
and Java probe/check classes. The original JAR is not modified.

## Host run

Prerequisites: Docker Engine with Linux containers, Docker Compose, Bash (Linux) or PowerShell (Windows),
Python 3.12 or newer, a JDK 25 or newer (compiling with `--release 25`), and the
current built main JAR. The Python fixture image is `python:3.12-slim`; the
first host attempt pulled and started it. The runner records image inspection data.

From the repository root in normal host PowerShell:

```powershell
./scripts/m1.ps1 -JdkHome "$env:ProgramFiles/Java/jdk-25"
```

Two bounded 45-second crawler runs plus setup/teardown are expected. Every run
has a unique Compose project and new RocksDB named volume. The fixed subnet
`172.30.71.0/24` must be free. If Docker reports a subnet overlap, stop and
review the test network and matching fixture-IP include together; the helper
does not delete unrelated networks or relax the network boundary.

The helper fails on nonzero Docker commands or failed assertions. It performs:

1. Java test-probe compilation, test JAR assembly and topology derivation.
2. Compose validation, image pull and image inspection capture.
3. Prepare volume ownership, start frontier/fixture with TCP/HTTP healthchecks,
   run actual URLFilters checks, inspect network membership/aliases, then run
   bounded DNS/TCP/HTTP preflight and gRPC readiness in the same probe JVM.
4. Empty-frontier assertion, root seed injection and first bounded crawl.
5. A read-only snapshot of processed URLs, then insertion of /pending.
6. Snapshot before stopping frontier; stop/remove only the frontier container.
7. Recreate frontier using the same named volume, then take another snapshot.
8. Second bounded crawl, final snapshot and evidence assertions.
9. Capture logs, down the project without deleting volumes, and check no project
   containers remain. Record the retained volume name.

No URLs are leased by the snapshot operation: it uses ListURLs, not GetURLs.
Seed writes require a successful URLFrontier acknowledgment and accept only
the two hard-coded synthetic URLs. Each gRPC operation has a deadline.

## Evidence and acceptance

Evidence lives under `.runtime/m1/onionall-brawler-m1-<id>/`. Do not commit this
directory: Storm logs may contain temporary credentials and environment dumps.

| File | Meaning |
| --- | --- |
| images.json | Local image IDs/tags/digests returned by Docker inspection |
| compose.json | Resolved service/network configuration |
| network-initial.json, network-recreated.json | Controlled network endpoints before each readiness probe |
| services-initial.json, services-recreated.json | Service state, network membership and aliases |
| probe-initial.json, probe-recreated.json | Readiness probe container inspection before removal |
| ready-initial.log, ready-recreated.log | Resolver files, DNS answers, TCP/HTTP checks and gRPC readiness |
| crawl-runtime.json | Exit codes from both crawler commands; required for full verification |
| services-final.json | Container state immediately before teardown |
| requests.jsonl | Fixture path, status, monotonic request timings, size, user agent |
| crawl-first.log, crawl-second.log | Crawler output, parsed stdout markers, metrics and shutdown |
| empty.json, crawled.json | Fresh frontier and first-crawl records |
| before.json, after.json | URL kinds, metadata and scheduled dates across recreation |
| final.json | Pending work processed after recreation |
| frontier-before.log, services.log | Backend close/reopen and service output |
| verification.json | Assertions passed; this file alone does not prove cleanup |
| cleanup.log, retained-volumes.txt | Teardown output and deliberately retained synthetic data |
| result.json | Written only after assertions and container cleanup pass |

The verifier requires exactly five initial records; successful pages must be
scheduled in the future. The HTTP 404 must be observed by the fixture and the
fetcher, and persist a first FETCH_ERROR counter, processing date and the
configured 120-minute retry schedule. Robots denial requires a crawler denial
record, no HTTP request and a completed frontier marker (`known`, refetch 0).
The pending record must remain unprocessed and immediately due across recreation
(ListURLs returns `known`, empty metadata and an insertion-time refetch date). Every
record's kind, metadata and refetch date must survive unchanged. A second crawl
must process /pending without re-fetching earlier pages.

The fixture must observe each allowed page once, exactly two robots requests,
no blocked or unexpected fixture paths and at least 1.8 seconds between completed
document requests within each crawler process (two-second configured delay,
0.2-second tolerance). Parsed content markers, real metrics records, absence of
ERROR/FATAL records and LocalCluster shutdown are also required.

Fixture logs cannot observe traffic sent to another host. Negative-host URL
filter checks and exact frontier contents supply additional scope evidence;
there is no packet capture or general outbound-isolation verification here.

After the host run, inspect warnings and backend close/recovery logs before
accepting M1. The verifier is deliberately strict: do not weaken assertions
merely to obtain a pass. A failure leaves evidence and the named volume for
investigation. Successful verification covers graceful container recreation,
not an abrupt crash, data corruption or production-scale load.

The runner never uses `down -v`. Volumes remain after container/network cleanup.
Delete only the exact synthetic volume named in `retained-volumes.txt` or
`result.json`, after evidence review. Re-running the script uses a fresh project
and does not reuse or delete earlier volumes.

## Checks actually performed by the agent

| Check | Observed result |
| --- | --- |
| M0 commit | git log shows 164c2ba, test(infra): verify stormcrawler m0 baseline |
| Java probes | Recompiled after the fix against the existing shaded JAR with JDK 17 / --release 17 |
| Connectivity regression checks | PASS: success result, DNS error diagnostic and bounded uninterruptible lookup; also passed from assembled test JAR |
| Actual StormCrawler filters | Allowed fixture cases/fragment normalization passed; 11 out-of-scope authority/path/scheme cases rejected |
| Test JAR assembly | Passed; packaged filter-check and Flux dry-run passed; original main JAR unchanged |
| Generated Flux graph | Dry-run passed; same component IDs/streams with M1 include and stock SimpleFetcherBolt substitution |
| Compose | Config validation passed with test environment variables |
| PowerShell | Parser accepted scripts/m1.ps1 |
| Shell syntax execution | BLOCKED: Git Bash cannot create its Windows shared object (0xC0000022); Linux init execution requires host Docker |
| Python local suite (2026-10-31) | 24 passed, 1 optional HTTP integration test skipped; includes actual short-gap regression, explicit process boundaries and HTTP 404 checks |
| Local HTTP integration attempt | Failed with WinError 10013 due to restricted socket access; not counted as passed |
| Docker execution | API permission denied; M1 script preflight rejects it before any containers start |

The current Flux dry-run exited 0 after supplying the cached Storm client and
shaded dependencies (Storm is a provided dependency). It reported missing local
logging providers; this is not a clean runtime log or evidence of crawling.

Java deadline/diagnostic regression check after compilation:

```powershell
& "$env:ProgramFiles/Java/jdk-17/bin/java.exe" -cp ".runtime/m1/local-check/classes" dev.onionall.brawler.m1.ConnectivityCheckTest
```

Default local checks (no Docker or sockets):

```powershell
python -B -m unittest discover -s test/m1 -p 'test_*.py' -v
```

Optional fixture HTTP smoke on a host that permits loopback sockets:

```powershell
$env:M1_HTTP_SMOKE = '1'
python -B -m unittest discover -s test/m1 -p 'test_*.py' -v
Remove-Item Env:M1_HTTP_SMOKE
```

The unit suite uses synthetic evidence to exercise verifier failures (lost
work, changed schedules, duplicate fetches, robots violations, missing parsed
content, short delays and runtime errors). Its success is not M1 acceptance.
The Java checks and Flux dry-run also do not exercise actual HTTP fetching.

## Historical blockers and continuing limits

- The agent cannot access Docker's engine pipe. Run the helper on the host and
  review its evidence before marking M1 complete.
- The reviewed host run demonstrates fixture fetching and unchanged frontier
  entries across graceful recreation, but fails the required request-spacing
  assertion. Protocol-IP enforcement has no independent packet-level evidence.
- Cgroup CPU/memory metrics were absent in M0. Request/queue/outcome metrics are
  covered by this harness; resource monitoring under load remains separate.
- Only Windows/PowerShell orchestration is supplied. Linux reproduction and a
  portable orchestration entry point remain follow-ups.
- Images use versioned tags; the run records digests but does not pin them.
- Binary/Tika document parsing, retry/redirect failure paths, crash recovery,
  Tor and search are outside this controlled HTML test.

## First host failure and fix

Reviewed evidence from `.runtime/m1/onionall-brawler-m1-a26467f41a43` shows
that frontier failed during backend initialization:
`RocksDBException: While mkdir if missing: /crawldir/rocksdb: Permission denied`.
The captured image configuration uses `User: urlfrontier`. A fresh named volume
mounted at `/crawldir` did not provide that account a writable data directory.
The subsequent `UnknownHostException: frontier` reported by the user is consistent
with the service having exited and lost its active network endpoint. The old
run did not save network inspection, so its historical endpoint/alias state
cannot be reconstructed. The startup output's `Healthy` line was not a gRPC
health result: neither the old Compose service nor the captured image had a
frontier healthcheck. No seeds, crawling or recovery were verified.

The fix adds a network-disabled, short-lived `frontier-init` service using the
same image. It resolves the image account's UID/GID, prepares only `/crawldir`
and `/crawldir/rocksdb` ownership, and never deletes database files or recursively
changes their ownership. Frontier waits for successful initialization and still
runs as the image's non-root account. Existing foreign-owned database files are
not silently repaired; such a volume should fail for review. Every normal M1
rerun still uses a fresh project and leaves all earlier volumes untouched.

Frontier now has a TCP healthcheck, including after recreation. Before gRPC,
the probe checks DNS for both `frontier` and `fixture`, TCP 7071/8080 and fixture
`/health` (no crawl records). Each preflight step is limited to three seconds,
with a total twelve-second budget; HTTP connect/read each have two-second limits.
DNS workers are daemon threads so a stuck native lookup cannot hold the process
open. The following gRPC readiness loop has a ten-second retry window and
two-second RPC deadlines. Failures identify the stage and preserve diagnostic
logs. Readiness is mandatory before initial seeding and after recreation.

All active test services remain on the same internal `controlled` network;
no new fixed IPs, public ports, external DNS or host networking were introduced.
The existing fixture IP restriction is unchanged. Compose `run --no-deps` uses
the probe service's network and entrypoint but does not start dependencies;
`ready` becomes the Java main argument. `--rm` only removes the one-off container
and `--use-aliases` is not needed to resolve other services. The runner now keeps
the readiness container briefly for inspection, then removes it explicitly.
See [Docker run reference](https://docs.docker.com/reference/cli/docker/compose/run/),
[Compose networking](https://docs.docker.com/compose/how-tos/networking/) and the
[URLFrontier 2.4 image source](https://github.com/crawler-commons/url-frontier/blob/2.4/Dockerfile).

The later host run below progressed beyond this startup failure. Docker API
access remains denied in the agent environment; the agent did not rerun Docker.

## HTTP 404 verifier correction вЂ” 2026-10-31

Reviewed all four snapshots and the full crawler/frontier logs from host run
`onionall-brawler-m1-85ae0033236a`. The first snapshot contains five entries;
the subsequent snapshots contain six (including the pending seed).

The fixture logged exactly one `/missing` request with status 404. FetcherBolt
logged `Fetched http://fixture:8080/missing with status 404`. Its frontier record
is `known`, with `fetch.error.count=["1"]`, processing date
`2026-10-30T21:13:08.318390296Z` and refetch epoch `1793401988`, approximately
120 minutes later. This row survives both recreation and the second crawl
unchanged. No pipeline change or fabricated `error.cause` is required.

For the shipped [StormCrawler 3.7.0 Status implementation](https://github.com/apache/stormcrawler/blob/stormcrawler-3.7.0/core/src/main/java/org/apache/stormcrawler/persistence/Status.java),
404 maps to FETCH_ERROR. The tagged source and local JAR bytecode agree; a generic
description of all HTTP 4xx as terminal ERROR does not match this implementation.
[FetcherBolt](https://github.com/apache/stormcrawler/blob/stormcrawler-3.7.0/core/src/main/java/org/apache/stormcrawler/bolt/FetcherBolt.java)
emits response metadata including `fetch.statusCode`; transport exceptions take
a separate path with `fetch.exception`. Neither implies a mandatory `error.cause`.
The M1 persistence allowlist includes `error.cause`, `error.source`, discovery and
processing dates, but not `fetch.statusCode` or `fetch.exception`. An allowlist
does not synthesize missing fields. [MetadataTransfer](https://github.com/apache/stormcrawler/blob/stormcrawler-3.7.0/core/src/main/java/org/apache/stormcrawler/util/MetadataTransfer.java)
always retains the fetch-error counter. The [status updater](https://github.com/apache/stormcrawler/blob/stormcrawler-3.7.0/core/src/main/java/org/apache/stormcrawler/persistence/AbstractStatusUpdaterBolt.java)
increments it, and the [scheduler](https://github.com/apache/stormcrawler/blob/stormcrawler-3.7.0/core/src/main/java/org/apache/stormcrawler/persistence/DefaultScheduler.java)
applies the configured retry interval. Therefore the verifier correlates fixture
response, fetcher recognition and stored counter/date/schedule; it does not claim
the raw HTTP code itself is retained in RocksDB.

Two other expectations were corrected from observed backend behavior:
[URLFrontier 2.4 RocksDB](https://github.com/crawler-commons/url-frontier/blob/2.4/service/src/main/java/crawlercommons/urlfrontier/service/rocksdb/RocksDBService.java)
discards metadata for completed URLs and reconstructs `/blocked` as a minimal
record with refetch 0. [ListURLs builds known items](https://github.com/crawler-commons/url-frontier/blob/2.4/service/src/main/java/crawlercommons/urlfrontier/service/AbstractFrontierService.java)
even for an inserted discovered seed; `kind` alone cannot establish consumption.
Pending work is now checked by empty metadata, a positive immediately due date,
exact preservation across recreation and successful later processing. The
stdout content check accepts the actual tab-separated indexer output as well as
the original colon form; required markers remain mandatory.

Verification results:

- PASS: 20 Python unit tests; one optional socket smoke skipped. New negative
  cases reject a missing/wrong HTTP response, missing fetcher recognition,
  missing error counter/date, success-style retry schedule, missing robots
  denial, robots retries and prematurely consumed/unscheduled pending work.
- PASS: focused HTTP 404 check against the untouched host evidence; `before`
  and `after` entries are identical; expected markers, metrics and shutdown
  messages exist in both crawler logs.
- FAIL: complete verifier on the real evidence. Request gaps are
  `0.906958062, 2.005527791, 2.005051944, 69.441362065` seconds. The first gap
  violates the unchanged 1.8-second minimum. No passing `verification.json`
  or `result.json` was generated for this run.
- Full log scan: no ERROR/FATAL records; first/second crawls have 39/30 WARNs
  from the ZooKeeper connection-limit default, unavailable cgroup metrics and
  shutdown waits. Both LocalClusters finish shutdown. Frontier logs show a
  graceful close and reopening with one recovered queue, without WARN/ERROR.
- BLOCKED: Docker rerun, engine API permission denied. No volume operations
  were performed. The retained volume is preserved.

The existing evidence can be rechecked without Docker:

```powershell
python -B test/m1/verify.py .runtime/m1/onionall-brawler-m1-85ae0033236a
```

This must currently exit nonzero on request spacing. The exact full host rerun
is `./scripts/m1.ps1 -JdkHome "$env:ProgramFiles/Java/jdk-17"`; it uses a new
project and preserves older volumes. A rerun is not a fix for the observed
spacing defect in that already captured run. The scheduling correction below
requires a new host run; old evidence must continue failing.
M1 is not accepted until every mandatory assertion and cleanup passes.

## Document delay correction вЂ” 2026-10-31

Latest failing evidence: `onionall-brawler-m1-db1edcb7fb0c`. There are seven
logged HTTP requests: two robots bootstrap requests and exactly one each for
`/`, `/a`, `/b`, `/missing`, `/pending`. All have the expected crawler User-Agent;
no redirects/retries occur. These historical rows do not contain method or IP
fields: GET follows from the fixture's only logging handler, `do_GET`; individual
client IPs cannot be recovered. New fixture rows record method and client IP.
Healthchecks and the Java preflight use `/health`, which the fixture explicitly
excludes from crawl logs; no probe fetches fixture documents.

| Pair | Previous finished_ns | Next started_ns | Gap seconds | Policy |
| --- | --- | --- | --- | --- |
| / в†’ /a | 11939124632634 | 11940131781050 | 1.007148416 | FAIL: same-host documents, phase 1 |
| /a в†’ /b | 11940132058849 | 11942138408701 | 2.006349852 | Same-host documents, phase 1 |
| /b в†’ /missing | 11942138517041 | 11944145153759 | 2.006636718 | Same-host documents, phase 1 |
| /missing в†’ /pending | 11944145268395 | 12012006172530 | 67.860904135 | Different processes; not a pacing sample |

The worker's effective configuration confirms delay=2.0, min.delay=0.0,
delay.force=true, threads.per.queue=1, threads.number=1, queue.mode=byHost,
URLFrontier max.buckets=1 and max.urls.per.bucket=2. The min.delay setting is for
concurrent queue fetches and does not replace the two-second single-thread delay.
URLFrontier leases/batches and retry dates are not an HTTP completion-to-next-start
rate limiter. The long last interval includes the first bounded crawl ending,
LocalCluster shutdown (21:22:45.599), frontier recreation and second LocalCluster
startup; `/pending` is fetched at 21:23:14.602. It is not a retry delay.

The [3.7.0 FetcherBolt queue implementation](https://github.com/apache/stormcrawler/blob/stormcrawler-3.7.0/core/src/main/java/org/apache/stormcrawler/bolt/FetcherBolt.java)
reaps an empty queue when no fetch is in progress, regardless of its outstanding
cooldown. A later outlink recreates a queue ready immediately. This reproduces
the observed early root-to-/a request; no configuration increase repairs the lost
deadline reliably. A robots-denied tuple also takes an ASAP path. The policy is
document-response completion to next same-host document start, including HTTP
404, within one crawler process. Robots protocol bootstrap/cache and `/health`
are separate operations; the existing two-second policy never guaranteed spacing
for every protocol-level HTTP request. No document is exempted for failing.

The bounded, single-host M1 topology now uses the dependency's
[SimpleFetcherBolt](https://github.com/apache/stormcrawler/blob/stormcrawler-3.7.0/core/src/main/java/org/apache/stormcrawler/bolt/SimpleFetcherBolt.java),
which retains per-host deadlines independently of queued work. Parallelism stays
one; explicit byHost and max.throttle.sleep=-1 make this executor sleep until its
deadline rather than emit to a throttle side stream. The main Flux, dependencies,
M0 and original JAR are unchanged. This is an M1 choice, not a fix or scaling claim
for the multithreaded FetcherBolt. Its stock deadline cache expires after 30 seconds
of inactivity, longer than the two-second policy; restart pacing is not claimed.

`DelayRegressionCheck` executes the real pinned classes with in-memory protocol,
metrics and collector substitutes (no HTTP). It proves the old queue loses a
future deadline, then executes `/`, a one-second idle gap, `/a`, a robots-denied
`/blocked`, and `/missing` through SimpleFetcherBolt. The packaged probe's
`delay-check` passed with document gaps 2.0053693s and 2.0064399s; `/blocked`
never reached the protocol and `/missing` emitted FETCH_ERROR. Reflection is
limited to this pinned-version test; it is not used by the crawler. The host
runner now requires this regression before seeding and saves `delay-check.log`.

The verifier classifies two crawl phases by their mandatory initial robots
requests; exact request counts and allowed phase paths are still required.
It checks all adjacent documents within each phase, and rejects unexpected
paths/methods. Historical GET-only records remain readable. Failure diagnostics
include phase, both paths, raw timestamps, measured gap and the unchanged policy
of 2 seconds with 0.2-second tolerance. Successful results include document-pair
details; the inter-process interval is excluded from delay statistics.

Checks: 24 Python tests passed, one optional HTTP smoke skipped; all Java checks
compiled with --release 17; packaged delay/filter/connectivity tests passed;
generated Flux dry-run, Compose validation and PowerShell parsing passed. Offline
Maven `test` with JDK 17 and the workspace cache returned BUILD SUCCESS (8.555s),
but reported no tests: standalone M1 tests remain outside Maven. The local Flux
dry-run still reports missing logging providers; it is not a crawler runtime run.
The verifier against untouched db1edcb7fb0c evidence still correctly exits 1 with
the explicit `/ в†’ /a` violation. Docker API permission is denied; complete HTTP
and restart-persistence verification of the new topology remains pending.

Full host rerun (new project, older RocksDB volumes preserved):

```powershell
.\scripts\m1.ps1 -JdkHome "$env:ProgramFiles/Java/jdk-17"
```

Do not accept M1 until the real document spacing and every other mandatory
assertion passes in that rerun. No retained volume was reset or deleted.

## Structural HTTP outcome checks вЂ” 2026-10-31

Latest evidence: `.runtime/m1/onionall-brawler-m1-23149e4be2e2`. The complete
startup.log shows successful init completion and healthy frontier/fixture.
Fixture records exactly one GET `/missing` with HTTP 404. The frontier row is
known, fetch.error.count=["1"], lastprocesseddate=2026-10-30T21:51:16.738850341Z,
refetch=1793404277 (about 120 minutes later). It is unchanged across both later
phases. This is a retryable FETCH_ERROR outcome, not terminal ERROR.

The old regex required `with status 404 in msec N`. SimpleFetcherBolt actually
logged `with status 404 in 3 after waiting 0`. The pipeline was correct; the
test depended on a different bolt's INFO wording. No production code changed.
The tagged Status.fromHTTPCode mapping still maps 404 to FETCH_ERROR. The active
OkHttp protocol returns the actual response code; socket failures follow the
exception path. StatusUpdater increments the error counter, schedules the retry
and writes a known item with metadata/date, not a Storm status enum. See
[Status](https://github.com/apache/stormcrawler/blob/stormcrawler-3.7.0/core/src/main/java/org/apache/stormcrawler/persistence/Status.java),
[HttpProtocol](https://github.com/apache/stormcrawler/blob/stormcrawler-3.7.0/core/src/main/java/org/apache/stormcrawler/protocol/okhttp/HttpProtocol.java),
[AbstractStatusUpdaterBolt](https://github.com/apache/stormcrawler/blob/stormcrawler-3.7.0/core/src/main/java/org/apache/stormcrawler/persistence/AbstractStatusUpdaterBolt.java).

HTTP 404 verification now requires an actual fixture GET/404 and persisted
error counter, processing date and retry interval, or a terminal known/refetch=0
completion marker. The latter is accepted with the real 404 response; it is an
inference of terminal error in this scenario, not a claim that URLFrontier stores
an ERROR enum. A future successful schedule is rejected, as is contradictory
transport-exception metadata. The test configuration now persists real
fetch.statusCode and fetch.exception for stronger diagnosis on future runs;
new available status-code values are checked, older captures without that key
remain readable. No value is synthesized.

Other INFO dependencies were removed: robots enforcement uses completion state,
the fixture's tested Disallow rule and absence of a blocked request/content;
process completion uses crawl-runtime.json with two zero exit codes, written
only after each Compose command succeeds. A partial/nonzero exit record fails.
Observability uses fixture counts, frontier states and per-pair spacing details
in verification.json, without requiring a LoggingMetricsConsumer logger prefix.
Runtime INFO messages remain diagnostic. Real ERROR/FATAL severity records still
fail review. Required parsed content markers remain checked in the documented
StdOutIndexer TSV/colon data output, independently of INFO logger formatting.

Checks performed: 31 Python tests passed, one optional HTTP smoke skipped.
Regressions cover HTTP 200/error-state mismatch, retryable/terminal 404, changed
or absent INFO messages, contradictory HTTP code/transport error, and missing
or nonzero process exits. The real Java SimpleFetcherBolt regression now also
injects a transport IOException and checks FETCH_ERROR plus fetch.exception;
it passed along with robots and 404 behavior. Java compilation, Flux dry-run,
Compose validation and PowerShell parsing passed. Maven offline test returned
BUILD SUCCESS in 7.362s but has no Maven-managed tests. Local Flux logging-provider
warnings remain a dry-run environment limitation.

The complete verifier on untouched host snapshots passes all crawl assertions:
six final records, identical before/after state, recovered pending work and
minimum document gap 2.002353499s. It reports crawl_evidence_passed=true but
assertions_passed=false and exits nonzero because the older run has no structured
crawl-runtime.json. No synthetic exit record or passing result was added to old
evidence. Docker API remains permission denied. Rerun the existing single host
command shown above to obtain all required new evidence and cleanup result.
M1 is not accepted until that complete integration succeeds; volumes are retained.

## M1 acceptance — 2026-10-31

Evidence: `.runtime/m1/onionall-brawler-m1-df35366e0994/`. The user ran Docker;
the agent reviewed saved evidence and reran the verifier offline. Earlier pending
statuses in the investigation history above are superseded by this acceptance.

All assertions pass: crawl_evidence_passed=true, runtime_verified=true, six
frontier records, HTTP 404 FETCH_ERROR, robots denial, exact deduplication/scope,
future schedules, unchanged before/after state and resumed pending work. Minimum
same-process document gap is 2.00265402s. Both crawler exit codes are zero.
result.json records successful cleanup; the named volume remains retained.

Full log review found no ERROR/FATAL. Crawl one has 29 WARNs, crawl two 30:
12 unavailable cgroup metric warnings, two ZooKeeper connection-limit defaults
and 45 shutdown waits. Both LocalClusters finish shutdown; no unresolved wait
or blocking runtime defect was found. Backend logs have no WARN/ERROR and show
RocksDB close/reopen. These warnings were not suppressed. Cgroup resource
monitoring remains unavailable in this setup.

Acceptance is limited to the synthetic HTML fixture and graceful container
recreation on the user's Windows Docker host. It does not establish abrupt-crash
recovery, adversarial isolation/redirect safety, production throughput, Tika
parsing, Linux reproduction, Tor, OpenSearch/search or distributed Storm. Image
tags are recorded rather than digest-pinned. No agent-side Docker rerun is claimed.
Raw logs, image inspections, runtime output and volume data stay outside Git;
only code, configuration and this sanitized record are committed.


## Linux port verification — 2026-10-31

From the repository root after building, the native Linux entry point is
`bash scripts/m1.sh`. Optional `JAVA_HOME` and `PYTHON` select the JDK and Python.
No PowerShell is required. Windows continues using the existing `scripts/m1.ps1`.

The Bash runner mirrors the accepted PowerShell order, including Java filter and
pacing regressions, inspected readiness probes, empty-volume check, first crawl,
pending seed, graceful frontier stop/removal/recreation, second crawl and the
unchanged Python verifier. `set -Eeuo pipefail` preserves failures through logged
pipelines; EXIT/INT/TERM handling collects diagnostics and removes the project
without deleting its volume. result.json is written only after verification,
container removal and inspection of the retained named volume succeed.
verification.json retains assertions_passed, crawl_evidence_passed and
runtime_verified; no passing flags are fabricated when Docker is unavailable.
Additional Linux files: docker-info.log and retained-volume.json.

Both M1 shell helpers are invoked via explicit interpreters from temporary copies
with carriage returns removed; neither executable mode nor checkout line endings
are relied upon. Sources and database files are not rewritten. Compose mounts,
network policy, assertions, Windows runner and verifier remain compatible.

Observed on Omarchy / Arch Linux (kernel 7.2.8-9-omarchy):

- PASS: Bash syntax; both Compose configurations, including M1 tools profile.
- PASS: 31 standalone Python tests; one optional localhost HTTP smoke skipped.
- PASS: Maven offline clean verify, 7.393 seconds, JDK 21.0.13 / Maven 3.10.0.
  Default local-cache locking was denied; a separate copy under
  .runtime/maven-linux-repository was used. Log: .runtime/maven-linux-verify.log.
  Maven reported no managed tests; the standalone harness is checked separately.
- PASS: all Java harness sources compile with --release 17; actual URLFilters,
  bounded connectivity checks and SimpleFetcherBolt delay regression pass.
  Delay regression needed provided Storm runtime jars on the local classpath;
  observed document gaps: 2.006998948s and 2.001203651s. Local checks report
  missing SLF4J providers; these are not Docker runtime log results.
- PASS: both resolved M1 helper entrypoints execute non-executable CRLF input.
- PASS: git diff --check; only the runner, M1 Compose and requested docs changed.
- BLOCKED: full Linux Docker integration. Docker CLI 29.9.2 / Compose 5.6.1
  are installed, but Engine socket access is denied. Actual runner invocation
  exited nonzero and saved docker-info.log under the unique evidence directory.
  No containers or volumes were created by that invocation.

The initial agent-side Docker access failure above is superseded for Linux
acceptance by the successful host run below; agent socket access is still denied.
No M2, Tor, OpenSearch or history rewrite is part of this port.

## Omarchy Linux M1 acceptance — 2026-10-31

Evidence: `.runtime/m1/onionall-brawler-m1-e55203e97536/`. The user ran the full
Bash runner on Omarchy. The agent inspected saved evidence and reran the unchanged
Python verifier offline successfully; no agent-side Docker rerun is claimed.

- assertions_passed=true, crawl_evidence_passed=true, runtime_verified=true.
- Six final frontier records; before/after entries are identical across graceful
  container recreation. Pending work resumes and previously processed state stays
  unchanged. HTTP 404 is FETCH_ERROR; robots, link discovery, scope and dedup pass.
- Minimum same-process document gap: 2.003614258 seconds.
- crawl-runtime.json records two zero exits. Both LocalClusters complete shutdown.
- cleanup.log confirms all project containers and the controlled network removed.
  result.json confirms cleanup; retained-volumes.txt and retained-volume.json
  record the retained named frontier-data volume. No volume deletion occurred.

Both crawler logs contain zero ERROR/FATAL and 38 WARNs each: 12 unavailable
cgroup metric warnings, two ZooKeeper connection-limit defaults and 62 shutdown
waits in total. The waits resolve; cgroup monitoring remains unavailable.
Backend logs contain no WARN/ERROR and show graceful RocksDB close, reopening
and recovery of one queue. No blocking runtime failure was found.

The separate delay-check.log contains one expected ERROR from the deliberately
injected IOException for /transport; the regression verifies FETCH_ERROR and
fetch.exception, then reports PASS. filter-check.log contains one expected WARN
for the deliberately malformed fixture-host lookalike URL; URLFilters reject it
and the check passes. These are negative-test diagnostics, not crawl failures.

Both `bash scripts/m1.sh` (Linux) and `./scripts/m1.ps1 -JdkHome <JDK>` (Windows)
remain supported, using the same verifier and evidence contract. The Linux port
itself is already in f77d789; this acceptance records the successful host run.
Raw runtime logs, generated JARs/classes, Docker inspection data and RocksDB
contents remain ignored and outside commits; only sanitized summaries are saved.

M0 and M1 are complete for their stated scopes. M2 has not started. Acceptance
covers synthetic HTML and graceful recreation; it does not establish crash
recovery, Tor, OpenSearch/search, distributed Storm, Tika parsing or production
isolation/throughput. Image tags are not digest-pinned.

## Java25 / Storm3.1 regression — 2026-11-01

Current migration branch passed M1 e1c1f2931a8f: all three verification booleans
true, six records, restored state/pending work, gap2.003810683s and cleanup with
retained frontier volume. Original Windows/Linux results above remain historical;
Windows25 execution was not performed. Current input versions and rollback:
[jvm25-storm31-migration.md](jvm25-storm31-migration.md).
