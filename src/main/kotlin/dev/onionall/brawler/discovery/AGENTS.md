# Local discovery adapters

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Pure bounded discovery planning and explicitly local-test Storm adapters.

## RESPONSIBILITIES

Normalize candidates, record provenance and budget decisions, and filter existing parser outlinks without initiating network requests.

## IMPLEMENTED ARCHITECTURE

DiscoveryPlanner uses instance-local deduplication/budgets. DiscoveryParseFilter requires discovery.budget.mode=local-test and a session ID; DiscoveryScopeFilter validates policy and discovery metadata.

## KEY COMPONENTS

`DiscoveryPlanner`, `DiscoveryParseFilter`, `DiscoveryScopeFilter`.

## DATA FLOW

Source page + extracted links -> TorPolicy normalization -> bounded depth/page/link decisions -> provenance metadata and allowed outlinks. Rejection summaries are bounded and do not expose raw destinations.

## DEPENDENCIES

Kotlin, JSoup/StormCrawler adapters and TorPolicy; Java offline CLI can call the planner.

## INVARIANTS

In-memory counts are not durable quota or reservation authority. Do not wire these local-test adapters in place of DurableDiscoveryFilter in product jobs. Preserve normalization, same configured scope and metadata-only target handling.

## CHANGE GUIDELINES

Verify deduplication, source-budget accounting, depth and link bounds, and Java call compatibility. Changes requiring durable admission belong in the existing Frontier-backed path.

## RELATED MODULES

[Scope](../tor/AGENTS.md); [Durable discovery](../../../../../java/dev/onionall/brawler/admission/AGENTS.md); [Local fixture](../../../../../../../test/m3/AGENTS.md).

## KNOWN LIMITATIONS

Instance budgets do not survive restart or coordinate parallel workers. Planning is neither fetch success nor durable admission.
