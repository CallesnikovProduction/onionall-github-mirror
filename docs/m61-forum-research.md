# M6.1 forum research contract

M6.1 depends on open M6 PR7. The existing Frontier remains admission, quota and
retry authority; StormCrawler performs every crawler fetch. Kotlin adds thread
identity, conservative extraction, derived research observations and readable
exports. No browser, JavaScript execution, second crawler or file downloader.

## Site navigation and separate thread scope

Explicit research-session plans now allow ordinary navigation depth0..3 and up
to20 pages per seed. Existing same-origin, robots, global allocation and Tor-only
contracts remain. Legacy research plan/run limits remain unchanged.

A thread is a separately inspected and explicitly approved immutable child plan:
max1..5 pages, same origin, only observed numeric pagination of the same thread.
Frontier's ordinary depth0..maxPages-1 counts pagination hops in this plan. It
never resets authoritative depth and never reuses a site-navigation reservation.
The separate global cap equals the thread cap. Running another approved session
is a new explicit allocation; operator smoke budgets must include both allocations.

Supported thread identity uses ordinary page/start/offset numeric query values
or terminal /page/N/ paths. Unique allowlisted query keys t/f/id/topic/thread and
pagination keys may be reordered for this thread-only identity. Unknown,
duplicate or encoded ambiguous pagination keys do not authorize expansion.
Query-only href and HTTP Location retain the current document path through the
shared RFC3986 UriReferences helper. HTML entity decoding occurs once in the DOM;
URI strings and percent escapes are not HTML-decoded again.

## Extraction and persistence

Two explicit, fixture-tested DOM contracts identify post boundaries: traditional
div.post[id=pDIGITS|post-DIGITS] with direct postbody/content, and semantic
schema.org DiscussionForumPosting articles. These are controlled DOM contracts,
not an assertion of universal engine compatibility. Author, raw datetime and DOM
post ID stay null when missing/ambiguous. Main text, nested quotes, link targets
and observed attachment hints remain distinct. Internal observation IDs are
labelled separately from actual source_post_id.

Unknown layouts return readable page text with zero invented posts. Static
JavaScript shells can be labelled UNSUPPORTED_DYNAMIC_CONTENT by an explicit
heuristic; no browser rendering or login bypass is attempted. Truncation,
conflicting IDs and missing extraction coverage produce PARTIAL evidence.

Derived threads/posts are stored in the existing research_sessions_v1 JSON
extension using revision CAS. Every capture records page/job/snapshot IDs,
fetch/recorded time and CRAWLER versus MANUAL_IMPORT. Original HTML remains the
content-addressed archive authority. New binaries read old M6 sessions; old
binaries must not launch M6.1 thread plans because they lack the thread-only
filter. Rollback is read-only for these plans; do not drop tables or erase data.

OBSERVED_PAGINATION_CAPTURED means every eligible pagination target visible in
the inspected HTML was captured. It never proves an exhaustive thread:
full_thread_coverage_verified remains false. Page order follows verified numeric
pagination before DOM post order. Nonunique source IDs are not silently merged;
changed links/attachments/quotes remain separate conflicting observations.
Captured generic/dynamic pages of an identified thread stay visible even in
ordinary research sessions and make structured coverage partial. Mixed pagination
units (for example page3 versus start20) retain capture/DOM order and explicitly
mark ordering/completeness ambiguous rather than comparing incompatible numbers.

## Human and machine exports

Markdown/text contain summary, concise sources, discussions/messages, file hints
and explicit limitations. Full machine metadata stays in JSON/JSONL. All remote
text is literal escaped data; images, HTML and retrieved instructions do not
become report metadata or execute. Generic fallback uses cleaned readable text
while original extracted text and HTML remain in the archive/machine records.
Golden tests fix layout and source boundaries. Outputs remain bounded8MiB,
new-directory-only and never overwrite operator files.

## Lifecycle and concurrency

A persisted bounded run ending PAUSED remains PAUSED when Frontier is offline,
with status_basis=PERSISTED_BOUNDED_RUN and scheduler_readback_current=false.
Unavailable counters remain null. Acquisition is a separate status; nonempty
HTML and worker exit0 never imply terminal completion or exhausted URLs.

Current runtime has one crawlid/origin per worker, one fetcher thread, archive-wide
ownership locks and embedded H2. Raising coroutine counts or allowed hosts would
break these boundaries. Safe cross-origin parallel progress therefore requires
an explicit runtime/ownership design beyond this milestone's current adapter.
Instrumentation distinguishes measured crawler overlap from socket-monitor
negative controls. Gate E must be reported blocked if actual two-host overlap
cannot be demonstrated; configured thread counts are not proof.

## CLI

With the canonical runtime ready, from the project root:

```sh
./onionall cli research import --seed APPROVED_HOME --label 'Forum navigation'
./onionall cli research select SESSION --all-valid
./onionall cli research plan --session SESSION --depth 3 --pages-per-seed 20 --global-pages 20
./onionall cli research approve SESSION
./onionall cli research run --session SESSION --total-seconds 900 --slice-seconds 90
./onionall cli research thread plan APPROVED_THREAD_URL --max-pages 5
./onionall cli research approve THREAD_SESSION
./onionall cli research thread crawl THREAD_SESSION --total-seconds 600 --slice-seconds 90
./onionall cli research thread show THREAD_SESSION
./onionall cli research export THREAD_SESSION --format markdown --output /state/new-thread-export
```

Native CLI requires explicit --runtime and --admission-token-file for crawl/run.
Selection/planning/approval/show/export initiate no crawler fetch. Ahmia discovery
uses existing administrator authorization, but newly discovered third-party
forums require separate owner approval of exact URLs/origins and budgets before
any real crawl. Previous M6 seed approval does not cover new forums. Real smoke
uses at most1–2 approved origins, total20 HTML per origin including independently
allocated thread work, max5 per thread, per-host1, robots and no binaries.
