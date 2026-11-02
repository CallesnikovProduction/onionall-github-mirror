# ADR 0003 — Additive durable admission inside URLFrontier

Status: **PROPOSED, not implemented or accepted for activation** (2026-11-02).
Refines ADR0002; does not supersede its fail-closed boundary. CLI admission stays
disabled and production/durable planner mode remains rejected.

## Existing boundary

This repository contains application code and a pinned URLFrontier2.4 image,
client/API dependencies, and probes; it does not contain the frontier server's
RocksDB implementation/build. Existing putURLs/queue-limit calls cannot provide
a global reserve+enqueue transaction. A proposal must therefore identify the
server change and its conformance tests before adding a client that claims the
contract. No separate queue, coordinator process, database or shared RocksDB
file access from the crawler is proposed.

## Additive authorized interface

Add a new versioned service to the **existing** frontier server, preserving
putURLs/getURLs and existing records/wire compatibility:

```proto
syntax = "proto3";
package onionall.frontier.admission.v1;
service AdmissionV1 {
  rpc OpenSession(SessionSpec) returns (SessionState);
  rpc ReserveAndEnqueue(AdmissionRequest) returns (AdmissionResult);
  rpc GetReservation(ReservationKey) returns (AdmissionResult);
  rpc SealSession(SessionKey) returns (SessionState);
}
message SessionSpec {
  string session_id = 1;
  string crawl_id = 2;
  uint64 max_unique_urls = 3;
  repeated string approved_onion_hosts = 4;
  bytes policy_revision_digest = 5;
  uint32 max_depth = 6;
  uint32 max_sources_per_url = 7;
}
message SessionKey { string session_id = 1; }
message ReservationKey {
  string session_id = 1;
  string canonical_url = 2;
}
message AdmissionRequest {
  ReservationKey key = 1;
  string source_url = 2; // Empty only for an explicitly authorized seed.
  uint32 depth = 3;
  string discovered_at_utc = 4;
  bytes policy_revision_digest = 5;
}
message AdmissionResult {
  enum Outcome {
    OUTCOME_UNSPECIFIED = 0;
    RESERVED = 1;
    ALREADY_RESERVED = 2;
    LIMIT_REJECTED = 3;
    SCOPE_REJECTED = 4;
    SESSION_CONFLICT = 5;
    SESSION_SEALED = 6;
    RESERVATION_NOT_FOUND = 7;
  }
  Outcome outcome = 1;
  string reservation_id = 2;
  uint64 admission_ordinal = 3;
  bool enqueued = 4;
}
message SessionState {
  string session_id = 1;
  uint64 max_unique_urls = 2;
  uint64 reserved_count = 3;
  bool sealed = 4;
}
```

Field numbers/signatures are a proposed new service, **not claims about installed
API2.5**. Source ownership, upstream API namespace, protobuf generation and server
storage review are prerequisites to implementation.

OpenSession is an operator/pipeline capability, never implicitly exposed through
the CLI or untrusted fetched metadata. Bind the caller to the permitted crawl
session using the deployment's authenticated internal service boundary. Do not
advertise an unauthenticated new host port. Session ID, quota, allowlist, crawl ID
policy revision, depth and provenance bounds are immutable. Reopening identical parameters is idempotent;
any mismatch is SESSION_CONFLICT. Unknown/sealed sessions cannot admit new URLs.

A durable session exclusively owns a new crawl ID. OpenSession rejects a crawl
ID already used by legacy records or another session; migrating existing crawls
requires a separately reviewed transaction, not silent adoption. For that crawl
ID, legacy putURLs must reject creation of unreserved URLs. Existing reserved URL
status/lease updates remain compatible, while parser discovery uses the new RPC.
Other legacy crawl IDs retain their original behavior. Otherwise a second writer
could bypass quota through the old API despite a correct new admission handler.

The server must independently validate HTTP(S), Tor-v3 checksum/version, strict
allowlist, canonical identity, depth/content-extension limits and source scope
using the pinned policy revision. A client-supplied digest/`in_scope=true` is not
proof. For a discovered child, the source must already have a reservation in that
session and child depth must equal its persisted parent depth plus one. Seeds
consume quota, use depth zero and require explicit seed authority. No response
permits fetching before robots or bypassing Tor transport; admission only makes
a URL eligible for the existing scheduler/fetcher policy.

## Atomic storage contract

Use versioned admission keys in the **same server-owned RocksDB instance** as
existing URL/scheduling data. Key encoding must be length-delimited, not ambiguous
string concatenation. Session quota counter, unique(session,canonicalURL)
reservation, stable reservation ID, provenance and normal frontier enqueue are
written in one server-owned transaction/WriteBatch under a session lock or
transaction conflict detection. A WriteBatch alone does not serialize check/add.

The normal frontier enqueue implementation must participate in that same
transaction and publish any in-memory scheduling index only after commit. It
must rebuild that index from persisted state after restart. If the existing
server cannot compose enqueue with the batch, stop implementation and revise
the server design; do not emulate atomicity with two RPCs, an outbox process or
a second queue. Never open live RocksDB files from the crawler.

The persistent counter equals the number of unique committed reservations,
including seeds. It never exceeds the immutable quota. Fetch failure, robots
denial or successful processing does not release a unique admission slot.

Use sync/WAL durability appropriate to the declared crash guarantee; ACK only
after the durable transaction completes. Crash before commit leaves neither
quota nor queue row. Crash after commit/before ACK leaves both. A retry uses the
same session/canonical URL and returns ALREADY_RESERVED with the **same** stable
reservation ID and admission ordinal, without another enqueue/count. The enum
may distinguish replay; the logical reservation outcome is stable. Do not claim
exactly-once fetch: normal dequeue/lease/ACK remains at-least-once.

Provenance retains the immutable first source/depth/server observation time;
additional source edges may be appended idempotently under explicit bounded
per-target/per-session limits. Never overwrite first-discovery evidence with a
retry timestamp. Reject arbitrary metadata/header/proxy overrides. User content
is not an authorization source. Explicit retention/archival policy is required
before deleting sealed sessions; no automatic deletion of existing state.

## Conformance gates before activation

1. Concurrent distinct/duplicate requests at quota boundary: exactly N committed
   unique reservations/enqueues, no counter overshoot, seed consumes one slot.
2. Duplicate worker/replayed ACK-loss request: stable ID/ordinal, one queue row;
   provenance replay adds no duplicate edge.
3. SIGKILL before commit versus after fsync/before ACK: restart preserves either
   neither row or both rows; queue index rebuild makes committed URL dequeueable.
4. Crawler interruption/lease expiry: pending URL becomes available again under
   existing lease semantics and is eventually terminal or explicitly failed.
5. Scope/invalid-v3/URI/metadata rejection: no quota/queue mutation; policy digest
   mismatch, session reopen conflict and unauthorized callers fail closed.
6. Server/container recreation with the same volume: counters, reservations,
   provenance and queue contents agree. Fault injection proves no stranded
   committed reservation that cannot be dequeued.
7. Existing M1/M2 API paths and snapshots remain compatible; existing legacy
   records survive the additive schema/migration unchanged.
8. Only then enable a separately configured crawler adapter and real-onion M3
   A→B→C + duplicate/cycle + restart + ACK-loss acceptance. Preserve the existing
   local HTTP variant and its m3_verified=false limitation.

## Delivery / rollback

Implement and test the server/API changes in their owning source/build first,
then pin a verified image by digest and client contract version in an isolated
candidate. Capability detection must fail closed on old/unsupported servers;
count-before-put is never a fallback. Keep the additive feature disabled by
configuration until conformance, controlled Tor E2E and M2 prerequisites pass.
Rollback disables the new RPC/client mode, preserves versioned records/volumes,
and retains the old API; it must not silently convert durable sessions to the
in-memory local-test planner. No implementation or M3 PASS is implied by this ADR.
