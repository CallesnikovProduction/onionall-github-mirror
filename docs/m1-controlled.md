# M1 controlled crawl — implementation and verification status

**Status on 2026-10-30: harness implemented; Docker end-to-end run pending.**
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
The M1 helper derives a Flux file from the existing graph and adds a test
configuration include. A separate copy of the shaded JAR receives test resources
and two Java probe/check classes. The original JAR is not modified.

## Host run

Prerequisites: Docker Engine with Linux containers, Docker Compose, PowerShell,
Python 3.12 or newer, a JDK 17 or newer (compiling with `--release 17`), and the
current built main JAR. The Python fixture image is `python:3.12-slim`; it has
not been pulled/started by the agent. The runner records image inspection data.

From the repository root in normal host PowerShell:

```powershell
./scripts/m1.ps1 -JdkHome "$env:ProgramFiles/Java/jdk-17"
```

Two bounded 45-second crawler runs plus setup/teardown are expected. Every run
has a unique Compose project and new RocksDB named volume. The fixed subnet
`172.30.71.0/24` must be free. If Docker reports a subnet overlap, stop and
review the test network and matching fixture-IP include together; the helper
does not delete unrelated networks or relax the network boundary.

The helper fails on nonzero Docker commands or failed assertions. It performs:

1. Java test-probe compilation, test JAR assembly and topology derivation.
2. Compose validation, image pull and image inspection capture.
3. Fresh frontier/fixture startup, actual URLFilters checks and gRPC readiness.
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
scheduled in the future. Robots-denied and 404 URLs must have persisted error
metadata. The pending record must remain discovered across recreation. Every
record's kind, metadata and refetch date must survive unchanged. A second crawl
must process /pending without re-fetching earlier pages.

The fixture must observe each allowed page once, exactly two robots requests,
no blocked or unexpected fixture paths and at least 1.8 seconds between completed
non-robots requests and the following request (two-second configured delay,
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
| Java probes | Compiled against the existing shaded JAR with JDK 17 / --release 17 |
| Actual StormCrawler filters | Allowed fixture cases/fragment normalization passed; 11 out-of-scope authority/path/scheme cases rejected |
| Test JAR assembly | Passed; packaged filter-check and Flux dry-run passed; original main JAR unchanged |
| Generated Flux graph | Dry-run passed; same spout/bolts/streams with M1 include |
| Compose | Config validation passed with test environment variables |
| PowerShell | Parser accepted scripts/m1.ps1 |
| Python local suite | 10 passed, 1 HTTP integration test explicitly skipped in default suite |
| Local HTTP integration attempt | Failed with WinError 10013 due to restricted socket access; not counted as passed |
| Docker execution | API permission denied; M1 script preflight rejects it before any containers start |

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

## Remaining blockers and limits

- The agent cannot access Docker's engine pipe. Run the helper on the host and
  review its evidence before marking M1 complete.
- Actual fixture crawling, seed writes, status metadata shape, protocol-IP
  enforcement and RocksDB recovery are pending that end-to-end run.
- Cgroup CPU/memory metrics were absent in M0. Request/queue/outcome metrics are
  covered by this harness; resource monitoring under load remains separate.
- Only Windows/PowerShell orchestration is supplied. Linux reproduction and a
  portable orchestration entry point remain follow-ups.
- Images use versioned tags; the run records digests but does not pin them.
- Binary/Tika document parsing, retry/redirect failure paths, crash recovery,
  Tor and search are outside this controlled HTML test.
