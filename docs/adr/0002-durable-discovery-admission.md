# ADR 0002 — Durable discovery admission stays fail-closed

Status: implemented by the versioned M4.1 Frontier candidate for the controlled
local workflow (2026-11-03). Stock Frontier remains fail-closed. Real-Tor
qualification is separate. See [current contract](../engineering/m41-admission-contract.md).

## Evidence

Installed URLFrontier CLI/client2.4 and selected API2.5 are distinct from the
Docker server2.4. The public API includes setCrawlLimit(key,limit,crawlID):Empty;
its CLI describes a limit for a specific queue. It also has putURLs(URLItem)
with AckMessage(OK/SKIPPED/FAIL), getURLStatus, listURLs; API2.5 adds countURLs.
None of those contracts exposes atomic reserve-if-global-count-below-N for a
unique canonical URL with a stable result after an RPC timeout. A queue limit
or getURLs lease/window is not proof of global unique-admission quota.

## Alternatives

A. Existing API only: count-before-put races. It cannot provide the required
transaction or guarantee whether a timed-out reservation committed.

B. New persistent admission coordinator: needs a durable unique ledger, atomic
counter and outbox/replay, adding another stateful runtime. It cannot open the
live frontier RocksDB from a second process.

C. Restricted single coordinator: ownership removes concurrent writers, but not
restart loss or unknown RPC outcomes. Adding WAL/outbox makes it effectively B.

Chosen direction: extend the existing frontier server/storage additively rather
than deploy a new database or another crawler supervisor. This is a separate
server/API change, not implemented by this application-only iteration.

## Required contract

- Immutable crawlSessionId/maxPages; seed consumes quota too.
- Unique key (session,canonicalURL).
- One serialized transaction: lookup existing reservation; if absent check count,
  write reservation, increment count and enqueue URL atomically.
- RESERVED / ALREADY_RESERVED / LIMIT_REJECTED, replayable for the same request.
- Retry after timeout uses the identical session/URL, not a new session/budget.
- RocksDB WriteBatch atomicity alone is insufficient for concurrent check/update:
  use transaction conflict detection or a server-owned lock covering the entire
  check + batch commit, with durability requirements stated explicitly.

Tests before activation: concurrent limit boundary, duplicate workers, restart,
commit-success/ACK-loss, retries without second enqueue/count, and seed accounting.
The original DiscoveryParseFilter remains local-test only. M4.1 uses a separate
DurableDiscoveryFilter with explicit durable-v1 mode and authenticated candidate
capability. No in-memory counter is advertised as durable; unsupported servers
remain rejected. crawlID alone is not a reservation.
