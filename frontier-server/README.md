# URLFrontier admission v1 candidate

Owning source: crawler-commons/url-frontier tag2.4, commit
27363207fdb68abad7606cf249d93be55509d71a. The14 service Java files and Apache2
LICENSE are vendored here; changes are limited to RocksDBService, QueueMetadata,
URLFrontierServer, plus AdmissionLedger and AdmissionRpc. UPSTREAM-SHA256 records
original source hashes. Runtime dependency is the exact stock2.4 service fat JAR:
SHA256 ff1b774bf81343234e6f34878f8b7c9a826371b3e305e96bf0e204895e3562df.
No dependency download or protobuf code generation occurs.

Build (JDK11+; project validation uses JDK25):

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
admission. Legacy read/lease/status APIs remain supported. This is a trusted
single-local-deployment capability boundary; do not expose arbitrary clients or
claim multi-tenant per-worker authorization.

## RPC contract

Unary full method: onionall.frontier.admission.v1.Admission/Command.
Request and response protobuf type: google.protobuf.BytesValue. Value is UTF8
JSON, maximum32768 request bytes. No generators/new protobuf runtime are needed.
Every admission RPC requires metadata header x-onionall-admission-token matching
ONIONALL_ADMISSION_TOKEN. This capability authorizes the trusted local operator
and pipeline; fetched content never supplies session authority or approval.

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
valid capability, admin denial and legacy read compatibility. These primitive
checks do not claim full CLI/Storm/archive integration or real-Tor M2/M3.
