# M4.1 ownership and reconciliation contract

The versioned admission endpoint executes inside the existing Frontier server
and RocksDB owner. Immutable approved sessions bind plan ID, canonical origin,
seeds, depth and unique-page quota. Seeds consume quota. Admission serializes
check+WriteBatch(sync WAL) for reservation, count and normal Frontier enqueue.
No H2 counter or second queue decides eligibility. Lease processing is at-least-once.

The operator capability authorizes session opening; untrusted HTML cannot grant
approval. Protected crawl IDs reject unreserved legacy submissions. PutURLs and
GetURLs require that same capability for every crawl ID before any write/lease;
legitimate durable worker channels must authenticate on initial calls and reconnect.
Administrative mutation remains disabled even with a capability. New admission
RPC uses a bounded protobuf BytesValue envelope carrying versioned JSON to avoid
unpinned generators against bundled protobuf. Internal deployment only; never
expose plaintext capability traffic to an untrusted/public network.

CLI start verifies the approved plan, opens its stable session, reserves seeds
and reads their committed IDs back before reporting admission or launching Storm.
A missing catalog observation is reconciled by reading the same session and seed
identities. A hard crash retaining PREPARING/LAUNCHING without cleanup proof still
blocks local start in WorkerOwnership; server replay safety does not establish
absence of a live worker. No new budget/session is created. H2 observations explicitly
identify Frontier as authority; archive writes precede successful pipeline ACK.
No transaction spans RocksDB and H2. A catalog failure can replay processing; it
cannot undo committed admission or authorize an extra reservation.

## RPC failures and credentials

Command transport failures and malformed responses do not prove rejection of a
mutation. AdmissionClient reports TRANSPORT/PROTOCOL and whether a mutation may
have committed; its ordinary exception chain omits untrusted response text.
The original gRPC failure is available explicitly for controlled diagnostics.
Automatic channel retries are disabled. After a transport failure open/reserve,
the admission coordinator performs read-only session/reservation reconciliation.
Only a positive, fully verified readback permits progress. Missing state or failed
readback yields UNKNOWN and prevents worker launch. A negative observation can
precede the original RPC's late commit; it never triggers an automatic mutation.
Partial reservations persist. A later explicit identical admission remains safe
under Frontier's immutable-spec and reservation replay contract, subject to local
ownership proof. Seal is also idempotent; after its lost response, callers must
verify `sealed=true` by session readback rather than infer success from an RPC code.

Production clients reload the capability file for each new RPC, including
discovery and start/status. The explicit String client API is a token snapshot.
The server captures one global operator/worker token at startup; it has no role,
session, overlap-rotation or active-stream revocation protocol. Coordinate token
changes with stopped workers/streams and a server/client restart. Files/parents
must be operator-controlled with private permissions/ACL; plaintext traffic must
stay on loopback or an isolated network with no published Frontier ports. The
product fixture checks Docker internal networking; an arbitrary runtime hostname
does not establish this property. No incompatible TLS migration is implied.

## Readback v1

Immutable sessions allow at most 100 unique reservations; list pages independently
allow 1..200 rows and may be shorter to stay under the 64 KiB JSON envelope.
Larger sessions are unsupported, not silently truncated. Local seed ordering and
duplicates have set semantics, matching server normalization; stored authoritative
specs have unique seeds and strict bounded integer limits/policy_version=1.
The cursor freezes the ordinal membership boundary only. Scheduler states and
server `readback_at` timestamps are independently observed per page; timestamps
are wall-clock samples, not monotonic revisions or freshness leases. Consumers
must tolerate clock skew/regression. Unsupported readback versions fail closed.

The existing store lacks terminal/success/retry provenance. Four terminal counters
remain explicit null, and zero active scheduler counts cannot prove completion.
Supporting them requires durable outcome updates/recovery, semantics for old
records, and a versioned client/server contract; RPC parsing alone cannot supply it.

Graph schema2 stores first edge observation time and source/snapshot/job identity.
Resolved target page IDs are same-job dynamic joins; foreign links remain data
and never authorize fetch. V1 archives migrate additively; old binaries reject
V2. Historical missing links cannot be reconstructed; backfilled times are marked
SNAPSHOT_RECORDED. Keep a user-owned backup before deployment; no automatic cleanup.

Admission CF schema1 is additive but stock Frontier2.4 cannot reopen a fourth CF.
Use fresh isolated volumes for acceptance; never point tests at existing user DBs.
Existing data migration requires an offline copy and explicit candidate opt-in;
rollback must retain an admission-aware binary and records, not stock2.4.

Verification is incomplete until server conformance, real CLI-controlled fixture
pipeline and regressions pass. M2/M3 real-Tor flags are independent and unchanged.
