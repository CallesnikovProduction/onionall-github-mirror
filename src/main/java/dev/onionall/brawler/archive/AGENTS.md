# Archive catalog and HTML objects

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Local archive persistence, catalog reads and observed crawl outcomes.

## RESPONSIBILITIES

Store plans/approval, snapshots, links and bounded failure observations; expose local search and import/export. Do not decide admission, quota, leases or retry scheduling.

## IMPLEMENTED ARCHITECTURE

ArchiveStore uses embedded single-process H2 and SHA256-addressed immutable HTML objects. Blob publication precedes snapshot transaction. ArchiveParseFilter commits before parser document emission; archive status observation precedes Frontier status write.

## KEY COMPONENTS

`ArchiveStore`, `ArchiveParseFilter`, `ArchiveStatusUpdaterBolt`, `CapabilityArchiveStatusUpdaterBolt`, `SearchProvider` and failure-observation types.

## DATA FLOW

Fetched HTML -> bounded immutable object -> catalog transaction -> parser document. Status outcome -> catalog observation -> authenticated Frontier updater. Assisted search-provider input imports user-provided HTML; local search queries catalog text.

## DEPENDENCIES

H2, JSoup, StormCrawler parser/status interfaces; admission client/orchestration; Kotlin Tor transport validation.

## INVARIANTS

H2 is a catalog, not authoritative scheduling. Preserve no-overwrite object integrity, bounded content, schema/version guards and single-process access. Failure intent/next due is an observation, not proof Frontier accepted it or a count of distinct HTTP attempts.

## CHANGE GUIDELINES

Test object/transaction failure ordering and catalog reopen. Do not silently enable AUTO_SERVER or read-only second-process fallback. Inspect installed parser exception/ACK behavior before changing fault handling; schema changes require explicit compatibility/rollback review.

## RELATED MODULES

[Orchestration](../admission/AGENTS.md); [Archive contract](../../../../../../../docs/archive-mvp.md); [Archive fixture](../../../../../../../test/archive/AGENTS.md).

## KNOWN LIMITATIONS

Failed catalog writes can leave an unreferenced blob, never a committed snapshot without its object. Installed JSoupParserBolt converts an ArchiveParseFilter exception to ERROR and ACK; throwing alone does not fail the parent tuple. Local search uses catalog matching, not full-text ranking.
