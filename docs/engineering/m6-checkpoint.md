# M6 checkpoint

Updated: 2026-11-12, Europe/Istanbul. Status: IMPLEMENTED / FINAL PUBLICATION IN PROGRESS.
Branch: feature/m6-discovery-multiseed. Verified starting main:
90df385c9f9c6829e20dabc714ad09ca923205ed; original worktree was clean.
Do not auto-merge: M6 delivery requires owner authorization after reviewable PR.

## Implemented and current evidence

- Kotlin Ahmia provider: ordinary homepage GET form + actual hidden field + search,
  fixed authorized origin, v3 validation, actual-result counting, dedup/provenance,
  true single-page limitation. No target URL fetch during discovery.
- Kotlin ResearchCatalog: additive H2 table in ArchiveStore-owned catalog, revision CAS,
  catalog_guard transactions/savepoints, 4MiB document/100 session bounds. Core schema,
  Frontier/protobuf/admission unchanged; no new database or scheduler.
- Kotlin session workflow/CLI: discover/import/candidates/select, immutable plan with
  distinct per-seed/global caps, batch approve, bounded sequential child jobs through
  existing CrawlService/Frontier/Storm, truthful per-seed/per-host status, local export.
- Observed document candidates from verified archived HTML (PDF/EPUB/TXT/Markdown/
  DOCX/ODT/RTF hints); actual file MIME/length remain null without file response.
  Explicit binary downloads NOT IMPLEMENTED. Forum support is ordinary scoped HTML
  pagination and page-level original/text export, not a universal post parser.
- JSON/JSONL/Markdown/text exports are bounded at8MiB and use new output directories.
- Direct kotlinx-coroutines-core-jvm1.11.0 dependency provides structured cancellation;
  added consistently to both POMs and packaged CLI. Java infrastructure preserved.

Final native crawler-POM verify + CLI package: **303 tests PASS, 0 failures/errors/skips**,
.runtime/m6/cli-build-reviewed.log (initial implementation300, final regressions+3). This includes legacy research/capability/ownership tests
and new provider/catalog/selection/budget/export/CAS tests. Tooling: **47 PASS**,
.runtime/m6/tooling-final.log. Two initial tooling fixtures needed the new research package;
assertions of original failure propagation remain intact.

Defects found and repaired during implementation:
- monitoring file-cache updates could stale the running parent's revision; run patches now
  merge owned fields into a fresh document with bounded CAS retries;
- failed child launches returned exit0; execution-failure aggregation and exit6 now expose them;
- generated runtime parent symlinks are rejected; descriptors use exclusive creation;
- Jsoup absUrl removed userinfo before validation; raw href/base now checked before resolution.
  A regression preserves rejection instead of treating credential URLs as ordinary candidates.

## Genuine Tor discovery

.runtime/m6-real-jjvk2li2/ contains the new private runtime state and H2 catalog.
Session: c779b510-b632-4632-be37-7a315dba2c05.
Query: Kotlin multiplatform. **VERIFIED WITH REAL TOR**: authenticated official Tor
bootstrap100, exactly2 provider requests, HTTP form search success, **61 observed valid unique
URLs**, requested/persisted **20 candidates**. No third-party targets were fetched during discovery.
Use discovery.json and discovery-transport-result.json for retained scalar/provenance evidence.
Raw provider inspection data in /tmp/ahmia-interface-m6/ is private and not Git input.

Owner explicitly approved these candidate IDs/URLs for depth2, per-seed5/global15,
robots enabled, per-host1, no file downloads:

1. ebf40a65-c14a-44c3-ae9b-95af8c1065bb —
   http://eweiibe6tdjsdprb4px6rqrzzcsi22m4koia44kc5pcjr7nec2rlxyad.onion/explore/projects/topics/tpo?language_name=Kotlin&sort=latest_activity_desc
2. 64b1a5ec-fa94-4ae3-b0c3-a67c3a06e619 —
   http://pierovggsterf4w5xplynqtl56srftc242l3g2xloqlty6nv7372xuyd.onion/tag/orange-pi-zero/
3. b0add6d3-6f04-4c52-b939-c324e2c2db68 —
   http://arf5sgoqvzde4vp6coqlmdjroqdngbq3rm5yd6eahndgxadmnieiivyd.onion/talks/34-android-tools-team-bof/

Real three-seed execution/persistence smoke **FINISHED; acquisition PARTIAL**.
Actual official-Tor/Frontier/Storm run in loopback-only namespace, depth cap2,
per-seed5/global15. No system networking/services changed; identity/archive retained.

| Approved seed | Archived HTML | Actual observation |
| --- | ---: | --- |
| Tor GitLab | 0 | ROBOTS_DENIED; reserved1, unscheduled1 |
| Piero | 0 | FETCH_ERROR, two failure observations; reserved1, pending1 |
| DebConf21 | 5 | 69,943 bytes; actual archived depths0/1; reserved5, scheduled_wait5 |

All three child executions were attempted; none had a worker execution failure.
This does not prove terminal crawl success: session PAUSED, terminal/fetched/retry
metrics remain null. No real file candidates were present and no binaries downloaded.
Actual Frontier restart preserved approvals, child IDs, archive/hash/size, graph and
scheduler state; original Markdown export was verified. Authoritative retained evidence:
.runtime/m6-real-jjvk2li2/batch-result.json and batch-transport-result.json.
The outer inherited discovery metric third_party_targets_fetched=0 was incorrect for
this batch; original kept as batch-transport-raw.json, corrected metric is null with
three attempted seed scopes and one host with archived HTML. Discovery evidence remains
separate in discovery-transport-result.json.

Final readback found a real display/export defect: legacy per-job graph lookup capped
results at100 while more edges were persisted. New CLI regression with two genuine
MANUAL_IMPORT pages ×60 links failed120vs100 before fix and passed after. Session results
now aggregate every listed page's latest snapshot links, label LATEST_SNAPSHOTS and retain
the existing per-page100 bound. Real archive readback reports **155 persisted latest-snapshot
edges** (29/47/26/28/25 per page), not a historical all-snapshot count; no page hits100.
Private readback .runtime/m6-real-jjvk2li2/readback-latest.json. No additional crawl was run.

Independent focused review found two additional export issues, now patched with regressions:
- stale result rows could be combined with newer page text; page/job/snapshot/hash identity
  is now checked before readback/export, failing closed on changes;
- untrusted Markdown images/links remained active inside blockquotes; ASCII punctuation
  is now escaped as literal data, in addition to HTML/control escaping.
The new image regression was observed failing before fix; snapshot race regression passes.
Already-running final verification/package finished successfully before pause: **303 JVM
tests PASS, 0 failures/errors/skips**, BUILD SUCCESS and packaged CLI produced. Evidence:
.runtime/m6/cli-build-reviewed.log. Final packaged CLI read/export verification also **PASS**:
.runtime/m6-real-jjvk2li2/final-export-1k72m0de/result.json. All four formats (JSON/JSONL/
Markdown/text) contain all5 real pages and155 latest-snapshot links. Actual object bytes,
SHA256, snapshot/job/page identities, visible text and output hashes were checked; no new
network requests. Final application JAR SHA256
a30cdaba0522a3d5f20bdacd1ee256bf5f23e042efe0b1a0b6f453c8315e25b5.

## Multi-seed deterministic acceptance

**VERIFIED WITH FIXTURE**: actual test/admission/m6_native_smoke.py passed.
Retained state .runtime/m6/multi-834kz_mg/, session0b0b21d5-3753-42e0-8613-9b01fd50e754.
Three distinct hosts:9 genuine HTML pages (3 each), depths0/1/2,33 persisted edges,
9 PDF/EPUB/TXT candidates,15 reservations (5 each),16 actual HTTP requests,
503→200 retry and302. No document download, foreign-host/depth3/robots-denied fetch.
Actual authenticated Frontier process restart preserved candidate selection/approval,
child IDs, HTML/hash/size, graph, file metadata and per-child scheduling state.
Markdown discussion/page-text export verified with actual source markers. Session PAUSED,
not completed. Artifact JAR SHA2568569e93a497e2174d83f1d1867212b73da50611be562713babeb5237003f7cc8.
This is isolated native pipeline evidence, not real external crawling or container recreation.

## Publication and remaining delivery

Owner resumed work after the break on 2026-11-12. No additional crawl was needed for
read/export fixes; official Tor, Frontier and crawler workers from the real run are stopped.
Archives, private identities and all evidence remain retained.

- Existing draft PR7: https://github.com/CallesnikovProduction/onionall-github-mirror/pull/7
- Initial GitHub feature commit335f956d5e1b5adb6d92b63dbaf9e4ea4a3cd5ec and local checkpoint
  08f5d9643b335111ca7bc8f20ebfffffb9968bed have identical tree
  cb24d9297ea842039690d8ae789d928d7e800255 but separate sibling commit IDs.
  Connected GitHub tools work; SSH/gh/Entire GitHub-write authentication was unavailable.
  New GitHub commits must parent actual remote HEAD; compare trees, no force/reset.
- Initial source portable CI **PASS**: https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/43229695593
  Images, both JVM entrypoints, Frontier/tooling, actual canonical depth2 crawl and container
  recreation passed. This canonical fixture is distinct from M6 native three-host acceptance.
- Initial verification CI https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/43229695676
  passed300 JVM and executed suites but intentionally ended exit2/NOT RUN because
  archive-write-ack/product/crash gates were unavailable; those assertions remain unchanged.
  S1-01 and archive-write-ack fault acceptance are not declared closed.
- Final read/export patch has303 native JVM tests PASS and real-archive four-format
  readback PASS. Publish its verified tree, inspect new CI and update PR before final delivery.
- M6 requires explicit owner authorization to merge. Main stays at verified90df385;
  synchronize Entire main only to a verified authorized merged GitHub HEAD. Preserve all
  runtime state, bridges, HTML, catalogs and private raw evidence outside Git.

Useful commands after ensuring no other build owns shared target/:

```sh
ONIONALL_OFFLINE=1 MAVEN_OPTS=-Dkotlin.compiler.daemon=false bash scripts/cli/build-cli.sh
python3 -B -m unittest discover -s test/scripts -v
FIXTURE_PARENT_NETNS=$(readlink /proc/self/ns/net) unshare -Urn sh -c 'ip link set lo up; exec python3 -B test/admission/m6_native_smoke.py --storm-libs .runtime/storm-lifecycle-build-cleanup2/libs --patched-server .runtime/storm-lifecycle-build-cleanup2/context/storm-server-3.1.0.jar'
```

The last command always creates fresh retained test state; it is not a resume command.
Do not rebuild packages while an acceptance worker is using their artifact paths.
Previous M5/real Ahmia HTML remains .runtime/sol-real-b70lba2e/archive/ and is unchanged.
