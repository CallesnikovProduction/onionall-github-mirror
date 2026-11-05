# Frontier RPC and backend boundary

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Server dispatch, capability enforcement and common URLFrontier queue abstractions.

## RESPONSIBILITIES

Keep authorization in the server interceptor before mutation dispatch; preserve read-only allowlisting and denied administrative operations.

## IMPLEMENTED ARCHITECTURE

URLFrontierServer wraps the selected RocksDB service and Admission command with AdmissionRpc.guard. Admission/Command, PutURLs and GetURLs require x-onionall-admission-token. Explicit legacy reads are allowed; admin/unknown methods are denied. memory/ and cluster/ retain upstream alternative implementations, not candidate admission backends.

## KEY COMPONENTS

`URLFrontierServer`, `AdmissionRpc`, `AbstractFrontierService`, queue/stream abstractions; `memory/MemoryFrontierService`, `cluster/DistributedFrontierService` and `rocksdb/`.

## DATA FLOW

gRPC method -> allowlist/capability check -> bounded request handling -> owning service. Stop incoming calls before closing storage/executors.

## DEPENDENCIES

gRPC/protobuf and generated URLFrontier stubs; RocksDB candidate; application credential header must match.

## INVARIANTS

All crawl IDs are protected for lease/status mutations; admission membership guards remain additional checks. Valid capability does not authorize admin mutations. Preserve constant-time token comparison and bounded payloads. Do not expose memory/sharded dispatch as an equivalent protected product.

## CHANGE GUIDELINES

Enumerate every mutation endpoint and reconnect path when changing RPC policy. Test unauthenticated/wrong credentials with unchanged persisted scheduling/leases after restart and normal authorized operations.

## RELATED MODULES

[Durable backend](rocksdb/AGENTS.md); [Paired clients](../../../../../../../src/main/java/org/apache/stormcrawler/urlfrontier/AGENTS.md); [RPC conformance](../../../../../test/AGENTS.md).

## KNOWN LIMITATIONS

Shared token does not distinguish operator/worker. Read-only RPCs are not confidential. Auth is checked on stream creation; this is not per-message revocation. forPort binding/plaintext require deployment-level isolation.
