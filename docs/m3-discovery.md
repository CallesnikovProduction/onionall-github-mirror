# M3 discovery: local vertical verified, Tor acceptance BLOCKED

## Current boundary

M2 remains BLOCKED; no Tor restart/circuit investigation or public crawl ran in
this iteration. M3 has an actual local Storm + gRPC URLFrontier vertical test,
not a completed Tor milestone. All manifests/results keep m3_verified=false.
Baseline82dce67 was pushed normally; subsequent checkpoint0a3148d contains the
MIME/local-integration implementation. No history was rewritten.

## Discovery and delivery

Stock JSoupParserBolt resolves/extracts HTML links; the M3 parse filter applies
existing TorPolicy and records bounded source/target/time/depth/decision edges.
Known file suffixes are metadata-only. Replays re-emit admitted URLs to the stock
frontier dedup mechanism: an in-memory reservation must not suppress a tuple that
never received its previous frontier ACK. Per-document duplicate output is removed.
ADMITTED means a planner decision, not FRONTIER_ACK or FETCHED.

DiscoveryScopeFilter observes policy refusals before ParseFilters. Installed
StatusEmitterBolt.filterOutlink bytecode confirms that URLFilters receive parent
metadata before getMetaForOutlink. Refusals increment bounded parent counters and
record time; raw rejected URLs/credentials are never stored. URI resolution
failures occurring before URLFilters remain outside this observer; do not claim
that every malformed raw HTML attribute is audited.

Live integration found a persistence defect in the generated config: with
fetchInterval.default=-1, frontier records became refetch=0 with empty metadata.
The corrected generator uses1440 minutes; graph metadata now survives actual
status updates/serialization. Terminal ERROR records may still compact/drop
metadata. Frontier is not an immutable historical graph store; never claim
permanent provenance retention across terminal states without another verified
storage contract. Different parents preserve their own edge arrays in their
parent records. Child discovery.source is not a complete multi-parent graph.

## Pre-body MIME gate

`tor.discovery.html.only: true` opts TorProtocol into the narrow M3 content gate.
Default M2 behavior is unchanged. M3 permits one valid Content-Type of text/html
for pages and text/plain for robots.txt. Missing/duplicate/malformed/other MIME
is rejected before application body reads with IOException(METADATA_ONLY).
The gate requests identity encoding and rejects unexpected Content-Encoding,
avoiding implicit decompression of untrusted binary objects. It cancels the Call
before closing a rejected/redirect response, avoiding body draining for reuse.

Redirect headers still require one policy-normalized Location (except304),
automatic following/retries remain off; redirect payloads are not read. HTML body
reads are bounded to configured limit+1, independently of Content-Length/chunking.
Obvious lying text/html (e.g. ZIP prefix) is refused after bounded inspection;
accepted documents need a conservative HTML prefix. This may reject valid HTML
fragments and is not a universal MIME authenticator. Headers may already cause
TCP/Okio read-ahead; no claim of zero binary bytes on the wire is made. The safety
contract is no automatic unbounded/full arbitrary file download, not perfect
classification of malicious bytes embedded inside a small HTML response.
Robots retains its existing parser, strict status checks and fail-closed handling.

## Durable global budget: fail-closed, NOT implemented

URLFrontier's installed admission/status API does not expose an atomic global
unique-URL reservation transaction. Parser synchronized sets do not implement
that. DiscoveryParseFilter now requires explicit `discovery.budget.mode=local-test`
and a bounded-format `discovery.crawl.session.id`. Default/durable/unknown modes
fail configuration. Page/depth/link counters remain bounded per parser instance;
retries of the same URL do not consume additional in-memory admission slots.
They are not durable or global across workers/restarts. Source counts as a page.

A real durable session needs immutable session ID plus an atomic unique
(session,URL) reservation/count in existing frontier storage (or a justified
server extension), restored before dispatch. Two concurrent workers admitting
the same URL must consume one slot; crash/retry must not reset it. Do not add a
new database or pretend the current local-test mode fulfills that contract.
Configuration failure prevents this discovery filter from emitting links; it
is not a claim that arbitrary separately launched seed fetchers cannot start.

## Reproduce the local vertical

Requires current M1 images, Docker Compose, Java25 tools on PATH, Python and an
offline Maven cache. No host DNS/proxy/VPN/route changes are needed:

```sh
mvn -o -B -Dmaven.repo.local=.runtime/maven-linux-repository verify
python3 -B test/m3/local_integration.py
python3 -B -m unittest discover -s test/m3 -p 'test*.py'
python3 -B -m unittest discover -s test/m2 -p 'test*.py'
```

The harness reuses compose.m1.yaml with a generated ignored override and one
internal network. Three synthetic valid-v3-shaped hostnames map only inside the
crawler container to one owned fixture. They are not real Tor services. The
local override explicitly uses stock HTTP transport for this diagnostic only;
it is neither a Tor fallback nor proof of Tor routing/MIME enforcement. Production
M3 config still selects TorProtocol. Existing M0–M2 config files are unchanged.
Only A is seeded. Fixture A links B twice, an archive, /blocked, /temporary and
one out-of-scope host; B links C and A; C links A. There is no upstream fetch.

The100s Storm run proves A/B/C exactly once, cycles/dedup, no archive request,
robots deny without /blocked request, one503 then successful scheduled retry
(one-minute test-only error interval), parsed retry marker and persisted graph,
sources/depths0/1/2. Real gRPC snapshots contain five records (three roots plus
blocked and temporary URLs). Cleanup handles SIGTERM with bounded consumer stop,
removes its owned containers/network and retains RocksDB volume; missing retained
volume makes the result fail. It never removes Tor state or service keys.

## Actual results

- **PASS** offline Java17 Maven verify:47tests (including MIME/header/chunked/
  redirect/robots, durable-mode refusal, safe rejection audit and previous tests).
- **PASS** Python M2:66tests; Python M3:2tests (fixture and generated config guard).
- **PASS** real local vertical: .runtime/m3/onionall-brawler-m1-m3-830a39ae9a/,
  five records, persisted provenance, cleanup and retained volume. The result
  intentionally reports m3_verified=false. Base three-record run9f09fc2973 also
  passed; it does not cover the extended negative/retry assertions.
- **FAIL, fixed** initial f0be205980: host-absolute config path was absent inside
  /workspace; generator now writes repo-relative topology include paths.
- **FAIL, fixed** f5f732c5e6: three roots were fetched but terminal scheduling
  discarded provenance. Finite revisit fixed this; failed raw evidence retained.
- **NOT RUN** real onion M3 acceptance, live Tor MIME test, full M2 gate/recovery.
- **BLOCKED** durable global budgets, M2 acceptance, Tor security update and
  production recovery prerequisites. No new supervisors or circuit resets.

Logs stay ignored under .runtime/m3; they can contain runtime credentials and
must never be committed. Subsequent cleanup/retention-result guard and SIGTERM
hardening do not change the proven graph/retry assertions; live cancellation
fault injection remains unverified. Current exact commands/results are recorded
in the raw logs. Unrelated pre-existing pom.xml release17 edit is not included
in the completion commits.

## Platform migration verification — 2026-11-01

Java25/Storm3.1 with StormCrawler3.7 passed actual LocalCluster/Flux A→B→C,
robots/dedup/depth, temporary503 retry and five persisted frontier records
(profile5f37e310b5; final-default rerun91c6025d49, cleanup/retention pass). This local diagnostic still reports m3_verified=false.
Maven47, Python M2 67/M3 2, Java25 resolver-guard35 pass.
Tor prerequisite127.0.0.1:19050 refused; no live integration started.
Durable admission remains rejected outside local-test; see ADR0002.
The historical Java17 results above remain valid for their original commits.
Current commands assume one selected JDK25 on JAVA_HOME/PATH. Full compatibility,
input checksums and remaining blockers: [migration report](SUMMARIZIED-MIGRATION-AND-CLEANLINESS-REPORT.md).
