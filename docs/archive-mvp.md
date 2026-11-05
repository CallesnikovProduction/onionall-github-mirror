# Local research archive MVP

This document records the admission-disabled MVP baseline. M4.1 now provides
configured durable start; current deployment/CLI contracts are in
[m41-durable-admission.md](m41-durable-admission.md). Historical results below
remain attributed to their original local-test diagnostic.

This increment adds offline approved plans, a durable document catalog, HTML
snapshots, provenance, local search and a controlled crawl-to-archive diagnostic.
It does **not** enable automatic production Frontier admission or qualify M2/M3.
Existing fetch/inspect/discover transport and CLI behavior remain available.

## Ownership and admission decision

URLFrontier remains the only scheduling/lease authority. StormCrawler owns
robots, pacing, finite retry and status updates. TorProtocol remains the onion
transport. The catalog stores user-visible plans/results, never pending work.

ADR0002 remains BLOCKED: stock Frontier2.4 has no atomic global reservation,
quota+enqueue or replayable unknown-ACK contract. The pinned server feasibility
plan documents that reserve followed by putURLItem cannot join its WriteBatch;
new admission keys cannot be hidden safely in existing CF namespaces. A fourth
CF prevents stock2.4 rollback. Compatible RPC generation, server transaction,
authority guards and rollback conformance are prerequisites, not CLI workarounds.
No existing RocksDB volume is migrated by this feature.

`crawl start` fails with exit7 and the actual ADR0002 blocker, even for an approved
plan. No pending job is fabricated. `test/archive/local_integration.py` explicitly
uses the existing single-parser `local-test` discovery mode with repository-owned
fixtures. Its bounded in-process discovery is not durable global admission and
must not be used to claim restart-safe quota. Archive uniqueness is not an
admission reservation. This is the fallback authorized for an unavailable safe
atomic server contract.

## Catalog and snapshots

SQLite was evaluated as the preferred concurrent local catalog, but no SQLite
JDBC runtime is present in the existing dependency cache. This bounded increment
uses the cached pure-Java H2 2.4.240 JDBC engine explicitly, without another
service or JNI delivery. H2 embedded permits one owning process. No AUTO_SERVER,
TCP listener or database console is enabled. Each pipeline callback opens/closes
its store; concurrent CLI access can fail honestly with a busy catalog. Do not
start a second crawler against one archive. Replacing the catalog with SQLite
requires its normal authenticated dependency delivery and migration tests; it
must not change scheduling ownership.

Schema version1, maintained by idempotent DDL migrations and version rejection:

- `crawl_plans`: canonical deduplicated seeds, one restrictive origin, immutable
  depth/page limits, provider attribution, explicit approval and timestamps.
- `pages`: unique(job,canonical URL), stable page ID and latest snapshot pointer.
- `snapshots`: distinct content versions, SHA256, size, HTTP/content metadata,
  fetch/record times, source kind, depth, source URL, title and extracted text.
- `page_links`: snapshot/source/target/disposition provenance in bounded order.
- `failures`: bounded structured outcome references, independent of Frontier's
  terminal metadata retention. These are observations, not scheduler state.
- `catalog_guard`/`schema_version`: transaction serialization and schema control.

Prepared statements, unique/FK/check constraints and indexes protect catalog
writes. Bodies are limited to128KiB, links to100, plan pages to100, depth to5,
result lists to100. The object store is limited to64MiB; no automatic deletion.
Additional catalog capacity guards reject growth explicitly rather than pruning.

Objects use `objects/<sha256>.html`, never URL-derived paths. Write a private
partial file, force its contents, atomically publish, then commit catalog rows.
A rollback can leave an unreferenced complete blob; it cannot intentionally
publish a completed DB snapshot pointing to a partial object. Cancellation removes
its partial file. Process crash may leave an unreferenced partial/orphan file;
there is no destructive automatic cleanup. Symlink paths are rejected. HTML is
untrusted data: no JS execution, automatic browser/file opening or shell use.
This is process/container persistence, not a claimed power-loss fsync proof.

Manual imports have source kind MANUAL_IMPORT, unknown HTTP status0 and no fetched
at timestamp; they never claim Tor transport or robots validation. A later real
fetch is a separate crawler observation. Identical content shares one blob;
versions and latest-content observations remain catalog metadata.

## Actual crawler integration

Configure `ArchiveParseFilter` after `DiscoveryParseFilter` with `archive.root`
and `archive.job.id` referencing an approved catalog plan. It requires actual
TorProtocol for HTTP/HTTPS, normalized approved origin, successful bounded HTML
and bounded discovery edges. Archive commit occurs before JSoupParserBolt emits
outlinks/documents and before downstream successful Frontier status ACK.

Use `ArchiveStatusUpdaterBolt` in place of the stock status updater to record
bounded FETCH_ERROR/ERROR/ROBOTS_DENIED/REDIRECTION observations at the inherited
store boundary, after the installed scheduler selects status and nextFetch but
before the Frontier write. A generated maxFetchErrors terminal outcome is retained
as TERMINAL_MAX_FETCH_ERRORS without duplicating the retry policy. Catalog failure fails the tuple;
it does not silently record successful archive completion. There is no atomic
transaction spanning catalog and Frontier: archive-first replay is idempotent,
Frontier remains at-least-once, and already committed snapshots survive a lost
subsequent ACK. Finite retry policies are unchanged.

## CLI

Build with JDK25: `ONIONALL_OFFLINE=1 bash scripts/cli/build-cli.sh`.
`onionall` below means `target/onionall/bin/onionall` or its installed equivalent.
Use `--archive-dir PATH` or ONIONALL_ARCHIVE_DIR; default is the XDG data directory.
`--json`, NO_COLOR and noninteractive output follow the existing CLI contract.

```sh
onionall --json crawl plan --import seeds.txt --scope same-host --depth 2 --max-pages 20 --provider user-provided
onionall --json crawl show PLAN_ID
onionall --json crawl approve PLAN_ID
onionall --json crawl start --plan PLAN_ID   # honest exit7: admission blocked
onionall --json crawl status PLAN_ID
onionall --json archive import saved.html --plan PLAN_ID --url APPROVED_ONION_URL
onionall --json pages --job PLAN_ID --limit 20
onionall --json page show PAGE_ID
onionall --json search-local keyword --job PLAN_ID
onionall --json archive export --job PLAN_ID --output summary.json
onionall --json search-provider research --provider ahmia
```

Plans accept one or more same-origin valid v3 onion seeds; arbitrary cross-host
exploration and binary attachments are unavailable. URL-list imports are bounded
UTF-8. Exports are bounded summaries, atomic and refuse overwrite.

SearchProvider distinguishes local archived search from assisted external search.
Ahmia's official terms prohibit scraping/replication without permission:
https://www.ahmia.fi/terms/ (checked2026-11-03). The Ahmia command only returns an
encoded official search URL and manual import instructions; it makes zero HTTP
requests and provides no pretend search results. No Torch address is assumed.

## Verification entrypoints

```sh
python3 -B -m unittest discover -s test/archive
M2_STORM_IMAGE=onionall-storm:3.1.0-jre25-cleanup2 python3 -B test/archive/local_integration.py
M2_STORM_IMAGE=onionall-storm:3.1.0-jre25-cleanup2 python3 -B test/m2/deterministic_recovery.py
```

The controlled local vertical uses CLI plan/approval, real StormCrawler,
TorProtocol and Frontier, A→B→C path discovery with cycle/duplicate/robots/scope
cases, one503 scheduled retry, archive hashes/provenance, real CLI search/export
and recreation. Its result explicitly retains durable_admission_verified=false,
m2_verified=false and m3_verified=false. Exact executions belong in the engineering
ledger; none of these commands authorize repeated public Tor experiments.

## M5 failure observation history

`onionall crawl errors <plan> --limit 20` reads append-only structured observations
with keyset continuation. See [ADR0006](adr/0006-failure-observation-history.md).
Legacy failure aggregates remain readable and are not historical attempts.
Fetch-attempt count and applied Frontier outcome remain UNKNOWN where unproven.
The additive failure_observations_v1 table preserves coreV2/previous-reader
compatibility; history writes commit before normal Frontier write/ACK.
Configured `crawl status --runtime ...` returns structured UNKNOWN plus nonzero
exit6 when authoritative readback is unavailable; offline catalog status can exit0
without asserting admission or completion.
