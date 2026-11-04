# Onionall Brawler — project context

## S1-01 local authorization hardening — 2026-11-04

The candidate Frontier now requires its existing capability for all PutURLs and
GetURLs (lease acquisition), as well as Admission/Command. Keep admin mutations
denied, immutable reservation/quota guards and sync-WAL admission unchanged.
No protobuf, persistent schema or Tor isolation change. Read-only RPC policy and
shared operator/worker authority remain separate open risks.

Durable CrawlExecution selects CapabilitySpout and CapabilityArchiveStatusUpdaterBolt.
CapabilityStatusUpdaterBolt/CapabilitySpout are source-pinned StormCrawler3.7.0
adapters under src/main/java/org/apache/stormcrawler/urlfrontier; only class identity
and mandatory CallCredentials wiring differ from upstream. Preserve ACK/scheduler/
backpressure semantics when updating these copies. FrontierCapability reads the
existing admission.token.file for each new RPC, including recreated streams;
missing/invalid file or rejected capability must never retry anonymously.
Stock M0/M1 and local-test archive fixtures retain their separate stock clients.

Deploy compatible clients/server together: old tokenless workers fail closed.
Windows CrawlWorkerTest uses nested Java argument files, with original assertions
and deadlines retained. Current validation and remaining integration/review gates:
docs/security/SECURITY-REVIEW.md and docs/engineering/security-execution-ledger.md.
The Security RC task authorizes reviewed local checkpoint commits only.
No push, merge, PR creation or main/source-branch changes are authorized.
Security RC is a local reviewable checkpoint, not full S1-01/Tor/release acceptance.
Before future WP-08 work note that current RocksDBService forces recoveryQscan(true);
DeepSeek REC-06/07 are not active on this candidate. Follow the corrected roadmap,
full M2 triage and owner handoff in SECURITY-REVIEW.md section15. Do not start M5-M9
or send the handoff automatically.

## Purpose

Onionall Brawler is an open-source-oriented, JVM-first project to build an independent search engine for publicly discoverable and permitted-to-index Tor onion services. Its intended capabilities include seed-based discovery, recurring crawling, a searchable corpus, source metadata, a graph of relationships between services, and a research interface. Optional semantic search, summaries and AI-assisted research may come later; the core search system must work without AI.

Tor v3 has no global registry of onion services. Complete enumeration is not a realistic guarantee. Measure discovery and coverage of known, permitted sources instead of claiming comprehensive coverage.

## Terminal CLI boundary

Entrypoint dev.onionall.brawler.cli.Onionall (Picocli4.7.7), with separate Brand
and HtmlFiles. fetch invokes existing TorProtocol: Ahmia allowlist only, local
SOCKS19050, HTML-only128KiB, finite30s call, no redirects/retries/DIRECT fallback.
inspect is offline; status is local TCP only, never Tor-readiness proof.
Offline discover reuses JSoup/TorPolicy and the planner metadata-only rule; no
transport/Frontier calls. Limit4MiB/1000 anchors/2048 URL chars, deterministic JSON
and explicit atomic JSONL export (no overwrite). Configured M4.1 durable admission is available through the versioned Frontier
candidate; stock Frontier and unconfigured crawl start remain fail-closed.
See docs/m41-durable-admission.md and its execution ledger before crawl changes.
Menu/status presentation styles trusted rows after clipping; status is TCP-only.
Discovery contracts/tests: docs/cli-discovery.md.
2026-11-02 offline Java25 verify80 tests and CLI/PTY15 pass. Installed discover
works without Maven in PATH; controlled SOCKS fixture sees zero connections.
M2/M3 remain BLOCKED; no live Tor or full milestone gate rerun in this pass.
Build scripts/cli/build-cli.sh (Java25, ONIONALL_OFFLINE=1 for cached Maven); install
scripts/cli/install-cli.sh; uninstall scripts/cli/uninstall-cli.sh. Thin target/onionall
bundle contains API dependencies, no Flux/Tika/frontier services. XDG user install,
checkout .runtime/downloads or XDG downloads; logs/evidence stay ignored.
CLI tests: OnionallTest plus existing TorProtocol regressions; mvn verify.
Packaged/installation/PTY tests: python3 -B -m unittest discover -s test/cli.
Delivery66 JVM/9 packaged tests pass; installed Ahmia GET200/4735bytes passed.
Evidence and precise limits: docs/cli-verification.md; M2/M3 still BLOCKED.
Menu/spinner are real-TTY only; JSON/NO_COLOR never use colored branding.
InteractiveSession owns a supported-TTY alternate screen with idempotent restore
on Quit/EOF/SIGINT/handled errors, fixed-header ANSI viewport, read-only size polling and
idempotent margin/cursor cleanup. Brand owns wordmark/palette/layouts; Mascot
owns headless Java2D surfaces encoded as ANSI half-blocks (java.desktop required).
No external image/font dependency; ASCII density fallback shares the same vector.
NO_COLOR/--no-color keep the plain no-control stream, not a pinned viewport.
Rendering verification69 JVM/14 CLI tests: docs/cli-rendering.md.

## SOCKS incident — 2026-11-02

2026-11-03 continuation: robots cache TPR-02 now uses per-origin single-flight
loading and short shared-cache critical sections; no network I/O holds the global
cache monitor. Six deterministic regressions cover cross-origin progress, duplicate
misses, TTL, failure, eviction and cleanup; offline Java25 verify90 and Python
M2 85 / CLI-PTY16 pass. Public no-arg protocol construction remains compatible.
Controlled incident c8d4cc4db8e8 passed actual Storm robots/pending; cold-state
fallback was not exercised. Fresh original target9560fd480748 failed after the
one gateway restart at CONNECT reply30s, before HTTP/JVM series. Quarantine and
cleanup pass, three volumes retained, host baseline unchanged. Full M2 not run;
no M2/M3 status upgrade. Same-context52b269ed5fb1 passes actual Storm;8f2036152884
fails four clients before fresh-context comparison can run. No more identical
Tor retries. Frontier-only SIGKILL/lease/recreation1a7cb4b6f722 passes; this is
not durable quota/ACK-loss admission. Fresh strict M136bf86224d74 passes six
records/gap2.002960580s/cleanup. Python M2 now89, opt-in M1 32, M3 unit2 pass.
Pinned upstream frontier compiles offline and nine storage tests pass; stock2.4
refuses an added CF, so compatible rollback is required before migration.
Fresh local M3f90ef8e542 passes five records/provenance/retry; transport is HTTP,
not Tor. Final frontier timing-guard variante4b9595217a2 also passes. Cleanup
audit: seven projects removed,13 expected volumes retained; protected host
snapshot unchanged. M2/M3 VERIFIED and production-ready remain false.
Current evidence/next step:
docs/engineering/recovery-execution-ledger.md. Preserve the user's in-progress
documentation consolidation; do not stage its deletions/new files as recovery work.

Same-namespace controlled comparison: Python NO_AUTH/USER_PASSW, native JDK and
TorProtocol can all stall after owned onion restart and recover after gateway
restart. Storm is not a necessary cause; exact Tor/path cause remains unproven.
TorProtocol now freezes the configured proxy as a numeric hostString: native JDK
otherwise re-resolves a resolved-but-named InetSocketAddress. Regression failed
before fix and passes after; this is separate from Tor network availability.
New Linux diagnostic test/m2/socks_incident.py preserves one client namespace,
20s deadlines, original isolation and volumes; original M2 gate unchanged.
a3f5c6720038 incident PASS includes real Storm robots/pendingHTTP200 and retained
frontier in that namespace. Fresh repeat b66bf31c112f FAIL: Python0/2 + nativeJDK
+ TorProtocol all time out beforeHTTP after gateway restart; crawler not started.
M2 remains BLOCKED; endpoint fix is not a proved cure. Host baseline unchanged
within all five experiments; no volumes removed.
Details/CONFIRMED/FIXED/STILL BLOCKED/NEXT EXPERIMENT: docs/recovery/m2-socks-incident.md.

## M1 lifecycle fix — 2026-11-02

M1 now passes twice consecutively on explicit image
onionall-storm:3.1.0-jre25-cleanup2:25da2046fecb andd927d1d8835e, all three
verification flags true, six records, pending resume, zero ERROR/FATAL, cleanup
and volume retention. Baseline and cleanup1 variants remain available.
Build: JAVA_HOME=<jdk25> bash scripts/patches/build-storm-lifecycle.sh; run with
M1_CACHED_IMAGES=1 M1_STORM_IMAGE=onionall-storm:3.1.0-jre25-cleanup2.
Backport serializes Nimbus cleanup on submitLock and drains active cleanup before
closing its timer/ZK dependencies; genuine ERROR logging/assertions unchanged.
Hash-pinned source and original server JAR, deterministic real Nimbus/StormTimer
regressions: docker/storm-lifecycle/README.md. M2_STORM_IMAGE propagates to M1 child.
M2/M3 remain BLOCKED. Target19aa14dfd670: initialhealth/identity preserved;
post-restart CONNECT timeout; gateway restart restores Python canary but first
standalone JVM robots times out20s before HTTP. Consumer never resumed; cleanup
retains all volumes. Same-container readiness variant remains UNVERIFIED.
Opt-in bounded TOR_TRACE uses JVM/request IDs, hashedhost and fixed thread_kind
(no raw thread names, which can embed URLs). No transport policy change.
Tor security pin unchanged; signed metadata obtained but Packages delivery timed
out. ADR0003 proposed only; CLI admission remains disabled.

## Recovery closure — 2026-11-02

M2/M3 remain BLOCKED. Fresh8b64cfc89746: controlled bootstrap/health, preserved
identity, one gateway restart, JVM robots/health series and unchanged pending
snapshot pass; resumed Storm robots times out in JDK SOCKS negotiation before
HTTP. URL persists with error/backoff; target fails, full M2 not launched.
No timeout/security changes. M3 lacks real-onion full runner and ADR0002 durable
server admission; retain local HTTP variant and fail-closed production mode.
M1 child now has deterministic isolated evidence/exit handling and propagated
cached-image policy (M1_CACHED_IMAGES=1); no mtime scan or implicit pull in cached
mode. Failed flat CONNECT health records preserve phase instead of “malformed”.
Local M3 b62983d078 passes5 records/provenance/retry/retention (HTTP only).
Checks: Java25 Maven80, Python M2 76/M1 31+1skip/M3 2, CLI15 pass.
Details/current verification: docs/m2-hardening.md; ignored closure-20261102 summary.
Fresh cached M1 ac0f49b9578d-m1 FAIL: six records/pending resume succeed, but
strict no-ERROR gate catches Storm3.1 Nimbus cleanupTimer missing stormcode.ser
during submission; no log whitelist or timer suppression. Parent preserves exit1.
Tor pin0.4.9.11 unchanged; security update remains independent blocker.

## Robots decoding contract — 2026-11-01

TorProtocol rejects malformed UTF-8/BOM-selected UTF-16 robots before parser;
parser runtime errors become ROBOTS_UNAVAILABLE with cause and are not cached.
Empty robots and valid BOM files retain crawler-commons semantics. TODO inventory
and remaining evidence contracts: docs/kotlin-todo-audit.md. No milestone upgrade.

## JVM migration — current candidate, 2026-11-01

On migration/java25-storm31, defaults are Java25 / Storm3.1.0;
StormCrawler3.7, URLFrontier server/client2.4 and Kotlin2.3.21 remain.
Select one JDK25 with JAVA_HOME/PATH; run Maven verify before M1/M3 helpers.
Profiles java25/storm31 are aliases, not Java17 fallback. Main97b9da4 is retained.
M1 e1c1f2931a8f passes all assertions/cleanup, six records, gap2.003810683s.
G0/G1 pass47 JVM tests; G2/G3 real local Storm A/B/C passes (91c6025d49, five records,
provenance,503 retry, robots and retention). Python M2 67/M3 2 pass.
Java25 DNS guard uses a separate test-only resolver SPI service JAR,35 tests,
zero application onion lookups; not system-wide DNS-leak proof. Probes require
Storm3.1 lib-common on their classpath. Native Windows remains unverified.
G4 blocked: local SOCKS19050 refused; no live Tor retries. Tor0.4.9.11 security
and M2 recovery blockers persist. G5 blocked: no atomic global reservation in
current API; ADR0002 selects a separate additive frontier-server contract.
M2/M3 acceptance stays BLOCKED, production discovery admission fail-closed.
Detailed gates, exact inputs, rollback and limitations:
docs/jvm25-storm31-migration.md. Historical entries below describe prior runtimes.

## M3 offline discovery in progress — 2026-11-01

M3 local vertical is verified, Tor acceptance remains BLOCKED. Discovery uses
stock parser/status/frontier and existing TorPolicy. TorProtocol has opt-in
`tor.discovery.html.only`: pre-body HTML/plain-robots MIME/encoding gate,
bounded inspection and cancellation of rejected/redirect bodies; M2 default
behavior unchanged. DiscoveryScopeFilter counts safe parent refusals without
raw rejected URLs. Use finite fetchInterval.default=1440 to retain provenance;
terminal refetch=0 entries dropped metadata in the actual frontier test.
Durable atomic global admission is unavailable: filter requires explicit
`discovery.budget.mode=local-test` plus crawl session ID; other modes fail closed.
Do not claim global/restart bounds. Local runner: test/m3/local_integration.py
uses isolated M1 Compose + stock HTTP solely for the diagnostic (not Tor fallback).
Run Maven verify first. Actual A/B/C dedup, depth/provenance, robots,503 retry,
five records and cleanup pass. JVM47/Python M2 66/M3 2 pass. Tor acceptance,
durable budget and M2/security prerequisites stay blocked. Details and commands:
docs/m3-discovery.md. Logical completion commits/push authorized for this task.

## M2 implementation in progress — 2026-10-31

Final integration review: TorPolicy rejects paths rewritten by URI.normalize
and percent-encoded dot segments: robots checks the original URL while OkHttp
could otherwise fetch /blocked. New scope/no-socket regressions pass. Supervisor
now recognizes exception records rather than category words in document content.
Recovery target rejects stale JARs and verifies actual ROOT/A/B Storm fetches.
Offline JDK17 verify38/Python M2 66/security4 pass; fresh resolver guard32 passes
with zero onion lookups (not system-wide proof). Target5c07d50d5d36 passes owned
restart, real protocol series, pending resume, eight-record state and cleanup.
Full dc2e99288d55 failed Docker Hub metadata access before startup; explicit
--cached-images now records/uses existing M2 images without build/pull. Full
ba732bdd136c failed: Python canary passed but the first Java robots series
request timed out in JDK SOCKS negotiation, before HTTP/Storm. No resume; dependent
outage/matrix stopped. m2_verified=false. Independent M1 abb26c8d207b passes
(six records, gap2.003326975s). All new containers/networks removed, volumes
retained, VPN/default routes/rules/DNS unchanged. No identical full retry.
Tor remains0.4.9.11; candidate0.4.9.15 undelivered. Production recovery still
requires M2 fixture/probes and is not independently deployed.
The user now authorizes a reviewed checkpoint commit and ordinary origin push;
this checkpoint records BLOCKED M2, not production readiness.
TODO disposition, known limits and evidence: docs/m2-hardening.md.

Checkpoint authorization (2026-11-01): the user requests one reviewed Git commit
and ordinary push to the existing Entire origin, then continued M2 recovery.
Do not commit/push subsequent recovery changes without a separate instruction.
Keep VPN and host networking untouched; only isolated M1/M2 projects are allowed.
That checkpoint is now1180512086bdd7b3f48c37fbe2d7dbcca17bffee on main,
pushed normally to origin; Entire doctor confirmed installed/approved hooks.

Post-checkpoint comparison isolates Tor hidden-service client state: after all
descriptor uploads, cold Tor fetched the new revision and answered while warm
Tor retained the old revision and CONNECT timed out. New rendezvous circuits
also stalled; closing a waiting circuit or fetching the new descriptor alone
did not restore warm connectivity. Do not call assisted reset spontaneous
production recovery. See docs/m2-hardening.md for A/B evidence and limitations.
Pinned Bookworm Tor0.4.9.11 has open Debian security issues. An isolated0.4.9.15
compatibility image attempt was blocked by BuildKit DNS for deb.torproject.org;
do not alter host/VPN DNS, routes, proxy or daemon settings to bypass this.
M2 remains BLOCKED; subsequent changes stay uncommitted. Python47 tests pass.

Restart diagnosis on 2026-11-01 with the host VPN active: host routes/policy
rules and resolver hash matched before/after every isolated experiment. Do not
change VPN, host routing/DNS/firewall/proxy settings or restart network services.
Read docs/m2-hardening.md's restart diagnosis before another live run. Failure
is now localized to SOCKS CONNECT, before HTTP, with fresh TCP sockets and stable
hostname/hs_ed25519 key hashes. Old HS_CLIENT_REND reuse and delayed descriptor/
introduction readiness were observed; a single exclusive cause is not proven.
No Tor configuration, TorPolicy or TorScopeFilter changes are justified yet.
The harness now records phases/UTC timestamps, preserves distinct restart logs
and stops dependent scenarios after failed controlled-service recovery. Python46
tests pass. Targeted restart remains red; a new full M2 gate was not launched.

Latest runtime verification (2026-11-01): bounded Tor-only diagnostic
`.runtime/m2/onionall-brawler-m2-cf110581a7e7/` passed against our own onion
fixture, including real TorProtocol /health under a JDK17 InetAddress guard,
zero target-onion resolver calls, actual crawler egress/DNS isolation and
proxy outage denial. This is scoped evidence, not system-wide DNS leak proof.
Offline JDK17 Maven package/verify passes26 tests; Python M2 passes43 tests.
Full controlled run `.runtime/m2/onionall-brawler-m2-fa44d93cba6b/` confirmed
crawling, eight-record frontier restoration, pending recovery and persisted
Storm outage/retry. Its failure matrix did not pass: after restarting our own
onion daemon, health timed out for90s although SOCKS/bootstrap were ready;
after gateway restart, health returned in6.326s. Cause remains unconfirmed.
M2 remains BLOCKED. The unreachable-onion scenario now stops our own service
instead of contacting a random generated hostname. See docs/m2-hardening.md.
M1 regression807ed6ad51e2 passed; full-run cleanup removed containers/networks
and retained all three volumes. The gate keeps controlled_onion_fetch/robots/
spacing/persistence false because its combined crawl check requires the failed
loop probe; raw crawl/persistence observations do not override that result.

M2 contract hardening on 2026-11-01 requires explicit nonblank
`protocol.md.prefix` (StormCrawler 3.7.0's real key); generated M2 configs pin
`protocol.` and persist `protocol.tor.category`. TorProtocol exports only a single
policy-normalized redirect Location, rejects ambiguous/missing targets except
304, and rejects clearly HTML robots before the existing parser. Empty robots
and unmatched User-agent groups retain parser semantics; decoding remains
lenient. JDK17 offline Maven test passes25 tests, Python M2 passes42 tests;
these loopback/mocked checks do not establish DNS isolation or M2 acceptance.
See docs/m2-hardening.md for commands and scope. No new commit is authorized.

The user authorized M2 plus intermediate commits/pushes to the existing Entire
origin. M0/M1 remain accepted and unchanged. M2 is BLOCKED, not accepted.
The project is a single Maven module; new application code is Kotlin under
src/main/kotlin/dev/onionall/brawler/tor. TorPolicy enforces v3 checksum/scope and
rejects metadata proxy/header overrides; TorProtocol implements StormCrawler's
Protocol interface with fixed SOCKS5, strict TLS, no automatic redirects/retries,
bounded bodies/deadlines and fail-closed robots. TorScopeFilter uses the same
policy before discovery scheduling. See docs/adr/0001-tor-only-transport.md.

compose.m2.yaml isolates crawler/frontier from fixture and direct outbound access.
Gateway Tor bridges internal crawler/outbound; onion-service Tor bridges separate
fixture/outbound. Generated keys remain in an ignored Docker volume. Core wire
regressions use local SOCKS stubs and do not establish real Tor acceptance.
Docker requires elevated execution in the agent session; an elevated docker info
succeeded. Pinned Tor image build passed. Fifteen JVM wire/policy tests and thirteen
Python acceptance-gate tests pass. Full M1 Docker regression on the new JAR
passed in `.runtime/m1/onionall-brawler-m1-f050361a4b55/` (minimum gap
2.002163184s, all flags true, retained volume and cleanup). Native Linux runner
`scripts/runners/run-tor-acceptance.sh` retains evidence under .runtime/m2 and includes actual crawler
network/DNS probes, real Tor failure matrix, and Storm outage/persisted retry.
M2 evidence writers use host UID/GID with dropped capabilities; M2 mounts only
necessary inputs rather than the entire repository. Both Tor daemons in real test
`.runtime/m2/onionall-brawler-m2-cbb947ba4633/` remained at bootstrap 10% until
the 360-second startup timeout. Logs show relay TLS handshake EOF/timeouts.
Cleanup removed containers/networks and retained volumes; verification.json is
m2_verified=false. Real onion crawling and outage/retry remain unverified.
An actual crawler namespace preflight denied direct internet, fixture DNS and
local onion DNS while permitting gateway SOCKS; this alone is not M2 acceptance. Independent review tightened the gate so
standalone protocol failure cannot substitute for persisted Storm outage/retry.
Keep M2 verification false until corresponding runtime evidence passes. Do not change M0/M1 contracts or describe new Tor code as accepted M2.

## M2 verification and commit checkpoints

- 9b5938d: Kotlin fixed SOCKS transport/policy/ADR, 15 JVM tests pass; pushed.
- 1b70246: isolated Tor Compose and onion fixture; pinned image build passes; pushed.
- 0c8fed8: Bash integration harness, actual-network and outage-persistence tests,
  UID/mount corrections; 13 verifier regressions pass; pushed.
- Final diagnostic/documentation commit records BLOCKED, never M2 completion.

Dependency review is the final delivery check. Runtime versions and shaded
classes are inspected separately from Maven's declared graph; preserve the M1
baseline rather than automatically upgrading transitive dependencies. See
docs/m2-tor.md for observed dependency risks and current run evidence.

## Linux M1 port — 2026-10-31

Run `bash scripts/runners/run-controlled-crawl.sh` from the repository root after building. Requires Bash,
Python 3.12+, JDK 17+ and Docker Compose; optional JAVA_HOME / PYTHON select tools.
It preserves the Windows sequence and verifier, unique `.runtime/m1/<project>/`
evidence, two crawl exit records, snapshots and retained RocksDB volume. Cleanup
must never use `down -v` or remove the frontier volume. Compose invokes normalized
copies of M1 shell helpers explicitly, so CRLF and missing executable bits work.

Linux local checks: both Compose files validate, Bash syntax passes, 31 Python
tests pass (one optional HTTP smoke skipped). Offline Maven clean verify passes
with JDK 21.0.13 / Maven 3.10.0 using a copied writable cache; default ~/.m2 locks
are unwritable in this session. Maven has no managed tests. Full Linux integration is accepted from user-run
Omarchy evidence `.runtime/m1/onionall-brawler-m1-e55203e97536/`, rechecked offline:
assertions_passed=true, crawl_evidence_passed=true, runtime_verified=true,
six frontier records, identical before/after state, resumed pending work and
minimum document gap 2.003614258s. Both crawl exits are zero; cleanup logs confirm
container/network removal and retained-volume inspection confirms RocksDB retention.
Crawler logs have no ERROR/FATAL and 76 WARNs: 12 unavailable cgroup metrics,
two ZooKeeper defaults and 62 resolved shutdown waits. Regression probe ERROR/WARN
records are intentional transport-failure/invalid-URL tests; both checks pass.
No blocking failure was found. Agent Docker access remains denied; this is saved
host evidence, not an agent Docker rerun. Windows acceptance remains valid.
See docs/m1-controlled.md for the verification record.

## Evidence and current state

M1 is accepted on 2026-10-31 from the user-run Docker evidence `.runtime/m1/onionall-brawler-m1-df35366e0994/`, independently rechecked by the agent's offline verifier. All assertions, crawl evidence and both process exits pass; six frontier records persist across graceful recreation, pending work resumes, HTTP 404 is FETCH_ERROR, robots/deduplication/scope pass and minimum document gap is 2.00265402s. Both crawler logs contain zero ERROR/FATAL and 59 WARNs total: 12 unavailable cgroup metrics, two ZooKeeper defaults and 45 shutdown waits, all resolved. Backend logs have no WARN/ERROR. result.json records container removal and retained volume. Docker Engine was not rerun by the agent; evidence is host-run. M0 is unchanged. Tor, search/OpenSearch, crash recovery, distributed deployment and Tika document parsing are not verified. See docs/m1-controlled.md.

This is a bootstrap-stage project generated from the Apache StormCrawler 3.7.0 Maven archetype, not yet an onion search engine. The repository contains a Flux topology and configuration, plus a new Kotlin M2 transport adapter; it has no search/domain application implementation. The topology declares URLFrontier input and status updates, fetching, sitemap/feed and HTML parsing, optional Tika parsing, and `StdOutIndexer` output. On 2026-10-30, Java 17.0.12 / Maven 3.9.16 built the shaded JAR successfully offline; Flux dry-run constructed one spout, nine bolts and 15 streams. The user then ran scripts/runners/run-startup-check.ps1 on the Windows host. Reviewed service/console logs confirm all nine bolts prepared (including Tika configuration loading), the spout opened/activated, 14 empty-frontier RPCs completed and LocalCluster shut down with crawler exit 0. URLFrontier closed RocksDB on orchestrated SIGTERM (143); Compose container/network removal is user-reported. M0 is accepted for this empty-frontier local baseline. Actual fetching, document parsing, status writes, durable recovery and search remain unverified. `StdOutIndexer` is not a searchable index. See `docs/m0-baseline.md` for evidence, hashes, warning assessment and limits.

The default M0/M1 topology has no Tor transport. The separate M2 stack now has a bridge-enabled transport and v3 validation, but its full acceptance is BLOCKED. There is no accepted production fail-closed guarantee, autonomous cross-service discovery, search API, desktop interface, graph storage or AI integration. A standalone M1 test harness now exists under test/m1, with Python verifier/fixture tests and Java gRPC/filter probes; it is not wired into Maven test. Full controlled Docker runs are accepted on Windows and Omarchy Linux; this establishes only the synthetic M1 crawl and graceful recreation contract.

M0 is committed as `164c2ba`. M1 uses a separate internal Compose network, synthetic fixture, named RocksDB volume and generated Flux with the stock SimpleFetcherBolt. Test-only Java/Python checks remain outside Maven test.

The user reports that the initial Maven build succeeded, Entire is enabled, Codex hooks were approved, and checkpoints sync to `origin`. On the user's host `entire doctor` reported installed hooks and approval records, and `entire status` showed Codex. A sandboxed session may not be able to read `.codex` or Git internals, so its diagnostic can differ from the host. Check actual host status when relevant; do not modify hook approvals to work around sandbox restrictions.

## Repository map

- `pom.xml` — Maven project `dev.onionall.brawler:onionall-brawler:0.1.0-SNAPSHOT`, Java source/target 17 and shaded JAR configuration.
- `crawler.flux` — Storm Flux topology, component parallelism and stream wiring; loads StormCrawler defaults and `crawler-conf.yaml`.
- `crawler-conf.yaml` — fetcher, URLFrontier, parser, scheduling and metrics settings.
- `src/main/resources/` — URL, regex, parse and JSoup filter configuration. M2 Kotlin transport lives under src/main/kotlin; these resource files remain shared.
- `README.md` - project status, build and empty-frontier runtime instructions.
- `compose.m0.yaml` - host-verified two-container empty-frontier M0 runtime; ephemeral RocksDB, internal network, no published ports.
- `scripts/cli/build-project.ps1` - verified Windows build helper; supports JDK selection, IntelliJ Maven discovery and offline cache.
- `scripts/runners/run-startup-check.ps1`, `scripts/runners/run-startup-container.sh` - host-run baseline orchestration, readiness wait, bounded local run and cleanup; agent reviewed logs, not an agent runtime rerun.
- `docs/m0-baseline.md` - 2026-10-30 M0 acceptance evidence, warning assessment, limits and M1 checklist.
- `compose.m1.yaml`, `scripts/runners/run-controlled-crawl.sh`, `scripts/runners/run-controlled-crawl.ps1`, `scripts/runners/run-controlled-crawl-container.sh`, `scripts/runners/frontier-volume-init.sh` - controlled M1 orchestration; host verified; M1 accepted after volume ownership, pacing and verifier corrections.
- `test/m1/` - synthetic Python HTTP fixture, M1-only settings/resources, Java frontier/filter checks and Python evidence verifier/tests. Test Java sources are not application code and are compiled by the M1 helper, not Maven.
- `docs/m1-controlled.md` - M1 run command, evidence contract, observed local checks, retention and limits.
- `.gitignore` — ignores Maven output, IDE files, common secrets and local crawl data.
- `.codex/hooks.json`, `.entire/` — Entire/Codex integration configuration. Preserve existing settings.

## Dependencies and build

Declared direct dependencies in `pom.xml`:

| Dependency | Version | Role |
| --- | --- | --- |
| `stormcrawler-core` | 3.7.0 | Crawler components and protocols |
| `storm-client` | 2.8.9, provided | Storm runtime API; supplied by the Storm runtime |
| `flux-core` | 2.8.9 | Flux topology loading |
| `stormcrawler-tika` | 3.7.0 | Tika parsing integration |
| `stormcrawler-urlfrontier` | 3.7.0 | Frontier spout and status updater |
| `urlfrontier-client` | 2.4 | URLFrontier client, including seed injection |

Use JDK 17, Maven, and the external Storm/URLFrontier services appropriate to the chosen execution mode. There is no Maven wrapper. `compose.m0.yaml` provides a host-verified empty-frontier baseline using URLFrontier 2.4 and Storm 2.8.9-jre17. The Storm container logged Eclipse Adoptium Java 17.0.20; frontier Java version and image digests were not captured. Lifecycle plugins are pinned: clean 3.2.0, resources 3.3.1, surefire 3.6.0 and jar 3.5.1. From the root, the documented build is:

```sh
mvn clean package
```

The shade plugin packages `target/onionall-brawler-0.1.0-SNAPSHOT.jar` with Flux as the manifest main class. The README documents build and M0 commands. The host run verified `storm local ... --local-ttl 30` and URLFrontier on `localhost:7071` via a shared container network namespace. RocksDB initialized in temporary /crawldir/rocksdb; persistence across restarts is deliberately absent from M0 and remains M1 work. Seed injection has not been tested.

On 2026-10-30 Maven 3.9.16 was found in IntelliJ IDEA 2026.2.3 and used with the installed Oracle JDK 17.0.12. Online resolution failed for repo.maven.apache.org. A workspace-local copy of the existing Maven cache enabled a fresh offline clean package (BUILD SUCCESS, 27.757 seconds), also verified through scripts/cli/build-project.ps1 (19.071 seconds). At those M0 builds no repository-owned tests existed or ran; the later standalone M1 tests run separately from Maven. `.runtime/` is ignored and contains disposable dependencies and logs; the host cache was not modified. Record the command, environment and outcome whenever verification is performed.

### M0 gaps and resolved references

- README now documents the project vision, actual status and missing Tor transport; the nonexistent Compose reference was replaced by compose.m0.yaml.
- No seeds are required or supplied for M0. Future permitted targets belong in ignored seeds.local.txt.
- tika-config.xml is supplied by stormcrawler-tika 3.7.0 and is present in the shaded JAR. Runtime logs confirm Tika loaded it and prepared successfully; document parsing remains unverified.
- Docker CLI 29.6.2 and Compose 5.3.1 are installed, but the desktop-linux engine pipe was unavailable. docker desktop start could not find its registry key; wsl --status returned Wsl/E_ACCESSDENIED. Do not infer unrestricted host status or modify approvals from these sandbox observations.
- Runtime recipe uses temporary RocksDB tmpfs, an internal network, no published ports and a 30-second local topology TTL. It deliberately provides no durable state; M1 must prove persistence separately.
- The host run verified images starting, all application components preparing/opening, empty-frontier gRPC interactions, topology activation and shutdown. M0 is complete for the local baseline. No M1 acceptance criterion is demonstrated by an empty run.
- Review found 0 ERROR/FATAL records and 34 WARN records: 6 unavailable cgroup metrics, 1 ZooKeeper connection-limit default and 27 shutdown waits that resolved. No blocking runtime defect was found; do not suppress warnings or change topology merely to make logs quiet.
- Agent Docker access remains denied, including post-run Compose ps/image inspect. User supplied the successful host run; container/network removal is user-reported, not independently inspected. Do not modify security settings to bypass this boundary. Preserve raw logs under ignored .runtime/: they contain runtime-generated ZooKeeper credential material and environment dumps. Commit only sanitized summaries/hashes.
- Image digests, clean-cache online build, Linux host reproduction and saved post-down inspection remain follow-ups, not claims made by M0.
- The user's commit `164c2ba` completed M0 versioning after the agent's staging attempt was denied. New M1 work is separate. Exclude unrelated .codex/config.toml and all .runtime/, target/ and Python cache content from commits. Do not bypass the Git access boundary.

## Configuration facts and constraints

The topology currently uses one worker and parallelism 1 for its components; `fetcher.threads.number` is 50, `topology.max.spout.pending` is 100, and the HTTP body limit is 65,536 bytes. The configured default revisit interval is 1,440 minutes and fetch-error interval is 120 minutes. These are configuration values, not demonstrated production behavior or safe limits for all targets. Tune conservatively for permitted tests.

HTTP and HTTPS use StormCrawler's OkHttp protocol implementation. No Tor proxy is configured in the project: onion traffic must not be attempted under the assumption that it is protected. The host filter has `ignoreOutsideDomain: true`, which may prevent the intended discovery across domains; assess actual filtering and domain handling before changing it. Regex filters reject several binary/media extensions and literal loopback and private IPv4 targets, but these rules alone are not a complete network boundary or content policy. `tika-config.xml` is dependency-supplied and packaged in the shaded JAR; Tika runtime initialization passed, but document parsing remains unverified. Internal Docker networking is configured, not a verified fail-closed Tor boundary.

## Direction and engineering guidelines

Keep discovery, fetching, parsing, scheduling, indexing, query APIs and presentation as distinct responsibilities with clear contracts. Prefer a local-first modular design and mature libraries over custom protocol or indexing implementations. Avoid premature microservices; consider distributed deployment after evidence warrants it. Ensure observable queues, crawl outcomes, errors, resource use and discovery effectiveness, and make state recovery explicit.

Architectural preferences, not implemented decisions: StormCrawler and Storm for crawling; URLFrontier for scheduling; Lucene or OpenSearch for full-text search; PostgreSQL for structured metadata if needed; Kotlin, Ktor and Koin for a future API; Compose Multiplatform for a desktop client; official Tor software for connectivity; Docker Compose for reproducible infrastructure. Select and document actual technologies when requirements and operational tradeoffs are understood.

Develop on Windows 11 and Omarchy/Arch Linux. Avoid platform-specific absolute paths in shared configuration; document shell-specific commands, runtime versions, service addresses and volume behavior. Keep credentials and local datasets out of Git. Preserve working configuration, inspect before changing, and update this file when verified architecture, commands or status change. Separate observed facts from assumptions and plans.

## Security and crawl boundaries

- Route all onion requests exclusively through the official Tor client. Fail closed when the transport is unavailable; never silently connect directly. Verify proxy routing and DNS behavior, redirects, retries and failure paths before any onion crawl.
- Treat remote content and metadata as untrusted. Do not execute downloaded scripts or binaries. Bound response size, processing time, concurrency and resource consumption.
- Crawl only authorized or publicly discoverable resources permitted to be indexed; observe applicable restrictions and access boundaries. Do not bypass authentication, CAPTCHA or access controls.
- Apply a content handling and filtering policy before persistence; avoid storing unlawful material or stolen personal data. Define retention, deletion and incident handling when storage is introduced.
- Do not assume the current URL filters prevent all access to local or private services. Design and verify network isolation and redirect validation as part of the transport boundary.

## Milestones and acceptance criteria

M0 is accepted based on the host-run evidence described above and in docs/m0-baseline.md. M1 is accepted for the controlled synthetic crawl and graceful recreation; M2–M8 remain open; no milestone is complete solely because configuration exists. Nimbus/supervisor/ZooKeeper messages in M0 belong to LocalCluster, not a verified distributed deployment. Tor and OpenSearch are not provisioned or verified.

| Milestone | Acceptance criteria |
| --- | --- |
| M0 — Foundation | Reproducible Java/Maven build; documented prerequisites and exact commands; verified topology startup and shutdown with required services; configuration and README gaps resolved or explicitly recorded. |
| M1 — Controlled crawling | An explicitly permitted test site is fetched, links are extracted, and crawl state survives a service restart using a documented persistent backend; rate limits and results are observable. |
| M2 — Tor transport | Official Tor client integration demonstrated; onion requests traverse the proxy, and unavailable Tor, redirects, DNS and retry paths fail closed under meaningful checks. |
| M3 — Onion discovery | Seeds are ingested, onion v3 addresses validated, permitted links extracted and deduplicated, and discovery metrics reported; cross-service filtering behavior is verified. |
| M4 — Search index | Collected, policy-approved documents are indexed and retrievable by keyword with relevance ranking and source metadata; indexing failures and updates are handled. |
| M5 — Application API | Kotlin/Ktor/Koin API exposes appropriate search, crawl management, statistics and configuration operations with documented contracts and access controls. |
| M6 — Desktop interface | Compose Multiplatform client supports practical search and exploration against the API on supported platforms. |
| M7 — Advanced research | Content changes and service relationships can be explored; semantic search and local AI assistance are optional and independently evaluated. |
| M8 — Scaling | Distributed Storm deployment and larger permitted crawls are evaluated using measured throughput, recovery, resource use and operational costs. |

## Current boundary

M0 and M1 are accepted. M2 is now authorized and in progress; acceptance is pending. Repeat M1 on Linux with `bash scripts/runners/run-controlled-crawl.sh` or on Windows with `./scripts/runners/run-controlled-crawl.ps1 -JdkHome "$env:ProgramFiles/Java/jdk-17"`; each run creates a fresh project and retains its volume. Keep raw evidence ignored, never weaken assertions, and never infer Tor/search readiness from this fixture run.

## M2 recovery preflight — 2026-10-31

Recovery remains BLOCKED. Bounded clean native Tor75s and outbound-container
Tor95s both fail to finish bootstrap (host10%, container14%). TCP to sampled
relay ports succeeds, normal HTTPS verifies, host NTP is synchronized. Exact
host/upstream filtering cause is unproven; firewall inspection lacked privileges.
No Kotlin, networking policy or TLS trust changes were made. scripts/runners/run-tor-acceptance.sh now
runs test/m2/tor_preflight.py before Maven/full stack: existing pinned image,
no ports/mounts, temporary state,90s bound, bootstrap100 gate and JSON cleanup
result. Build image once if missing. Sixteen Python tests pass; JVM unchanged,
no Maven rebuild. Evidence remains ignored under .runtime/m2/recovery/.
See docs/m2-tor.md for findings and the optional private-bridge investigation.


## Git & Entire Continuous Checkpoint Policy

- This repository uses Entire to preserve agent development context and checkpoints.
- Agents are authorized to create and push meaningful, cohesive Git commits to
  this repository's existing configured origin remote during normal development.
- Commit and push after verified, reviewable implementation phases rather than
  waiting for the final milestone. Run relevant checks before each commit.
- Clearly label partial or incomplete functionality; never claim verification
  that was not actually performed. Do not create empty or meaningless checkpoints.
- Maintain AGENTS.md when architecture, environment, milestone state or operating
  procedures materially change, preserving existing architectural instructions.
- Preserve Entire hooks and configuration. Never disable Entire to bypass an issue.
- Never commit secrets, private bridge lines, onion private keys, runtime evidence,
  Docker volumes or credentials.
- Never use force-push or destructive history rewriting, or overwrite unrelated
  user modifications. If push fails, investigate/report the cause rather than force.
- This standing authorization does not permit pushing to unrelated remotes or
  repositories.


## Bridge integration and hardening audit in progress — 2026-10-31

Latest explicit audit instruction forbids creating new commits unless requested;
leave current bridge/hardening changes reviewable and uncommitted. Earlier policy
checkpoint3e108a5 predates that restriction. Preserve standing Entire policy.

The host obfs4 solution is now reproduced in the container preflight: pinned Tor
0.4.9.11 + obfs4proxy0.0.14, bootstrap100/current control100, SOCKS ATYP3,
HTTPS IsTor=true and obfs4process observed; container cleanup succeeds. This is
not full M2 acceptance. Private bridge-only config lives in ignored .secrets/m2,
mode600, read-only mounted into both Tor clients. No direct fallback is allowed.
See docs/m2-bridges.md for native Linux/Windows shared Python runner instructions.

Hardening verifier requires both scenario-exits.json codes to be integer0 and
all three persistent volumes retained. Bootstrap log summaries are historical,
never live connectivity. Crawler rootfs is read-only; /tmp must be exec,nosuid,
nodev for RocksDB JNI. Actual dry-run confirmed this requirement. Host JDK tools
are checked for same-JDK17+; native Windows execution remains unverified.

Real bridge crawl found a pinned HTTP client idle-stream reuse bug after implicit
HTTP/1.0 close. Focused reproducer failed before the fix; TorProtocol now has
zero idle pooled connections with retries still disabled. Both implicit/explicit
robots→root cases pass; Maven verify17tests passes. No fixture assertions weakened.
Run d42486ff0e09 remains failed/interrupted evidence with all volumes retained.
Run f269f7dd28b8 proved real onion crawl/persistence/M1 but was correctly rejected
for a too-short delayed-response test and recovery before onion availability.
Those test infrastructure defects are corrected: normal10s request timeout for
15s delayed fixture, and actual controlled onion /health readiness after Tor
returns. Run04a0afbf1294 then failed initial controlled onion availability despite successful
bridge bootstrap/SOCKS preflight; direct-egress probes still denied access and
cleanup retained all three volumes. Initial availability now uses the same
bounded90s observed /health readiness as recovery. These final readiness changes
have focused tests but have not completed another full integration. M2 remains
BLOCKED; do not launch repeated long full runs without resolving availability.

Codex machine-specific notify was removed from project config without changing
user settings. Entire hooks are unchanged and doctor/shell checks pass on Linux;
Windows needs sh/entire on PATH and remains a runtime validation item. Operational
findings and limits are recorded in docs/m2-hardening.md. No automatic volume
pruning or deletion helper is introduced.


Final hardening audit results:41Python M2 tests,17Maven tests and17actualJRE17
socket/policy tests pass. Entire doctor and hook syntax pass on Linux; native
Windows and desktop notification delivery were not executed. No new commits
were made after the explicit audit prohibition. Changes remain in the working
tree. Full integration has real passed stages and preserved failures, not a
passing M2 result. See docs/m2-hardening.md and ignored bridge-integration reports.

## Recovery contract implementation — 2026-11-03

Acceptance now attributes unassisted/assisted transport separately; the strict full M2 gate remains unchanged. Internal-only deterministic Storm/TorProtocol/Frontier recovery and three-error terminal metadata characterization pass in57fb824e2d5b; terminal reason is still discarded by stock Frontier. 43 targeted JVM/113 Python M2 tests pass. Single retained/cold attempt4c5fbf2c0766 ended INCONCLUSIVE before baseline: gateway startup health120s failed, pair0, cleanup/retention/host checks pass. Read the latest recovery-execution-ledger entry for commands and evidence. Full M2/M3 remain BLOCKED. Preserve user documentation consolidation.

## Local archive MVP — 2026-11-03

ArchiveStore owns H2 catalog/schemaV1 and SHA256 HTML objects, not scheduling.
ArchiveParseFilter commits before parser emission; ArchiveStatusUpdaterBolt.store
records the installed scheduler outcome before the normal Frontier write/ACK.
CLI: crawl plan/show/approve/status, blocked start(exit7), archive import/export,
pages/page show/search-local, assisted search-provider(no scraping). Plans are
same-origin/depth0..5/pages1..100; approval is not admission. H2 is single-process
embedded, callbacks open/close stores; concurrent CLI may report catalog busy.
Source/runtime/CLI contracts: docs/archive-mvp.md. Exact successful local
Storm/TorProtocol/Frontier A→B→C/retry/archive/reopen and Recovery evidence:
docs/engineering/archive-execution-ledger.md.105 JVM/113 M2/16 CLI/2 fixture tests
pass; durable admission and M2/M3 qualification remain blocked. Existing RocksDB
volumes are not migrated and no external Tor experiment is repeated here.

## M4.1 durable admission — 2026-11-03

Configured start now uses the versioned owning Frontier server (frontier-server/)
for sync-WAL reservation+quota+enqueue; no stock API/local-counter fallback.
DurableDiscoveryFilter preserves rejected graph edges; H2 schemaV2 is catalog
only. Child-owned archive-wide runtime lock/deadline survives CLI termination.
Routing: docs/m41-durable-admission.md (CLI/deployment), frontier-server/README.md
(server/schema/rollback), docs/engineering/m41-execution-ledger.md (executed tests).
Packaged controlled acceptance a7c09e2448f4 passes, as does actual Recovery
7d7260899b6d. Real-Tor M2/M3 remain blocked. Preserve user .gitignore changes.
No push is authorized for this task.
