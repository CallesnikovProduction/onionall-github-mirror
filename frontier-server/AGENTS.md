# Owning URLFrontier candidate

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Vendored URLFrontier server with authenticated durable Admission and RocksDB scheduling.

## RESPONSIBILITIES

Own admission, reservations, quota, queue/lease/retry state and recovery; preserve upstream provenance and the paired application contract.

## IMPLEMENTED ARCHITECTURE

Separate build.sh compiles selected sources using JDK 25 --release 11 into a checksum-verified URLFrontier 2.4 base JAR. The candidate entrypoint accepts the guarded RocksDBService path, not arbitrary alternative backends.

## KEY COMPONENTS

`build.sh`, `UPSTREAM-SHA256`, `LICENSE`, `src/main/java/crawlercommons/urlfrontier/service` and `src/test`.

## DATA FLOW

Authenticated Admission -> immutable durable session/reservation -> atomic queue publication -> authenticated legacy lease/status RPC -> recovery readback.

## DEPENDENCIES

Exact baseline JAR/hash documented in README, gRPC/protobuf APIs, RocksDB JNI and upstream service infrastructure. This is not part of the root Maven compilation.

## INVARIANTS

Preserve existing protobuf and the admission column-family contract. No stock-server rollback over an admission-enabled database. Existing legacy data migration requires explicit isolated copy-only opt-in and preserved backup.

## CHANGE GUIDELINES

Keep source/license/base-hash provenance. Run conformance against real disposable RocksDB and process restarts. Review schema/rollback and paired client compatibility before changing startup or storage.

## RELATED MODULES

[Build and storage contract](README.md); [RPC boundary](src/main/java/crawlercommons/urlfrontier/service/AGENTS.md); [Conformance](src/test/AGENTS.md).

## KNOWN LIMITATIONS

The server is plaintext RPC, not a TLS endpoint or multi-tenant authorization system. Alternative vendored backends do not implement the candidate Admission contract.
