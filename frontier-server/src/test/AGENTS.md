# Frontier conformance drivers

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Plain-Java process/RPC/storage checks for the owning Frontier candidate.

## RESPONSIBILITIES

Exercise actual RocksDB durability, capability rejection, immutable admission, queue/counter recovery and compatibility boundaries.

## IMPLEMENTED ARCHITECTURE

build.sh --test compiles/runs main-method conformance drivers against the assembled server; these are separate from Maven JUnit. LegacyFixture creates isolated legacy data for explicit migration tests.

## KEY COMPONENTS

`AdmissionConformance`, `AdmissionStateConformance`, `AdmissionRpcConformance`, `AdmissionShutdownConformance`, `AdmissionMigrationConformance`, `RecoveryCountersConformance`, `LegacyFixture`.

## DATA FLOW

Fresh disposable database -> real command/status/lease operations -> injected failure or subprocess halt -> reopen -> persisted assertions and owned cleanup.

## DEPENDENCIES

Exact candidate/base JAR, Java tools, loopback gRPC and local RocksDB native support.

## INVARIANTS

Never use a user database or production volume. RPC OK alone does not prove state change; denial must preserve metadata, leases and quota after reopen. Preserve copy-only migration and incompatible-stock rollback checks.

## CHANGE GUIDELINES

Run through build.sh --test with the documented baseline. Keep deterministic bounds and failure assertions; record actual command/environment in ledgers rather than this file.

## RELATED MODULES

[Build ownership](../../AGENTS.md); [Generated product fixture](../../../test/admission/AGENTS.md).

## KNOWN LIMITATIONS

Local durability does not establish live Tor, distributed cluster recovery or end-to-end Storm behavior.
