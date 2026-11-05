# Local discovery runtime

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Synthetic bounded discovery graph for the explicitly local-test Kotlin adapters.

## RESPONSIBILITIES

Check fixture preparation, provenance, deduplication, scope and local budgets without claiming durable quota.

## IMPLEMENTED ARCHITECTURE

prepare.py selects local-test discovery mode and generated fixture configuration; local_integration.py runs the controlled HTTP graph; fixture.py provides deterministic link cases.

## KEY COMPONENTS

`prepare.py`, `local_integration.py`, `fixture.py`, `test_prepare.py`, `test_fixture.py`.

## DATA FLOW

Synthetic seeds/HTML -> local-test DiscoveryPlanner/filter -> bounded emitted graph -> provenance assertions.

## DEPENDENCIES

Kotlin discovery adapters, baseline fixture graph and Docker runtime when integration is selected.

## INVARIANTS

No external onion discovery. Instance-local budget is not restart-safe or distributed admission. Do not replace product DurableDiscoveryFilter with this fixture mode.

## CHANGE GUIDELINES

Verify preparation and fixture tests, then selected runtime for graph changes. Preserve source/depth/dedup bounds and explicit mode/session configuration.

## RELATED MODULES

[Adapters](../../src/main/kotlin/dev/onionall/brawler/discovery/AGENTS.md); [Durable product fixture](../admission/AGENTS.md).

## KNOWN LIMITATIONS

HTTP fixture provenance does not qualify Tor or durable Frontier reservations/quota.
