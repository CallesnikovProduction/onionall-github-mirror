# M6 research sessions — compatibility and execution contract

M6 adds query/candidate/selection orchestration over the existing crawler.
A session contains up to 20 explicitly selected seed URLs. Each seed owns an
existing immutable same-origin crawl plan and Frontier admission session.
Cross-host links remain observations; they never enlarge another plan's scope.

## Durable state and compatibility

The additive `research_sessions_v1` table lives in the existing H2 catalog.
Kotlin ResearchCatalog borrows the ArchiveStore connection; Java exposes only
compilation-boundary forwarding methods. Core schema version 2, protobuf,
Frontier RocksDB and admission contracts remain unchanged. Whole-document
research updates use catalog_guard and a compare-and-set revision. Plans,
approval, candidates, original result provenance and child job IDs survive
restart. Old binaries ignore the extension; rollback leaves it intact and must
not drop tables, reset approvals or launch untracked jobs. A newer unsupported
research document version is rejected. No separate database or scheduler.

Selection is editable until planning. The persisted plan freezes selected URLs,
depth, per-seed caps and the separate global page cap. Sum of immutable child
page caps must not exceed the global cap: Frontier enforces each child cap, so
no competing runtime quota counter is introduced. Approval applies once to this
exact batch. No network fetching occurs during selection/planning/approval.

## Execution and truthful status

The existing archive/worker ownership allows one active worker per archive.
M6 coordinates bounded child launches sequentially (effective concurrency 1,
below the requested ceiling 4; per-host 1). Each worker gets a bounded slice;
a slow seed cannot occupy an unlimited session. A session deadline and
structured coroutine cancellation bound coordination; existing identity-aware
worker cleanup remains authoritative. Pending child jobs persist for resume.
This is orchestration, not replacement of Frontier retries or URL scheduling.

Observation of archive pages means data acquired, not terminal success.
Frontier v1 cannot establish success/terminal/retry_wait for unscheduled rows.
These metrics remain UNKNOWN/null. Session outcomes separate acquisition,
execution, pending children, available authoritative scheduler counts and unknown
terminal provenance. A zero worker exit never manufactures COMPLETED.

## Provider authorization and content

Ahmia administrator authorization is established owner context. The explicit
provider operation submits the site's actual public HTML search form, including
its normal hidden field, through existing TorProtocol. It is recorded as
OWNER_AUTHORIZED_AHMIA_DISCOVERY; ordinary Storm crawl robots remain enabled.
No generated tokens, CAPTCHA/login bypass, DIRECT fallback or third-party fetch
occurs during discovery. Search-result wrappers are parsed as candidate evidence,
not treated as blanket cross-host redirect authorization. Raw results remain
untrusted data. Actual result counts and pagination limitations are exposed.

## Files, discussion pages and export

File candidates come from observed archived links/attributes. Extension and link
MIME are hints, not verified response headers; unknown sizes/MIME stay null.
Original HTML and page-level visible text remain available. Ordinary forum
pagination follows the same scope/depth/quota/robots contracts; no universal
structured-post parser or dynamic/login content coverage is claimed.
Exports bound size, preserve URL/timestamp/job/session boundaries and write only
new local outputs. No external AI upload, script execution, archive extraction
or automatic binary download is part of this contract.

Session graph readback aggregates the persisted links of every listed page's
latest snapshot, rather than applying the legacy per-job 100-edge display cap.
`links_view=LATEST_SNAPSHOTS` and `metrics.listed_link_observations` identify this
view; it is not a historical all-snapshot total. Each page retains the existing
100-observation bound, exposed as `per_page_link_limit` and a conservative
`links_may_be_truncated` flag. Export rejects a snapshot change between listing
and page-text readback; retry after the worker stops. Remote Markdown syntax is
escaped as literal text so captured images/links do not become active markup.

## CLI workflow

Legacy `research plan --url ...`, `run --plan ...`, local search and ordinary
crawl commands retain their contracts. New session workflow (canonical Docker
runtime must already be ready as described in local-runtime.md):

```sh
./onionall cli research discover --provider ahmia --query 'Kotlin multiplatform' --limit 20
./onionall cli research candidates SESSION_ID
./onionall cli research select SESSION_ID --ids CANDIDATE_ID_1,CANDIDATE_ID_2,CANDIDATE_ID_3
# Or --all-valid and --exclude ID,... after inspecting the actual list.
./onionall cli research plan --session SESSION_ID --depth 2 --pages-per-seed 5 --global-pages 15
./onionall cli research approve SESSION_ID
./onionall cli research run --session SESSION_ID --total-seconds 900 --slice-seconds 90 --request-timeout-ms 30000
./onionall cli research status SESSION_ID
./onionall cli research pages SESSION_ID
./onionall cli research links SESSION_ID
./onionall cli research files SESSION_ID
./onionall cli research export SESSION_ID --format markdown --output /state/research-export-SESSION_ID
```

Use different new output directories for `--format json|jsonl|markdown|text`.
Container `/state` is the retained runtime bind mount, so the example export
survives CLI container exit. Native CLI calls supply `--runtime` and
`--admission-token-file` explicitly for run/status. `discover` needs only the
runtime SOCKS descriptor and receives no Frontier capability. Batch run
`--approve` is an explicit optional shortcut; the launcher never inserts it.

Manual seeds use `research import --seed URL --seed URL --label TEXT` and are
labelled USER_SUPPLIED/MANUAL_INPUT, never provider results. All candidates start
unselected. Select/exclude before plan; scope and child IDs freeze at planning.
Approval survives restart. A failed/partial planning can be resumed idempotently.
Only selected URLs receive child plans. Up to20 seeds are supported; global
reservations stay <=100, per-seed cap1..20 and depth0..2. The requested provider
result limit1..20 is independent of those crawl limits. Current verified Ahmia
form accepts <=100 query characters and <=10 words and serves one result page.

An initial run attempts pending child plans. `--resume` prioritizes pending
children, then permits explicit resumed execution of already-attempted plans;
Frontier still decides whether their URLs are due. Default total600s/slice45s
can leave children pending; budget must include a worker slice plus existing
bounded cleanup. Current archive/H2 ownership permits one worker at a time:
effective global concurrency1, per-host1, ceiling4 respected. A slow host gets
only its configured slice. The outer canonical CLI timeout accounts for batch
budget and cleanup. Monitoring can report catalog contention during live worker
callbacks; inspect between/after slices. No second H2 server was introduced.

`status/results` distinguish selected/planned/attempted seeds, execution failures,
seeds with archived HTML, pages archived and known authoritative scheduler counts.
Fetched attempts, successful/failed terminal seed counts, completed and scheduled
retry counts remain null where Frontier v1 cannot prove them. Multiple seed URLs
on one hostname are grouped in `per_host` with `seed_scopes`; `per_seed` preserves
the individual child observations. Execution failure returns a nonzero run exit;
a PAUSED result with data is not COMPLETED.

File discovery returns stable IDs, source page/job/session, target URL, filename,
extension and observed HTML type/download hints. Actual file content_type and
content_length stay null. Explicit document downloading is NOT IMPLEMENTED;
no binary transfer or auto-open/extract occurs. Forum-style page1/page2/page3
pagination uses the ordinary same-origin crawler and is bounded/deduplicated.
Exports preserve original HTML paths plus page-level text, title, source URL,
fetch time and all graph/candidate/file/error/session provenance. Structured
posts, author fields or dynamic content are not fabricated.
