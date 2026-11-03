# Archive MVP execution ledger — 2026-11-03

Baseline branch migration/java25-storm31 at0ae1b7c. Existing Recovery implementation
and user documentation consolidation were present before this increment; preserve
separate checkpoints and never include the user's Markdown deletions/summaries/ZIP.

## Contract and storage checkpoint

Implemented local H2 2.4.240 catalog (explicit cached dependency), schemaV1,
plans+approval, canonical same-origin scope, bounded snapshots and link provenance,
structured unique failures, local title/text search and bounded export. HTML blobs
are SHA256-addressed, forced and atomically published before catalog commit.
No RocksDB schema or volume migration; no scheduling table in the catalog.

ArchiveStoreTest8 + ArchiveCommandsTest4 PASS in
`.runtime/archive/store-checkpoint/tests-after-cancellation-fix.log`.
Earlier compile/cancellation failures remain as separate logs; fixed pre-existing
interrupt handling before JDBC calls and Java checked-exception interop.

## Pipeline and CLI checkpoint

ArchiveParseFilter is ordered after DiscoveryParseFilter and before parser
outlink/document emission. ArchiveStatusUpdaterBolt.store sees the actual status
and nextFetch selected by the installed scheduler, records catalog outcomes,
then delegates unchanged Frontier serialization/ACK behavior. A third-error
terminal observation is retained as TERMINAL_MAX_FETCH_ERRORS. No second retry
policy or coordinator exists. Catalog failures fail the tuple before success ACK.

Commands: crawl plan/show/approve/start/status, archive import/export, pages,
page show, search-local, search-provider. `crawl start --plan` intentionally exits7
for unavailable ADR0002 admission; approval is not an admitted/running job.
Manual import records HTTP0/unknown and MANUAL_IMPORT, never fake Tor/robots.
Ahmia support is an encoded official link plus manual import assistance only.

`JAVA_HOME=<selected JDK25> ONIONALL_OFFLINE=1 bash scripts/cli/build-cli.sh`:
105 JVM tests PASS, zero failures/errors/skips, full packaged/shaded build PASS.
Evidence `.runtime/archive/build-cli-verified.log`. Additional pipeline tests
cover commit-before-return, catalog failure and real scheduler terminal outcomes.
The first new scheduler regression lacked scheduler.class; corrected and retained
its failing build log rather than suppressing the failure.

Python M2 suite113 PASS (`.runtime/archive/python-m2.log`), existing packaged/PTY
CLI16 PASS (`python-cli.log`), controlled archive fixture2 PASS (`python-fixture.log`).

## Actual controlled crawl→archive checkpoint

Command:

```sh
JAVA_HOME="$PWD/.runtime/platform-migration/jdk25/jdk-25.0.5+8" \
M2_STORM_IMAGE=onionall-storm:3.1.0-jre25-cleanup2 \
python3 -B test/archive/local_integration.py
```

Executed once successfully. Evidence:
`.runtime/m2/onionall-brawler-m2-8430d45708c6/archive-local.json`;
driver `.runtime/archive/e2e-driver.log`.
Job/plan d291b6df-087f-43dd-b05d-a6302aca8807, explicit CLI approval, same origin,
maxDepth2/maxPages4. Actual StormCrawler/TorProtocol/Frontier with SOCKS ATYP3:
/a→/b→/c, cycle+duplicate, robots-denied /blocked, binary/outside/depth exclusions.
/b returns503 once then200 via the existing1min test retry; archived pages3,
snapshots3, Frontier records4. All HTML objects' SHA256/size, depth0/1/2,
source-page links/history and local text search verified through real CLI.
Two unique failure records (FETCH_ERROR and ROBOTS_DENIED) persist in the catalog.
Frontier recreation and independent CLI process reopen preserve state; export
succeeds. Containers/networks removed; Frontier volume retained; host snapshots
unchanged. No external Tor daemon is used in this diagnostic.

The manifest explicitly keeps durable_admission_verified=false, m2_verified=false,
m3_verified=false. Current local-test planner has process-local quota only;
this run does not prove quota conformance across worker recreation/ACK loss.
Production admission remains disabled per the safe fallback in the request.
Exact server transaction/migration/rollback prerequisites remain documented in
frontier-admission-implementation-plan.md; no count-before-put workaround exists.

## Recovery regression and preservation checkpoint

Current packaged artifact passed `test/m2/deterministic_recovery.py` again in
`.runtime/m2/onionall-brawler-m2-b2f4257c45be/deterministic-recovery.json`.
All pending-before-outage/persisted-error/stored-date-retry/terminal-characterization
flags true; cleanup, volume retention and host equality true. Driver retained at
`.runtime/archive/recovery-regression-driver.log`. This is independent of archive
acceptance and still m2_verified=false.

The previously uncommitted verified Recovery implementation was saved separately
as commit06c6366 before the archive checkpoint, so a fresh checkout of the new
archive test has its shared teardown dependency. No user documentation deletions,
summary files, scripts/AGENTS.md or ZIP are included in these feature checkpoints.
