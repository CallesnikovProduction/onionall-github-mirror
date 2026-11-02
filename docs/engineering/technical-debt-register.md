# Technical debt register

Inventory baseline: `39257ef`, 2026-11-02. Scope: first-party `src/`, recovery
`scripts/` and `test/`; generated output, `.runtime`, dependencies and vendored
code are excluded. This register records evidence obligations, not a claim that
every open TODO is a demonstrated defect. Current acceptance results belong in
[the execution ledger](recovery-execution-ledger.md); historical test counts below
are attributed to their saved reports, not reruns performed by this inventory.

Dispositions: **RESOLVED** requires an implementation and an executed regression;
**TEST_REQUIRED** means a contract lacks sufficient executable evidence;
**BLOCKED** identifies an unmet prerequisite; **DEFERRED** retains an explicit
reason. Priorities: P0 blocks required correctness/recovery, P1 is a material
existing contract/reliability/security obligation, P2 is bounded qualification or
maintainability, P3 needs a future requirement. Priority is not vulnerability severity.

## Current recovery and architecture obligations

| ID / priority | Finding and ownership | Disposition / evidence | Remaining work / closure criterion |
|---|---|---|---|
| REC-01 / P0 | Controlled onion stream recovery is not repeatable across gateway/service restart. Tor path plus recovery owner. | **BLOCKED**. Corrected-endpoint incident `a3f5c6720038` reaches actual Storm robots/pending HTTP200; independent `b66bf31c112f` fails Python NO_AUTH, Python USER_PASSW, native JDK and TorProtocol before HTTP. [Incident report](../recovery/m2-socks-incident.md). | A discriminating bounded experiment, then actual crawler pending/terminal-state recovery and original full M2. A single positive stream or exit0 cannot close it. Do not rerun identical full gates. |
| ADM-01 / P0 | Durable global discovery admission is absent. Existing URLFrontier server must own reserve+quota+enqueue. | **BLOCKED**, production/durable mode already fails closed in `DiscoveryParseFilter.configure`; local planner ADMITTED is not a durable server ACK. [ADR0002](../adr/0002-durable-discovery-admission.md), [proposed ADR0003](../adr/0003-additive-frontier-admission-proposal.md). | Add server-owned atomic reservation/counter/provenance/enqueue contract, persist/rebuild its scheduling index, reject legacy API quota bypass, and pass concurrent quota/duplicate/restart/ACK-loss tests before enabling a client. Keep CLI admission disabled. |
| E2E-01 / P0 | Real Tor A→B→C discovery and interruption/lease recovery are not covered by the local HTTP variant. Harness plus frontier/worker owners. | **BLOCKED**. `test/m3/local_integration.py` explicitly uses stock HTTP and retains `m3_verified=false`. | Reuse its fixture/assertions in a separate real-onion variant only after M2 prerequisite; verify actual fetch→parse→durable admission→dequeue→fetch plus restart. Preserve the local variant and distinguish its transport in evidence. |
| SEC-01 / P1 | Baseline Tor0.4.9.11 security delivery remains unqualified. Image/package owner. | **BLOCKED**. Authenticated candidate not installed in baseline. Delivery evidence is independent of crawler functional acceptance. | Complete official signed InRelease→Packages→package hash/version verification, reproducible isolated candidate build, compatibility/bootstrap checks; only then change default. Do not infer an unavailable Bookworm version or bypass host network/TLS policy. |
| REC-02 / P1 | Local recovery scripts depend on M2 fixture, owned hostname, probe JAR and log-derived failure attribution. | **BLOCKED for independent production deployment**, not proof of a current DIRECT path. `scripts/m2_recovery.py`, `scripts/m2_supervise.py` and lifecycle boundary documented in [M2 hardening](../m2-hardening.md). | Retain one recovery owner, one bounded attempt budget, serialized launch/recovery ownership and verified actual-consumer context. A production contract must remove fixture assumptions and define restart/crash journal semantics before deployment; do not add competing supervisors. |
| NIM-01 / P1 | Nimbus cleanup could read published metadata/key before topology data commit. | **RESOLVED on explicit cleanup2 image**, original baseline retained. `docker/storm-lifecycle/nimbus-cleanup.patch`; real-Nimbus lock regression fails baseline and passes backport. | Keep source/JAR pins, upstream comparison and source patch reproducible; never remove original no-ERROR acceptance check. |
| NIM-02 / P1 | Closing cleanupTimer interrupted active dependency I/O during Nimbus shutdown. | **RESOLVED on explicit cleanup2 image**. `nimbus-cleanup-drain.patch`; real StormTimer drain regression and two strict M1 runs `25da2046fecb`, `d927d1d8835e`. | Retain drain-before-close ordering and ensure future upstream upgrade provides both publication and shutdown fixes. |
| SOCK-01 / P1 | A resolved-but-named InetSocketAddress allowed native JDK SOCKS to re-resolve a supposed pinned gateway endpoint. | **RESOLVED**, commit39257ef. `configuredProxyHostnameIsFrozenAsNumericEndpointForNativeJdk` failed before fix and passes after; USER_PASSW/remote-name fixture covers both supported native methods. | Does not close REC-01 or the new-IP replacement obligation TPR-05. Gateway recreation still requires a new protocol instance. |

2026-11-03 qualification: original target9560fd480748 FAIL at CONNECT after its
one gateway restart; incident52b269ed5fb1 PASS includes actual Storm, while
8f2036152884 fails all four clients even in the original namespace. Fresh-context
B comparison and cold-state fallback were not exercised live; neither is a
confirmed cause/fix. Full M2 remains blocked. New frontier lifecycle1a7cb4b6f722
executes unacknowledged-consumer exit, server SIGKILL/recreation, lease expiry and
ACKed-result recreation successfully. This strengthens existing at-least-once
scheduling evidence, not ADM-01's absent atomic quota/reservation/ACK-loss contract.
Strict M1 current artifact36bf86224d74 passes with cleanup/retention.

## Live source TODO markers

The baseline scoped scan found **12 TODO comment occurrences / 11 distinct IDs**:
two TorPolicy comments and ten TorProtocol comments, including duplicate TPR-01.
The TPR-02 implementation below removes one fulfilled marker; the remaining
source has **11 comment occurrences / 10 distinct IDs**.
No live TODO remains in TorScopeFilter. There is no production `TODO()` or
`UnsupportedOperationException` placeholder. No TODO is removed by this inventory.

| Original marker / priority | Actual remaining contract | Disposition / existing evidence | Minimal next action |
|---|---|---|---|
| TP-04 / P1 | Exhaustive request-control metadata consumer audit. | **TEST_REQUIRED**. `TorPolicy.checkMetadata` rejects proxy/http./set-header keys; `allUpstreamProxyOverrideKeysAreRejected` and wire no-socket tests cover enumerated forms, not every installed consumer. | Inventory actual Metadata consumers and classify control keys; add one negative fixture per control surface. Do not expand the denylist from guesses or call a generic substring check exhaustive. |
| TP-02 / P2 | Independent v3 public-key/version/checksum vectors. | **TEST_REQUIRED**. Existing vectors are generated by the repository test helper; shape/checksum rejection is exercised but not independently sourced. | Add published independent vectors with origin and expected bytes/checksum. Do not rewrite the already extracted Base32 decoder. |
| TPR-01 (two occurrences) / P0 | Controlled Tor-only routing plus resolver guard/outage denial in complete acceptance. | **BLOCKED** under REC-01. Wire ATYP3/no-DIRECT tests and scoped resolver evidence exist; they are not system-wide DNS-leak proof or original full M2. | Close targeted actual-crawler recovery, then execute full M2 with original security assertions. |
| TPR-02 / P1 | Robots cache expiry, concurrent misses and cross-host failure isolation. | **RESOLVED** in this pass. The original global method monitor was held across HTTP; a healthy approved origin could not obtain rules while another origin stalled. The new loopback HOL regression fails the original implementation, then passes with per-origin loading and short shared-cache critical sections. Six new `TorRobotsCacheTest` cases and 35 existing `TorProtocolTest` cases execute successfully. | Keep per-origin loader ownership, monotonic ten-minute TTL, 16-entry FIFO cache, fail-closed uncached failures and terminal cleanup. Source marker removed only after these checks. No real-Tor or distributed recovery acceptance is inferred. |
| TPR-03 / P1 | Cleanup ownership after partial real-topology prepare failure. | **TEST_REQUIRED**. Terminal/idempotent cleanup and cancellation are unit-tested; those do not prove Storm ProtocolFactory frees every partially created instance. | Fault-inject the installed factory/topology prepare boundary and assert cleanup for all created clients/threads/calls. Share verification with TPR-11 instead of duplicating ownership logic. |
| TPR-04 / P1 | Security-relevant upstream configuration aliases. | **TEST_REQUIRED**. `unsafeHttpConfigurationFailsBeforeStartingTransport` and fixed proxy/TLS/redirect builder enforce checked settings; no bypass via another alias has been demonstrated. | Enumerate supported installed-component settings; test actual aliases and prove ignored settings cannot affect the fixed client. Preserve policy fail-fast where required. |
| TPR-05 / P1 | Gateway replacement/new IP and client lifecycle. | **TEST_REQUIRED**. Numeric endpoint pinning is fixed (SOCK-01); old client intentionally keeps the selected address. | Controlled resolver/IP replacement: existing client retains old endpoint, fresh instance selects new endpoint, cleanup rejects reuse, no target DNS/DIRECT route. Do not close on same-container restart alone. |
| TPR-14 / P1 | TLS/HTTP2/auth follow-ups stay bounded and SOCKS-only. | **TEST_REQUIRED**. Automatic redirects/retries are disabled and redirect escape tested; no exhaustive installed OkHttp follow-up claim. | Owned TLS/HTTP2/auth challenge fixtures with connection/request counts and explicit SOCKS-only destinations. Preserve existing strict TLS. |
| TPR-07 / P2 | Exhaustive SOCKS reply-code/TLS exception taxonomy. | **TEST_REQUIRED**. Causes are retained; REQUEST_TIMEOUT/SOCKS_FAILURE/ONION_UNREACHABLE are deliberately coarse. No observed wrong retry decision is inferred. | Enumerate reply codes and TLS failures using deterministic fixtures; assert category, cause, no HTTP/direct fallback and bounded retry ownership. |
| TPR-08 / P2 | Aggregate response-header memory/size behavior in pinned OkHttp. | **TEST_REQUIRED**. Body size and mandatory `protocol.md.prefix` have regressions; they do not establish an aggregate header bound. | Execute oversized header count/line/aggregate cases against the selected OkHttp version and test bounded rejection before making a memory-limit claim. |
| TPR-11 / P1 | Actual Storm shutdown/partial-prepare ownership of ProtocolFactory. | **TEST_REQUIRED**, same ownership boundary as TPR-03. `cleanupCancelsBlockedSocksCall` and `cleanupCancelsBlockedRobotsWithoutReportingNetworkOutage` establish local instance behavior only. | Exercise real worker stop during blocked request and partial prepare; assert no new calls after cleanup, bounded cancellation and accounted frontier lease/retry state. |

## Already closed source obligations retained as contracts

These are historical findings from [the Kotlin audit](../kotlin-todo-audit.md),
not newly executed closure claims:

- TPR-06: ambiguous/missing redirect Location is rejected; a single normalized
  target is exported, 304 has its own non-redirect contract, and no automatic
  redirect occurs. Tests include order-swapped duplicates and out-of-scope URLs.
- TPR-08 metadata namespace: SimpleFetcherBolt applies the one configured
  nonblank `protocol.md.prefix`; TorProtocol does not double-prefix headers.
- TPR-10: malformed UTF-8/BOM-selected UTF-16 and parser runtime failure become
  ROBOTS_UNAVAILABLE, never ALLOW_ALL/error-cache success. Empty/unmatched-agent
  robots retain parser semantics. Keep those tests when updating dependencies.
- TorScopeFilter: configure/use-before-configure, null metadata, source authority,
  normalization and IOException→null behavior have named regressions. No universal
  framework-order guarantee is inferred from fail-fast behavior.
- FrontierSchedulingTest uses the real installed status/scheduler contract with
  an in-memory sink: persisted consecutive errors stop at three, successful retry
  resets the budget. It is not a RocksDB/lease/ACK-loss test.

## Deferred work and intentionally retained test sentinels

| Finding / priority | Disposition and reason |
|---|---|
| Prior SOCKS per-request/per-source circuit-isolation request / P3 | **DEFERRED** until a deployment privacy/isolation requirement exists. TCP pool policy does not prove Tor circuit isolation; never weaken Tor's existing isolation to make tests green. |
| Prior TPR-12 pooling optimization / P3 | **DEFERRED**. Zero idle connections is the verified compatibility policy for implicit HTTP/1.0 closes. Optimization needs representative latency/concurrency evidence and must preserve recovery. |
| Prior TPR-13 split timeout tuning / P2 | **DEFERRED**. Keep the finite total deadline. Change connect/read/call budgets only with bounded measured evidence, not a larger timeout to conceal failure. |
| Missing native Windows PTY/signal/package evidence / P2 | **TEST_REQUIRED on Windows**. POSIX terminal/signal tests and Linux Docker-container account lookup intentionally skip unsupported platforms. Do not claim Windows runtime from regex/static parity tests. |
| Optional M1 localhost HTTP smoke / P2 | **DEFERRED/opt-in**, explicitly gated by M1_HTTP_SMOKE=1. The fixture smoke is not required for the separate controlled Docker acceptance. Execute only when local sockets are allowed; do not remove the skip to alter reported counts. |
| Build-dependent CLI/PTY suite / P2 | **PREREQUISITE**, explicit skips when target/onionall is absent. The delivery gate must build the bundle first and run the suite; absent build is not a PASS. |
| `FrontierSchedulingTest` UnsupportedOperationException | **INTENTIONAL test-double sentinel** for unsupported Tuple methods, not production placeholder behavior. Unexpected collector calls separately fail with AssertionError. |
| `mktemp` XXXXXX patterns | **FALSE POSITIVE** for a case-insensitive XXX scan, not debt. They request secure random temporary names. |

No JUnit `@Ignore`/`@Disabled` or unexplained unconditional test skip was found in
the scoped Java/Kotlin/Python tests. Conditional platform/build/account-lookup
skips are listed above. No FIXME/HACK production marker was found by this scan.

## Durable admission feasibility checked locally

Read-only `jar tf`/`javap` inspection of installed dependencies confirms:

- `urlfrontier-client2.4.jar` contains CLI/client classes, not a RocksDB server.
- `urlfrontier-API2.5.jar` exposes `putURLs`, `getURLs`, `getURLStatus`,
  `setCrawlLimit`, `listURLs`, `countURLs` and queue/control calls.
- Its AckMessage.Status has OK/SKIPPED/FAIL/UNRECOGNIZED; there is no stable
  reserve-if-under-global-quota result, immutable session-open or atomic
  reserve+enqueue RPC in the installed service interface.
- The first-party tree contains no frontier server implementation/proto/storage
  transaction source. Application code cannot safely open the server's live
  RocksDB files or turn separate count/put RPCs into one transaction.

The smallest compatible design remains an additive service in the **existing
server-owned storage transaction**, as specified by ADR0003, with an authenticated
pipeline capability and immutable session authority. Its atomic commit must
include reservation, quota, bounded provenance and existing enqueue/index update;
the legacy creation path must not bypass quota. If enqueue cannot join that
transaction, revise the server design before adding a client. Import/pin the
server's owning source/build in an explicitly scoped implementation, then execute
concurrency/restart/ACK-loss conformance before capability activation. No new
queue, coordinator, production mode or CLI admission is introduced by this
register. No majority-closure count is manufactured from missing evidence.

That source/build feasibility is now independently checked: all14 tag2.4 service
files compile offline against the exact cached image JAR and nine unchanged
upstream RocksDB tests pass. An executed negative probe proves that adding a
fourth CF prevents stock2.4 reopening the DB. Missing Maven plugins no longer
block this source-patch path; compatible RPC generation, transaction/auth guards
and compatible rollback remain genuine implementation prerequisites. See
[the pinned implementation plan](frontier-admission-implementation-plan.md).

## Inventory verification

The initial inventory executed targeted `rg`, file reads and local `jar tf`/`javap`.
A subsequent explicitly scoped TPR-02 repair executed local loopback SOCKS tests:

```sh
JAVA_HOME=<jdk25> PATH=<jdk25>/bin:$PATH mvn -o -B \
  -Dmaven.repo.local=.runtime/maven-linux-repository \
  -Dtest=TorRobotsCacheTest,TorProtocolTest test
```

Before-fix HOL test: TimeoutException while the healthy origin waits behind the
blocked origin. After-fix: **6 new + 35 existing tests PASS**, with no skipped case.
Evidence: `.runtime/autonomous-recovery/robots-cache/before.log` and `after.log`.
No Docker, real Tor or external network was used; only controlled loopback TCP.
The principal recovery owner records full acceptance and delivery separately.

The repair retains the public no-argument constructor used by Storm, with an
optional LongSupplier only for deterministic monotonic-clock injection. A load
lock exists per validated `scheme://authority`; strict TorPolicy permits at most
16 hosts × two schemes × absent/default explicit port = 64 forms. Load locks
survive cache eviction to prevent duplicate in-flight loaders, then clear at
terminal cleanup. Network/parser work never holds the shared-cache monitor.
Cleanup cancels active calls before clearing cache/load ownership; a closed
check under that monitor prevents post-cleanup cache publication.
