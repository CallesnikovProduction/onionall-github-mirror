# Recovery implementation review

Endpoint baseline: `39257ef`; cache repair: `6fc4597`; frontier diagnostic:
`f62385d`; retained-state diagnostic checkpoint: `c63044d`. This review
describes written code and distinguishes local contracts from incomplete external
acceptance. M2/M3 and production readiness remain BLOCKED pending the ledger's
strict gate results and independent Tor security delivery.

Tested application stack stays Java25/Kotlin2.3.21, Storm3.1.0 with explicit
cleanup2 backport, StormCrawler3.7.0, crawler-commons1.6, OkHttp5.4.0,
URLFrontier client/server2.4 with selected API2.5. No dependency version was
upgraded. Existing shaded gRPC/protobuf/API overlaps remain build warnings, not
newly solved compatibility/security claims; the legacy integration positives
do not qualify a new admission RPC generator/runtime combination.

## Endpoint and transport ownership

`src/main/kotlin/dev/onionall/brawler/tor/TorProtocol.kt`, `configure`, validates
TorPolicy, bounded deadlines/body size and nonblank `protocol.md.prefix`, resolves
the SOCKS gateway once and reconstructs its InetSocketAddress with numeric
`hostAddress`. Native JDK SOCKS otherwise re-resolves a resolved-but-named address.
The before/after regression is
`TorProtocolTest.configuredProxyHostnameIsFrozenAsNumericEndpointForNativeJdk`.
Only gateway resolution is allowed: onion destinations remain unresolved for
SOCKS ATYP3; the OkHttp DNS implementation throws. The client has an explicit
SOCKS proxy, no automatic redirect/retry, strict TLS and zero idle connections.
Scope/metadata validation precedes call creation. These wire tests do not prove
system-wide absence of DNS leaks.

The selected gateway address is intentionally immutable. A new container/IP
requires a newly configured protocol/JVM. Restarting the same gateway container
is different from recreation. No controller is authorized to resume an old JVM
against an unverified replacement IP.

## Local recovery boundary

`scripts/m2_recovery.py::recover` accepts only its exact isolated M2 project and
evidence path. Exclusive journal creation bounds execution to one attempt and
prevents parallel owners/repeated restart loops. Only a recorded failure after
SOCKS greeting qualifies. It pauses project crawler consumers, stops/restarts
the same gateway, verifies unchanged container identity, then requires controlled
onion HTTP200/ready over ATYP3 and three JVM robots/health pairs before unpausing.
Any intermediate failure retains quarantine and stops the gateway. Bootstrap100
alone does not pass. Persistent state is neither replaced nor deleted.

`scripts/m2_supervise.py::supervise` owns at most two finite local crawler batches
and one recovery. Failed JVM exits precede recovery; a new batch gets fresh
protocol/cache state and existing frontier scheduling, without reseed. Existing
quarantine prevents launch. SIGTERM is handled by the CLI wrapper and stops its
exact owned crawler. Journal replay/resume after supervisor crash is deliberately
not implemented; an existing journal refuses implicit recovery continuation.

These scripts are **local M2 tooling**, using the M2 fixture, hostname/evidence
layout and probe JAR. They are not an independently deployed production service
or distributed Storm worker recovery. Pausing an old live JVM does not reset
its socket deadlines or frontier lease. The targeted runner's paused consumer is
idle Python; the actual Storm process is freshly launched afterward. Do not
promote this surrogate to proof of live-worker pause/resume. A second supervisor
has not been introduced to hide that boundary.

`test/m2/socks_incident.py` compares four fresh clients in one namespace; optional
`--cold-gateway-on-failure` is diagnostic only. Its override uses a new directory
in the retained tor-state volume. Old state/keys remain untouched, replacement
IP is used only by fresh clients, and default recovery is unchanged. The passing
c8d4cc4db8e8 run used normal restart; cold replacement was **not exercised**.

## Robots concurrency and shutdown

`TorProtocol.getRobotRules` formerly held the instance monitor through the entire
network load, serializing unrelated permitted origins. It now gets a stable
per-origin load lock under a short shared-cache monitor, checks monotonic TTL,
then calls `loadRobotRules` without that shared monitor. Same-origin misses
coalesce; distinct origins progress independently. Successful rules alone are
published; strict status/HTML/decoding/parser failures remain uncached and closed.
The ten-minute TTL starts when loading completes. FIFO rules cache stays at16;
load-lock ownership survives eviction (at most64 policy-admitted origin forms).

`cleanup` is terminal/idempotent. Under the existing lifecycle lock it rejects
new calls, cancels registered calls, evicts connections and closes the dispatcher;
it then clears cache/load ownership without waiting on a network-held origin
lock. The closed check under the publication monitor prevents a late cache write.
Public no-argument construction is retained for Storm. Regression:
`src/test/java/dev/onionall/brawler/tor/TorRobotsCacheTest.java` (six cases,
including loader/waiter cancellation), alongside 35 existing protocol tests.
No custom future/executor/cache dependency was needed.

Selected algorithm: per-key single-flight with a bounded FIFO result cache.
Hash lookups/publication are expected O(1); retained ownership is at most64 keys
and16 rule entries under the validated policy. A miss on A no longer extends
B's critical section by A's network latency. Same-key waiting still serializes
loads and transport deadlines remain finite; this is not a claim of worker-wide
fairness or production latency percentiles. Exponential/jitter retries, adaptive
concurrency and a second queue/state machine were not added: the installed
scheduler already owns bounded retries, and no new measured saturation justified
competing algorithms. No new cryptography or formal-model claim is made.

## Frontier, retries and acknowledgments

URLFrontier remains scheduling/storage authority; no second queue or crawler
lease state machine was added. M2 uses the existing StormCrawler status updater
and DefaultScheduler. `FrontierSchedulingTest` executes that installed contract:
consecutive persisted errors stop at three, success resets the error count;
default error delay120min, test-only outage variant1min. This is bounded retry,
not a newly implemented exponential/jitter algorithm or RocksDB crash proof.

Read-only upstream tag2.4 source inspection at
`27363207fdb68abad7606cf249d93be55509d71a` confirms
`RocksDBService.sendURLsForQueue`: a dequeue leaves the schedule row and sets an
in-memory `QueueMetadata.holdUntil` until the requestable delay expires.
`AbstractFrontierService.getURLs` defaults that delay to30s when unspecified.
This is at-least-once processing, not durable exactly-once ownership; restart
can lose the transient hold and redeliver persisted work.

`RocksDBService.putURLItem` serializes a URL's existence key and batches the
default-CF existence pointer plus queues-CF schedule replacement atomically.
`AbstractFrontierService.putURLs` sends the corresponding status ACK after the
storage method returns. `KnownURLItem.refetchableFromDate` changes eligibility;
zero means terminal, not a retry. The existing WriteOptions do not explicitly
enable synchronous fsync; do not infer power-loss durability from recreation.
M2 snapshots exercise normal process/container persistence, not commit/ACK-loss.

The new `test/m2/frontier_lifecycle.py` / `FrontierLeaseProbe.java` diagnostic
additionally exercises actual getURLs holds and consumer exit without a Known
update, server SIGKILL/recreation, lease expiry, ACKed result and future scheduling
across another recreation. The first run1a7cb4b6f722 passes with one retained row.
This is internal gRPC, not Tor acceptance or reservation ACK-loss conformance.
Pinned source compilation/storage tests and the executed fourth-CF rollback
refusal are documented in [the admission implementation plan](frontier-admission-implementation-plan.md).

`test/m2/outage_pipeline.py` verifies a genuinely interrupted handler, exactly
one stored fetch error, unchanged unrelated rows, stored-date retry without
reseed, parsed recovery marker and exactly two handler attempts. Full current
acceptance must execute these assertions before claiming their result.

## Deliberately unchanged and unresolved

- Numeric endpoint/security policy, normalization, robots fail-closed handling,
  original acceptance assertions and verified Nimbus cleanup2 backport retained.
- No Tor internal circuit resets, host networking changes, larger deadlines,
  DIRECT fallback, new supervisor or speculative custom scheduling algorithm.
- Exact external Tor failure cause and repeatability remain open: four client
  implementations can fail before HTTP, so an application-only rewrite is not
  supported by that evidence. One positive recovery is not availability proof.
  The opt-in fresh-context comparison is a bounded A/B/A diagnostic; its live
  run8f2036152884 failed the original-A prerequisite, so it proves no namespace
  attribution. The original targeted9560fd480748 also failed before HTTP.
- Tor0.4.9.11 remains installed. Candidate delivery failed ordinary official-host
  DNS resolution; no unverified update/default pin is claimed.
- ADR0002 durable global reservation+quota+enqueue is absent. ADR0003 is proposed,
  not activated; CLI discover stays offline. Real-onion M3 is blocked by M2 and
  this storage contract. The local HTTP diagnostic cannot satisfy those gates.

Exact executed commands/results and next step: [execution ledger](recovery-execution-ledger.md).
Open P0/P1 and contract tests: [debt register](technical-debt-register.md).
