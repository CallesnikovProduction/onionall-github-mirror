# Research sessions and provider adapters

Inherit root and src instructions. Kotlin owns M6 application behavior;
ArchiveCommands/ArchiveStore supply only Java compilation-boundary forwarding.

- ResearchCatalog borrows ArchiveStore's connection; additive research_sessions_v1
  stays in the existing H2 catalog. Preserve schema_version1 envelopes, catalog_guard,
  revision CAS, caller transaction/savepoint ownership and old core schema compatibility.
- DiscoveryProvider/AhmiaDiscoveryProvider submit the actual ordinary provider form.
  Owner authorization for Ahmia search is established. Keep fixed provider origin,
  actual response counts, original wrappers/query provenance, strict v3 validation,
  cancellation, no result-target fetching and no fabricated pagination.
- ResearchTorTransport delegates to TorProtocol: no DIRECT or target DNS, no automatic
  redirects. Provider grant is scoped to explicit discovery, never crawler robots.
- ResearchWorkflow creates one immutable same-origin CrawlService plan per selected
  seed. Sum of allocated child caps must fit the separate global cap before approval.
  Frontier owns actual reservations, scheduling and retries; coroutines only coordinate
  bounded child launches. Preserve existing archive worker ownership and explicit approval.
- Selection freezes at planning. Keep observed file metadata patches CAS-safe against
  concurrent monitors; never overwrite newer execution state with stale observations.
- Frontier v1 lacks terminal provenance: unknown fetched/retry/success/terminal counts
  remain null. Archive acquisition, execution failures and job completion are distinct.
- ResearchFiles uses verified archived HTML and link hints. Never invent MIME/length,
  follow credentials/symlinks, fetch files automatically or imply downloads are implemented.
- ResearchExporter writes bounded new local outputs with URL/time/job/session boundaries.
  Remote text is untrusted data; do not execute it or send it to external AI services.

Contracts and operator examples: docs/m6-research.md. Evidence and continuation:
docs/engineering/m6-checkpoint.md. JVM regression tests live under src/test.
