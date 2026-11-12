# M6 checkpoint

Updated: 2026-11-12, Europe/Istanbul. Status: IMPLEMENTED / ACCEPTANCE IN PROGRESS.
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

Fresh native crawler-POM verify + CLI package: **300 tests PASS, 0 failures/errors/skips**,
.runtime/m6/cli-build-final.log. This includes legacy research/capability/ownership tests
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

Real multi-seed acceptance is RUNNING, **not yet PASS**. Host harness:
.runtime/m6/tor_batch.py, console .runtime/m6/real-batch.log; child evidence will be
.runtime/m6-real-jjvk2li2/batch-result.json. It uses loopback-only namespace, private UNIX
SOCKS relay to official bridge-only OnionTrafficOnly Tor, owning real Frontier and existing
Storm LocalCluster adapter. No system networking/services changed; old identity/archive retained.
The outer helper was adapted from discovery: its inherited field
third_party_targets_fetched=0 is discovery-only and MUST NOT be used as a batch metric.
Use actual per-seed/page observations in batch-result.json; correct the outer summary after run.

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

## Exact continuation

1. Observe running native/real processes/logs, do not restart blindly or overwrite state.
   Tool session IDs may expire across chat turns; inspect private log/result paths first.
2. Finish and diagnose actual M6 fixture, file candidates, exports and restart assertions.
3. Finish approved real3-seed crawl; report actual per-host outcomes/depth/data, including
   robots/network failures and UNKNOWN terminal provenance. Do not broaden scope on failures.
4. Review diff, add actual commands/results to docs/m6-research.md/checkpoint and update
   relevant AGENTS navigators. Root/CLI/archive/research navigators now describe M6 routing.
5. Check whitespace, links, scoped secrets and source-only staging. Preserve all .runtime,
   bridges, catalogs, raw HTML, downloaded research and user data outside Git.
6. Commit logical checkpoints, publish feature/m6-discovery-multiseed to GitHub and create
   a reviewable PR. Existing SSH/gh/Entire GitHub-write auth was unavailable last delivery;
   connected GitHub tools worked. Verify published tree equals local tree if using those tools.
   Do not update protected main or auto-merge without the owner's new M6 authorization.

Useful commands after ensuring no other build owns shared target/:

```sh
ONIONALL_OFFLINE=1 MAVEN_OPTS=-Dkotlin.compiler.daemon=false bash scripts/cli/build-cli.sh
python3 -B -m unittest discover -s test/scripts -v
FIXTURE_PARENT_NETNS=$(readlink /proc/self/ns/net) unshare -Urn sh -c 'ip link set lo up; exec python3 -B test/admission/m6_native_smoke.py --storm-libs .runtime/storm-lifecycle-build-cleanup2/libs --patched-server .runtime/storm-lifecycle-build-cleanup2/context/storm-server-3.1.0.jar'
```

The last command always creates fresh retained test state; it is not a resume command.
Do not rebuild packages while an acceptance worker is using their artifact paths.
Previous M5/real Ahmia HTML remains .runtime/sol-real-b70lba2e/archive/ and is unchanged.
