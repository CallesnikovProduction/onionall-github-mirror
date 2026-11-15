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
- ThreadCommands/ForumPagination create a separately approved thread-only existing plan
  with provider=m61:thread, max1..5 pages and Frontier pagination depth0..maxPages-1.
  Never reset reservation depth or admit arbitrary same-origin navigation in this mode.
  Ordinary research session navigation supports depth0..3. Do not launch thread plans
  using older binaries that lack the thread policy; rollback is read-only for them.
- ForumExtractor supports two explicit controlled DOM contracts, not universal engines.
  Missing or ambiguous source author/date/post IDs remain null. Keep quotations, links,
  observed attachment hints and per-page extraction limitations separate.
- ResearchThreads reads verified immutable HTML, records actual page/job/snapshot provenance
  and caches derived observations with existing CAS. Nonunique or conflicting IDs must not
  silently merge messages. Observed pagination coverage never proves an exhaustive thread.
  Keep generic/dynamic captures of an identified thread. Compare numeric pagination
  only within one counter kind; mixed units retain capture order with explicit PARTIAL.
- UriReferences in the Tor package resolves query-only/empty-path references consistently;
  HTML entity decoding belongs to the DOM boundary once, not general URL normalization.
- Human Markdown/text must not dump serialized session/page maps; preserve full metadata
  in JSON/JSONL. Golden fixtures under src/test/resources/research/export fix readable layout.
- Existing single-scope worker/archive ownership is a concurrency boundary. Do not launch
  uncontrolled child processes/coroutines or widen allowed hosts to simulate parallelism.

Contracts and operator examples: docs/m6-research.md. Evidence and continuation:
docs/engineering/m6-checkpoint.md. JVM regression tests live under src/test.
Forum workflow/compatibility: docs/m61-forum-research.md.
