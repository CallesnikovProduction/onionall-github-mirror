# M4.1 ownership and reconciliation contract

The versioned admission endpoint executes inside the existing Frontier server
and RocksDB owner. Immutable approved sessions bind plan ID, canonical origin,
seeds, depth and unique-page quota. Seeds consume quota. Admission serializes
check+WriteBatch(sync WAL) for reservation, count and normal Frontier enqueue.
No H2 counter or second queue decides eligibility. Lease processing is at-least-once.

The operator capability authorizes session opening; untrusted HTML cannot grant
approval. Protected crawl IDs reject unreserved legacy submissions. New admission
RPC uses a bounded protobuf BytesValue envelope carrying versioned JSON to avoid
unpinned generators against bundled protobuf. Internal deployment only; never
expose plaintext capability traffic to an untrusted/public network.

CLI start verifies the approved plan, opens its stable session, reserves seeds
and reads their committed IDs back before reporting admission or launching Storm.
A crash before catalog observation is reconciled by replaying the same session
and seed identities. No new budget/session is created. H2 observations explicitly
identify Frontier as authority; archive writes precede successful pipeline ACK.
No transaction spans RocksDB and H2. A catalog failure can replay processing; it
cannot undo committed admission or authorize an extra reservation.

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
