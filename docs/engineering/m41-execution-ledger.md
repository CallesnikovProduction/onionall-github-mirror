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

## Checkpoint 3 — CLI, archive reconciliation and graph integration

Implemented AdmissionClient, bounded foreground CrawlExecution and child-owned
archive-wide CrawlWorker ownership/deadline. The initiating CLI can be killed
without releasing the running child's lock. H2 is closed before worker launch.
Unknown catalog outcome reconciles by authoritative readback of the same session,
not reseeding or allocating a new budget. Graph schemaV2 adds first-observation
time and dynamic same-job target-page resolution; rejected foreign links retain
identity without admission. Archive is still not a scheduling queue.

Failed95e1e6db73f7 exposed classpath-only filter loading: generated absolute files
were not worker resources. CrawlResources now publishes a job-specific full JAR
with classpath resources before admission; original installed artifact unchanged.
The nextfc01e857551a run fetched/archived A/B/C, retried503 and preserved quota
through recreation, but correctly failed Robots refusal not observable: runtime
metadata.persist omitted error.cause/source. Corrected those keys; no gate relaxed.

Final app build: .runtime/m41-build-final.log,118 JVM tests PASS, no skips/errors.
Packaged CLI17 (including real RPC dependency/failclosed smoke), Recovery Python113
and archivefixture2 pass in .runtime/m41-python-*-final.log.

Independent review fixed .onion RPC endpoint direct-DNS possibility and native
storage close vs active admission race. Server stopping is visible before drain;
late commands reject, active commit finishes before native handles close. Real
latched shutdown/reopen regression passes alongside concurrency/crash/migration/
auth in frontier-server/target/build.P98g1K/.

Actual Recovery regression running separately: .runtime/m41-recovery-regression.log.
Final M4.1 E2E running: .runtime/m41-e2e-final.log. Do not infer either PASS yet.
Original /tmp driver logs disappeared across the user's environment refresh;
continuation evidence is now saved under persistent ignored .runtime/. All earlier
per-project runtime evidence remains retained. User .gitignore edit is unrelated.

## Checkpoint 4 — actual vertical and Recovery verified

Final controlled project f175c5834c79 PASS. Source of truth:
.runtime/m2/onionall-brawler-m2-f175c5834c79/admission-local.json and
.runtime/m41-e2e-final.log. Actual CLI entrypoint starts Storm/TorProtocol against
real candidate Frontier: approval rejection,4 reservations,3 snapshots,8 directed
edges, foreign/depth rejection, robots terminal reason,503 scheduled retry, worker
and server restart, stable seed identity and repeated start without quota change.
Containers/networks removed, fresh Frontier volume retained, host snapshots equal.

Actual Recovery7d7260899b6d PASS: pending-before-outage, persisted error, stored-date
retry and terminal characterization, cleanup/retention/host equality. Evidence:
.runtime/m2/onionall-brawler-m2-7d7260899b6d/deterministic-recovery.json and
.runtime/m41-recovery-regression.log. M2/M3 qualification flags remain false.

The final packaging-specific pass mounts target/onionall read-only into the
controlled crawler and uses its actual bin/onionall for start/status. It is in
progress in .runtime/m41-e2e-packaged.log; this extra pass checks the thin runtime
closure rather than using the broader fat JAR classpath.

## Checkpoint 5 — packaged CLI acceptance complete

Project a7c09e2448f4 PASS via the actual read-only packaged /cli/bin/onionall
inside the crawler namespace, including start/status. Evidence:
.runtime/m2/onionall-brawler-m2-a7c09e2448f4/admission-local.json and
.runtime/m41-e2e-packaged.log. packaged_cli_verified=true, durable_admission_verified
=true;4 reservations,3 actual archived pages,8 graph edges.503 stored-date retry,
worker/server restart, stable reservation identity, repeated start, archive reopen
and CLI search/export all pass. Containers/networks removed, volume retained,
host snapshots equal. No external Tor was started; m2_verified/m3_verified=false.

Final sources passed118 JVM tests (.runtime/m41-build-final.log), server realDB
concurrency/crash/migration/RPC/shutdown conformance (build.P98g1K), packaged CLI17,
Python Recovery113, archivefixture2 and actual Recovery7d7260899b6d. Independent
review found no quota/replay bypass; its endpoint/shutdown findings were fixed.
No existing user RocksDB volume was migrated; no evidence or volume was deleted.
The user's .gitignore assets/ edit is intentionally excluded from local commits.
No push is authorized or performed.
