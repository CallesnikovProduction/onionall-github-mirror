# M6.1 checkpoint

Updated:2026-11-13, Europe/Istanbul. IMPLEMENTED / native forum acceptance IN
PROGRESS / real forum approval NOT YET REQUESTED. This checkpoint records the
state at publication; inspect current Git, PR and private result files on resume.

## Repository and authorization

- feature/m61-forum-research is based on M6 commit
  af0504a4d7d62331b62a67e21e179a889f5083a6. PR7 remained OPEN/unmerged at last
  check, headaf0504a/main90df385. Preserve it; create a separate dependent M6.1
  PR, never silently merge/rewrite M6 or main.
- Worktree was clean before M6.1. Current changes are focused source/test/docs.
  Raw research, runtime catalogs/logs, bridge config and identities stay ignored.
- Owner mission: attachment5b6e7d95-92f9-4c09-80a2-2a7b166979a8. Ahmia provider
  administrator authorization is established. Every newly discovered third-party
  forum needs separate explicit approval of actual URLs/origins and budgets.
  Previous M6 seed approval does not cover these new scopes.
- No M6.1 live keyword discovery or external forum crawl has run yet. Complete
  deterministic pipeline before discovery. Never fetch a result target merely
  to prepare the approval question; use actual provider title/snippet/URL evidence.

## Implemented contracts

Kotlin ThreadCommands plan/crawl/show reuse existing approval, Admission/Frontier
and Storm. Separate immutable provider=m61:thread plan:1..5pages, same origin,
only verified numeric pagination, authoritative depth0..maxPages-1 counts hops.
No depth reset, new scheduler or new DB. Ordinary research-session navigation
supports depth3; legacy plan/run keeps depth2. New binaries read old sessions;
rollback must not launch new thread plans with old binaries lacking the filter.

ForumExtractor uses two explicit controlled DOM contracts, plus conservative
readable-page/static-dynamic-shell fallback. Missing/ambiguous author/date/source
post IDs stay null. Internal observation IDs are separate. ResearchThreads reads
verified original HTML and persists derived captures/posts in existing research
JSON/CAS with snapshot/job/page/time/source-kind provenance. Changed references,
attachments and quotations are not silently deduplicated. Generic/dynamic pages
of an identified thread stay visible in ordinary sessions. Mixed page/start/offset
units retain capture/DOM order with explicit PARTIAL, never a false numeric order.
Observed pagination coverage is not proof of exhaustive thread coverage.

UriReferences fixes real query-only href/Location path loss, preserving percent
escapes and raw query data. DOM entity decoding occurs once. Known pagination
query keys can be reordered only in thread mode; arbitrary queries are untouched.
Human Markdown/text now contain summary/sources/discussions/files/limitations,
not serialized maps; JSON/JSONL keep full metadata. Untrusted Markdown/HTML/images
remain literal. Four golden fixtures preserve layout and source boundaries.
Persisted bounded PAUSED is distinct from unavailable live scheduler counters.

## Verification

- Current native packaged CLI: **340 JVM tests PASS,0failures/errors/skips**,
  .runtime/m61/cli-build-reviewed.log. Tooling **53 PASS**,
  .runtime/m61/tooling-reviewed.log. Application JAR SHA256:
  b0b5b194a5c9334bd8b6089ada173c2bd60d2d7c236ce847f0d67f89ab53aa24.
- Both independent review findings were reproduced as2failures in9tests:
  .runtime/m61/review-regressions-before.log; after focused fixes,26targeted
  extraction/pagination/export tests PASS, .runtime/m61/review-regressions-after.log.
  No assertions were weakened. Earlier query-only pagination failure and malformed
  Location exception-type regression were also retained and fixed.
- Actual old M6 H2:20candidate URLs,0literal &amp; in canonical URLs/wrappers;
  12host groups retain20distinct pages. Only Markdown encoded ampersands. No
  persisted canonicalization corruption was established. Scalar private evidence:
  .runtime/m61/url-canonicalization-evidence.json.
- Actual old M6 archive re-read with new CLI:5pages/155links/0posts, PAUSED with
  PERSISTED_BOUNDED_RUN and live scheduler freshness=false; unavailable counters
  stay null. New Markdown17,413bytes versus old353,013, all source URLs/page IDs
  and output hash checked. Evidence:
  .runtime/m61/m6-readable-46nucrt7/result.json. This is genuine Tor-origin HTML,
  NOT proof of forum messages or a new network request.
- Runtime still owns one crawlid/origin per worker, one fetcher thread, embedded
  H2 and archive-wide locks. Socket-monitor overlap controls are not crawler
  parallelism proof. GateE stays BLOCKED unless actual independent-host overlap
  is demonstrated; do not widen hosts/launch uncontrolled workers to fake it.

## Current actual pipeline run

Fresh retained state:.runtime/m61/forum-ve8mw_le/; private console:
.runtime/m61/native-reviewed.log. Actual Frontier + Storm LocalCluster run in a
loopback-only namespace using the current packaged artifact. No host network
configuration changed. Test scopes: two homepage→category→listing→topic depth3
jobs; two complete three-page threads; one separate two-page-cap thread; actual
retry/graph/attachments/extraction/HTTP-in-flight measurements and Frontier restart.
Synthetic origins are NOT external Tor/forum evidence. Do not claim final PASS
until result.json and the owning process exit are inspected.

**Do not rebuild target while this run's workers use its artifact paths.** To
pause, create .runtime/m61/forum-ve8mw_le/pause.requested. The harness finishes
its current bounded CLI operation, then starts no next one and performs owned
cleanup; a site batch can contain multiple workers. This is not instant product
cancellation. SIGINT exact harness also sets the cooperative flag, but never
signal a whole process group containing children.

## Preserved previous pause

Old state .runtime/m61/forum-f4vq_8yx/ is retained. GateA passed8HTML/depth0..3;
ThreadA passed3pages/4posts/503→200/Markdown; ThreadB was interrupted, budget and
restart NOT RUN. Old harness exit130 is NOT graceful cancellation proof because
Python subprocess.run killed its active CLI on KeyboardInterrupt. Exact owned
processes disappeared and POSIX locks became free;21HTTP starts/ends, active0,
max1. Old stale RUNNING journal was deliberately preserved. Private pause ledger:
.runtime/m61/forum-f4vq_8yx/pause-result.json. Do not erase or reuse it blindly.

## Remaining delivery

1. Inspect final current native result/process state. Diagnose failures from real
   evidence; retain archives, no blind retries or assertion weakening.
2. After deterministic PASS run actual Ahmia forum-keyword discovery through the
   existing officialTor/isolation. Present suitable actual URLs and ask owner
   approval: combined20HTML/origin including thread allocations,5/thread,
   depth3ordinary navigation, per-host1/robots/no binaries. Wait before crawling.
3. Continue independent docs/CI while approval is pending. GateG remains pending
   until approved/executed; never fabricate accessible forum activity or posts.
4. Publish verified source-only feature to a dependent PR, inspect portable CI;
   preserve unchanged legacy NOT_RUN fault/crash gates. Connected GitHub tools
   work; prior SSH/gh writes were unavailable. Compare trees, no force/reset.
   No automatic merge or Entire-main synchronization without owner authorization.

Contract/CLI/rollback:[M6.1](../m61-forum-research.md).
