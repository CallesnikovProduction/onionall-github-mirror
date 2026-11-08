# URLFrontier admission v1 candidate

Owning source: crawler-commons/url-frontier tag2.4, commit
27363207fdb68abad7606cf249d93be55509d71a. The14 service Java files and Apache2
LICENSE are vendored here; changes are limited to RocksDBService, QueueMetadata, AbstractFrontierService,
URLFrontierServer, plus AdmissionLedger and AdmissionRpc. UPSTREAM-SHA256 records
original source hashes. Runtime dependency is the exact stock2.4 service fat JAR:
SHA256 ff1b774bf81343234e6f34878f8b7c9a826371b3e305e96bf0e204895e3562df.
No dependency download or protobuf code generation occurs.

## Maven and IntelliJ IDEA

The root POM does not declare modules. The canonical launcher orchestrates
`crawler/pom.xml` and `frontier-server/pom.xml` as independent builds; the server
is independent of the crawler dependency graph. Its standard `src/main/java` and
`src/test/java` roots are imported by IDEA; select JDK25 for Maven and the IDE.
The build and tests use JDK25. The server's source/API/bytecode compatibility
remains release11, matching the pre-existing `build.sh`; IDEA therefore shows
language level11 for this module. This does not select a Java11 runtime or
change the crawler's Java25 target.

One explicit preparation step is required before the first Maven import/build.
From the checkout root, with JDK25 on PATH:

```sh
java frontier-server/build-support/FrontierBuild.java prepare /absolute/path/to/pinned-service.jar frontier-server
mvn -B -U -f frontier-server/pom.xml verify
mvn -B -f crawler/pom.xml verify
```

Use a quoted Windows path in PowerShell. Preparation checks the exact SHA256
above before writing anything. It copies that input and creates an ignored local
file repository at `.runtime/frontier-maven-repository`. The synthetic Maven
artifact `dev.onionall.pinned:urlfrontier-runtime-dependencies:2.4-ff1b774b-deps1`
contains the original dependency/API/resource entries, byte for byte, excluding
only `crawlercommons/urlfrontier/service/**/*.class`, which the vendored sources
replace. Every excluded class must have a vendored owning Java source.
No dependency version is substituted and no system-scoped dependency is used.
The dependency is optional for downstream consumers because the packaged server
already embeds it; it remains available for this module's IDE/compile/test runs.
The first Maven resolution reads this file repository (`-U` also clears a prior
failed-import cache); later builds can use `-o`
with a complete Maven cache. Keep the prepared repository for validation and
packaging even after Maven caches its dependency.

`validate` verifies the pinned input, every retained dependency entry and the
actual resolved Maven JAR. `test` compiles the conformance sources and runs all
seven conformance main classes in separate JVMs, including ambiguous RPC,
recovery counters and authenticated state readback; failures and
180s subprocess timeouts fail Maven. These are executable conformance suites,
not JUnit tests. Each run retains fresh DBs/logs under
`target/maven-conformance-*`. The migration test alone starts a stock-baseline
subprocess to verify stock creation and rollback refusal.

`package` overlays the compiled service classes onto a fresh copy of the pinned
fat JAR using the same JDK `jar --update` and fixed timestamp as `build.sh`.
Output remains `frontier-server/target/urlfrontier-admission.jar`; launch this
single JAR, never add the original fat JAR alongside it. The thin intermediate
JAR is replaced before Maven install/deploy. `build.sh` remains an independent
build path and does not require the prepared Maven repository.

After preparation, import/reload `crawler/pom.xml` and `frontier-server/pom.xml`
as separate Maven projects in IDEA. Missing input or
checksum mismatch is a build failure; there is no fallback to another service
artifact. Compilation/import contracts can be checked with Maven; actual IDEA
indexing must also be observed in the IDE.

Build with JDK25 (server source is compiled with release11):

```sh
JAVA_HOME=<JDK25> URLFRONTIER_BASE_JAR=<pinned-service.jar> bash frontier-server/build.sh --test
```

The test option needs local loopback sockets for real gRPC auth conformance.
Every build uses a unique ignored target directory, retaining logs and fresh test
DBs. Output: frontier-server/target/urlfrontier-admission.jar. Launch main class
crawlercommons.urlfrontier.service.URLFrontierServer with stock configuration
arguments, e.g. rocksdb.path=/new-isolated-volume/db. Set
ONIONALL_ADMISSION_TOKEN to32..256 characters; never include it in arguments or
logs. Deploy only inside the existing isolated crawler network, without host
ports, egress, privileged flags, or host configuration changes.

## Ownership and durability

A global server-owned admission lock serializes admission and normal URL writes
for the local-first workload (maximum100 unique URLs/session). Reservation,
session counter, existence pointer and ordinary scheduling row are one sync-WAL
RocksDB WriteBatch. No second DB/outbox/queue exists. Index counters are published
only after commit; on reopen indexes rebuild from authoritative rows. ACK after
commit can be lost; replay returns the same reservation ID/ordinal and never
re-enqueues or consumes another slot. Leases/fetch remain at-least-once.

Sessions are immutable: session ID=crawl ID, same origin (scheme+onionhost),
approved seed set, max pages1..100, max depth0..5, policy version1. Seeds consume
quota. Parent reservation/depth is authoritative. Duplicate requests retain
original provenance even when rediscovered along another eligible path. Known
updates cannot create unreserved URLs in protected crawls; Discovered replays of
reserved URLs use stock deduplication. Administrative mutation RPCs are globally
disabled on this candidate, so queue deletion or crawl controls cannot bypass
admission. Legacy lease/status APIs remain supported with the existing capability;
read-only list/status APIs retain their existing policy. This is a trusted
single-local-deployment capability boundary; do not expose arbitrary clients or
claim multi-tenant per-worker authorization.

## RPC contract

Unary full method: onionall.frontier.admission.v1.Admission/Command.
Request and response protobuf type: google.protobuf.BytesValue. Value is UTF8
JSON, maximum32768 request bytes. No generators/new protobuf runtime are needed.
Every admission RPC and every legacy PutURLs/GetURLs RPC requires metadata header
x-onionall-admission-token matching ONIONALL_ADMISSION_TOKEN, for all crawl IDs.
GetURLs acquires leases and is not read-only. Missing/wrong tokens are rejected
before handler dispatch; there is no anonymous fallback. This capability authorizes
the trusted local operator and pipeline; fetched content never supplies authority.
Tokenless legacy workers are incompatible with this hardened candidate. Deploy
the matching CapabilitySpout/CapabilityStatusUpdaterBolt clients with the server;
they read the existing admission.token.file on each new RPC, including reconnect.
Stock synthetic M0/M1 and local-test archive fixtures remain separate deployments.
No protobuf or persisted schema changes are required; worker/operator capability
separation and plaintext transport remain outside this narrow authorization fix.

Requests:

- open: action, session_id, origin (canonical without slash), seeds[], max_pages,
  max_depth, approved=true. Identical reopen idempotent; mismatches conflict.
- reserve: action, session_id, url, source_url, depth. Empty source allowed only
  for approved seed at depth0. Otherwise source must be reserved; depth=parent+1.
- reservation: action, session_id, url.
- session: action, session_id.
- seal: action, session_id; duplicates still readable/replayable after seal.

Session response: outcome=SESSION_OPEN, spec containing immutable fields,
reserved_count, sealed, created_at. Reservation response is flat:
outcome=RESERVED or ALREADY_RESERVED, session_id,url,source_url,depth,
reservation_id,ordinal,discovered_at,enqueued=true. Reservation lookup returns
original RESERVED record. Refusals have outcome only: APPROVAL_REQUIRED,
SESSION_CONFLICT,CRAWL_CONFLICT,SESSION_NOT_FOUND,RESERVATION_NOT_FOUND,
SCOPE_REJECTED,DEPTH_REJECTED,SOURCE_REJECTED,SEED_REJECTED,LIMIT_REJECTED,
METADATA_ONLY,SESSION_SEALED,ADMISSION_READ_ONLY. Invalid request shape/URL is
gRPC INVALID_ARGUMENT. Storage failure is an RPC failure, never a success result.

URL identity independently validates v3 checksum/version, HTTP(S), default ports,
userinfo/fragment/dot-segment constraints and exact origin. Binary extension
links are not scheduled. Successful admission does not bypass robots or transport.
The first source/depth/server observation time is immutable. Additional source
edges belong to archive observations and must remain bounded/idempotent.

## Schema, migration and rollback

Fourth CF onionallAdmissionV1 uses version byte and length-delimited keys; schema
marker must equal1. Candidate accepts fresh empty DBs. Existing3-CF DBs are
refused before opening unless admission.migrate=copy-only is explicitly set.
That flag is ONLY for operator-prepared isolated copies with a backup; never use
it on existing user volumes. No automatic migration/cleanup exists.

Stock2.4 cannot open this4-CF schema. Rollback requires a compatible candidate
binary with admission.readonly=true: new open/reserve/seal are disabled, durable
records and protected-crawl guards remain, existing result updates/leases work.
Never return to process-local quota or strip CFs. Migration conformance creates
a disposable3-CF baseline, verifies refusal, explicitly upgrades that test DB,
checks legacy preservation, verifies stock rollback refusal and compatible
read-only reopening. Existing user data is untouched.

## Verification

AdmissionConformance exercises real RocksDB: concurrent quota, duplicate/reopen,
legacy creation denial, source/scope/content rejection, write-failure atomicity,
server reopen and actual subprocess halt before commit / after sync-WAL-before
ACK. AdmissionMigrationConformance exercises fresh schema migration/rollback.
AdmissionRpcConformance runs a real local gRPC server and validates absent token,
valid capability, rejected PutURLs/GetURLs without state or lease mutation,
separate-process restart/replay, authenticated retry/terminal updates, all six admin
denials and legacy read compatibility. These primitive
checks do not claim full CLI/Storm/archive integration or real-Tor M2/M3.

Shutdown conformance blocks a real admission transaction before commit and proves
close drains it before freeing native handles. Late admissions/status writes fail
closed; committed reservation/count/queue survive reopening.

## Recovery counter qualification (M5 REC-01)

`build.sh --test` also runs RecoveryCountersConformance: actual RocksDB with
three queues (mixed active/unscheduled, all unscheduled, all active), two clean
restarts, and a separate-process Runtime.halt without close. It verifies exact
counts, due dates, reservation identity/quota, due membership and idempotent replay.
"Completed" is the storage meaning UNSCHEDULED, not successful fetch or terminal
reason. Process crash is not an OS/power-loss durability qualification.
No volumes are deleted. Current candidate forces recoveryQscan(true); alleged
readQueueInfos/deleteRange and recoveryQscan(false) defects are dormant, so no
recovery production patch is justified by those findings. Native Windows can run
the same test via scripts/runners/verify.py with the pinned base JAR/JDK25.

## M5 authenticated scheduler observations (state_version1)

Owner approved UNKNOWN/null outcome semantics on2026-11-04. The existing restricted
Admission/Command accepts additive read-only actions list and counts with the same
existing capability. No new protobuf, persistent schema/index or authorization
service. Old clients remain compatible; old servers reject new actions explicitly
(client IOException), never return a successful empty scheduler fallback.

list requires session_id, optional limit1..200 (default100), optional cursor.
Result: outcome STATE_LIST, state_version1, session_id, readback_at UTC, rows,
through_ordinal and nullable next_cursor. Each row retains reservation_id/ordinal,
URL, depth/source and a scheduler observation (state, next_due_epoch,
lease_until_epoch; epochs in seconds, null where unavailable). Cursor is a bounded
base64url v1 session/watermark/after tuple; it is not a capability or security token.
Membership is fixed through the first page's ordinal; concurrent appends appear in
a new traversal. Row state is fresh on each page (not a cross-page MVCC snapshot).
The existing per-session100-reservation cap bounds the prefix scan/sort. Response
pages are additionally byte-bounded below the existing64KiB client envelope; limit
is a maximum, not a promised row count. No offset paging or new persistent index.

counts returns outcome STATE_COUNTS and reserved/pending/held/scheduled_wait/
unscheduled/unknown. done, success, terminal and retry_wait are explicitly null.
Future scheduled work is not proof of retry, and UNSCHEDULED is not proof of
success. next_attempt_at is the earliest candidate considering stored due times
and live lease expiry; it is not a guarantee of fetch execution (worker liveness,
queue pacing and network availability still apply).

Reads serialize with admission/status writes using admissionLock and take the
session's queue monitor for a consistent live-lease observation. They read existing
CF0/CF1/admission rows; no GetURLs/lease acquisition, expiration pruning, queue
counter change, reservation/quota write or repair. Missing/inconsistent authoritative
rows fail explicitly rather than synthesize zero/complete. Leases remain ephemeral:
a server restart releases them and due rows are PENDING again, not failed/done.

AdmissionClient.list/counts return strict FrontierReadback records; unknown outcomes,
versions, categories, fractional counts, malformed/partial pages and timeouts fail
explicitly. Unknown terminal provenance stays null. New conformance runs with five
reservations/two unscheduled rows, leases and a future row; it proves zero RocksDB
sequence changes and identical raw leases (including expired entries), auth denies,
read-only mode, concurrent append pagination, long URL byte paging, two clean and
one process-crash restart. Both Maven and --test run seven drivers, also covering
ambiguous RPC outcomes. No power-loss/soak claim.

Rollback: deploy the previous server/client binaries together; all existing DBs
remain readable. New typed reads against the old server report unsupported/unavailable.
No recovery/data migration is needed and no scheduling state is rewritten.
