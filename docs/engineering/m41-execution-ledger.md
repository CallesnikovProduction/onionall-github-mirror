# M4.1 durable admission execution ledger

Baseline: migration/java25-storm31 at 5f7f9da; clean worktree.
06c6366 Recovery and 5be5763 Archive MVP are present. No push authorized.

## Checkpoint 1 — contract

Implement admission in the existing Frontier owning RocksDB boundary: immutable
approved session, canonical same-origin URLs, parent-derived depth, serialized
quota check and one sync WAL WriteBatch containing reservation+counter+enqueue.
Publish scheduling index changes only after commit. H2 records observations,
never pending jobs or authoritative budget. RPC replay uses plan/session+URL.
Fresh isolated databases only; never migrate user volumes. Added admission CF
requires candidate-compatible rollback; stock2.4 cannot reopen it.

Implementation and conformance in progress; no admission/CLI/E2E PASS claimed.
Existing Recovery and real-Tor M2/M3 qualification are independent.

## Checkpoint 2 — authoritative server primitive verified

Versioned candidate source/build is under frontier-server/. Actual owning RocksDB
conformance passed:64 concurrent requests/quota10, duplicate/reopen, prewrite
failure without index mutation, source/scope/extension/seal guards, subprocess
halt before commit and after sync-WAL-before-ACK. Explicit isolated-copy schema
upgrade preserves legacy data; stock rollback refuses4CF, compatible readonly
rollback retains protection. Real loopback gRPC capability/admin/read tests pass.
Evidence: frontier-server/target/build.EyF8AC/{conformance,migration,rpc}.log.
Application offline build115 tests PASS in /tmp/m41-build-3.log. Python Recovery113,
packagedCLI16 and archivefixture2 PASS. Full M4.1 E2E NOT yet verified.

First runner attempt b75c0f53d0de failed before infrastructure (Path.open opener
argument); corrected to builtin open with mode600. Second d9c3c39d3fdb committed
seed admission/readback but Storm launch failed: daemon log path remained the
read-only /apache-storm/logs. Need daemon -c options, not topology-only config.
Failed evidence retained; cleanup/volume/host checks pass. No live Tor used.
