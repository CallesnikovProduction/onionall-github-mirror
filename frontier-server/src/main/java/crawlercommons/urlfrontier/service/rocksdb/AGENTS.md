# RocksDB admission and recovery

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Authoritative durable crawl state and reconstruction of scheduler indexes.

## RESPONSIBILITIES

Publish reservations/quota/queue state atomically, guard legacy updates by admitted membership, and rebuild queues/counters from authoritative rows.

## IMPLEMENTED ARCHITECTURE

AdmissionLedger serializes admission operations under the admission lock. A sync-WAL WriteBatch publishes reservation and scheduling state together; counters update after commit. RocksDBService opens default/queues/queueInfos/onionallAdmissionV1 and forces recoveryQscan(true).

## KEY COMPONENTS

`RocksDBService`, `AdmissionLedger`, `AdmissionState`, `QueueMetadata` and `ShardedRocksDBService` (upstream alternative, outside candidate admission entrypoint).

## DATA FLOW

Immutable session -> authoritative parent/depth validation -> duplicate replay or new reservation/quota/enqueue batch -> lease/status updates -> reopen scan and counter reconstruction.

## DEPENDENCIES

RocksDB JNI, common Frontier queue abstractions and AdmissionRpc authorization. H2 is not consulted for scheduler decisions.

## INVARIANTS

Replay must not enqueue again or spend quota. Preserve canonical origin, immutable seeds/budget, admission-only membership, sync persistence, per-queue timing and at-least-once leases. No scheduler mutations from catalog observations.

## CHANGE GUIDELINES

Test crash windows, duplicate reserve, denied mutations, range deletion boundaries and recovery counters on disposable stores. Never bypass migration guards, strip the admission column family or replace data to make a test run.

## RELATED MODULES

[Compatibility and rollback](../../../../../../../README.md); [Durability drivers](../../../../../../test/AGENTS.md); [Application projections](../../../../../../../../src/main/java/dev/onionall/brawler/admission/AGENTS.md).

## KNOWN LIMITATIONS

UNSCHEDULED rows do not retain a proved terminal/success reason. Cached counters/indexes need recovery reconstruction. Sharded forwarding is not an authenticated Admission implementation.
