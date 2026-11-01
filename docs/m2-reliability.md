# StormCrawler / frontier reliability review — 2026-11-01

Status: M2 remains BLOCKED. This pass used installed StormCrawler3.7.0 bytecode,
repository configuration, saved evidence and local tests. No Docker/Tor/public
network experiment or full M2 gate was executed; gateway/controller unchanged.

## Actual processing contract

SimpleFetcherBolt catches protocol/robots exceptions and emits FETCH_ERROR with
fetch.exception. AbstractStatusUpdaterBolt.execute increments fetch.error.count;
at max.fetch.errors (default3) it transitions to ERROR. DefaultScheduler.schedule
uses fetchInterval.fetch.error=120 minutes normally,1 minute in the M2-only outage
configuration; fetchInterval.error=-1 yields no next date. Successful FETCHED
resets consecutive error count and error.cause/source/message. This is a fixed
retry interval with a finite consecutive-fetch-failure budget, not exponential
backoff. The normal1440-minute revisit is separate from error retry.

New FrontierSchedulingTest invokes the actual installed updater and scheduler,
with a small in-memory storage sink and real metadata filtering. New updater
instances receive the previous filtered metadata: errors1/2 retain their count
and schedule, error3 becomes ERROR without a next date; success resets the budget
and the next error starts at1. It is a dependency contract test, not a disk or
RPC durability test. Initial test setup needed the real scheduler.class and a
collector for the terminal deletion stream; failed setup logs are preserved.

URLFrontier StatusUpdaterBolt has a semaphore controlled by the real
urlfrontier.max.messages.in.flight setting (10 in M2). This limits updater RPC
writes, not all fetcher queues. Its onNext(AckMessage) acknowledges stored tuples;
the spout delegates ack/fail to AbstractQueryingSpout's in-process tracking and
closes its gRPC channel at shutdown. This supports asynchronous at-least-once
processing; it is not an exactly-once guarantee or proof of fsync on host crash.
A crash before status persistence can cause redelivery without incrementing the
persisted error count. The3-error budget does not bound every possible Storm
redelivery/crash loop.

M2 max.spout.pending=10, one bucket and two URLs/bucket bound its configured
controlled flow. Default FetcherBolt queue limits remain -1; no universal bounded
queue claim is made. Existing RPC backpressure and Storm pending limits are
reused; no additional queue/retry infrastructure is introduced.

## Error taxonomy and confirmed correction

| Category | Meaning and limits |
| --- | --- |
| REQUEST_TIMEOUT | SocketTimeoutException / InterruptedIOException while protocol remains open; does not localize TCP/SOCKS/HTTP phase by itself |
| SOCKS_FAILURE | ConnectException; not every SOCKS failure has this Java exception type |
| ONION_UNREACHABLE | Other IOException, including EOF/TLS/upstream failures; does not prove the target rather than gateway/path is at fault |
| ROBOTS_UNAVAILABLE | Robots transport exception wrapped with its cause, or strict non200/HTML rejection without a transport cause |
| SCOPE_REJECTED | TorPolicy/redirect scope rejection, with no unchecked redirected request |
| PROTOCOL_CLOSED | Terminal/intentional cleanup; must not be diagnosed as an external outage |

Confirmed defect: an in-flight call canceled by cleanup was previously mapped to
ONION_UNREACHABLE/REQUEST_TIMEOUT and robots could wrap it as ROBOTS_UNAVAILABLE.
The network catch paths now check terminal state first and preserve IOException
cause under PROTOCOL_CLOSED. Robots cancellation propagates the same terminal
category. Existing open-protocol errors and all allowlist/robots rules remain.

A local SOCKS test proves robots transport EOF yields ROBOTS_UNAVAILABLE with
ONION_UNREACHABLE cause; a subsequent successful robots fetch is actually retried
and still forbids /blocked. Transport failure never returns/caches ALLOW_ALL.
An additional cleanup test blocks robots SOCKS negotiation, cancels it, and
requires PROTOCOL_CLOSED within2s versus configured120s. No gateway change.

Important downstream limitation: SimpleFetcherBolt's generic exception path
stores the Java exception class (usually java.io.IOException) in fetch.exception,
not the entire cause/category chain; full details remain in its error log.
protocol.tor.category on HTTP response is not a complete persisted taxonomy for
thrown exceptions, especially getRobotRules(url), which has no Metadata argument.
No new custom bolt/exception hierarchy was added just to change that contract.

## Source independence and persistence evidence

M2 intentionally tests one owned onion host with one SimpleFetcherBolt executor.
A failed fetch is bounded, but blocks that executor until timeout. TorProtocol
getRobotRules is synchronized across network I/O, serializing cache misses across
hosts sharing that protocol instance. The host-side batch supervisor also reacts
to transport failures at batch scope. Multi-source fault isolation is therefore
**not established**. No multi-host parallelism or per-host recovery promise is
inferred from the base topology's50 FetcherBolt threads; M2 derives a different
single-executor topology. Fixing this for independent production sources needs
an explicit concurrency/capacity contract, not silently relaxing the M2 fixture.

Saved fa44d93cba6b contains passed outage-pipeline stages: failed fetch persisted,
retry date honored, content recovered without reseed, completed frontier entries
unchanged. That overall run still failed other required scenarios. Saved
21def5f8ab61 confirms eight records survived recreation identically and pending
work resumed, but its outage pipeline failed before /outage. Both retained all
three volumes. These are historical observations, not fresh runs of this patch.
M1 aada9657f24a likewise records its accepted six-record persistence/restart.

Unproven: abrupt process/host crash durability, exact-once request behavior,
concurrent multisource progress, peak memory/queue bounds under load, production
retry timing with real unavailable services, and successful full M2 acceptance.
No reseed, frontier mutation, key/volume deletion or security-policy change was
introduced. Graceful recreation evidence must not be presented as crash proof.

## Executed verification

Offline JDK17 Maven test: **33pass** (TorProtocol26, frontier/scheduler2, policy5).
Python M2: **65pass**. git diff --check passes. JVM tests use loopback SOCKS only;
no Tor, container or host network changes. Evidence under ignored
.runtime/m2/reliability-review/: javap contracts, extracted installed defaults,
initial setup failures, final-all-tests.log. Maven test compiles classes but does
not rebuild the shaded runtime JAR: package before any later integration run.

No commit/push. The remaining full-gate recovery blocker is unchanged.
