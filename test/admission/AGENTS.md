# Generated product runtime fixture

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Internal-only end-to-end exercise of the actual CLI-generated authenticated crawl graph.

## RESPONSIBILITIES

Check plan/approval/admission, lease/status/retry, archive readback, restart/replay and worker ownership against the owning Frontier.

## IMPLEMENTED ARCHITECTURE

local_integration.py assembles an isolated Compose runtime and internal synthetic onion SOCKS origin, invokes real CLI start and checks durable/catalog results. AdmissionProbe and WorkerOwnershipProbe expose bounded test observations/actions.

## KEY COMPONENTS

`local_integration.py`, `AdmissionProbe.java`, `WorkerOwnershipProbe.java`.
`m61_forum_fixture.py` and `m61_native_smoke.py` exercise two controlled discussion DOM
layouts, ordinary depth3 navigation, separate thread plans, extraction/provenance and
restart readback through actual native Frontier/Storm. In-flight instrumentation reports
observed concurrency; independent socket overlap controls are not crawler parallelism proof.

## DATA FLOW

Unique fixture plan -> CLI approval/start -> generated product worker -> internal SOCKS -> owning RocksDB/catalog -> restart/readback and cleanup.

## DEPENDENCIES

Paired app/candidate Frontier JARs, Linux Docker, patched Storm image, exact UID/volume initialization and synthetic fixture helpers.

## INVARIANTS

SOCKS never forwards real destinations. Keep fixture token private and out of uploaded logs. Mutation/process probes must stay test-only; force-kill requires exact owned identity/birth/role checks. Unknown ownership blocks restart without altering admission/resources/quota.

## CHANGE GUIDELINES

Inspect actual generated graph/classes and persisted state. Preserve negative auth and replay assertions. Do not erase ownership markers, weaken liveness checks or add native reapers to make crash cases succeed.

## RELATED MODULES

[Product source](../../src/main/java/dev/onionall/brawler/admission/AGENTS.md); [Authority](../../frontier-server/AGENTS.md); [SOCKS helpers](../archive/AGENTS.md).

## KNOWN LIMITATIONS

This is a synthetic transport fixture, not real Tor. Java process liveness can include unreaped children; fail-closed ownership is not an OS containment guarantee.
