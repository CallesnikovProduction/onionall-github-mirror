# M5 Operational Closure execution ledger

## Execution boundary — 2026-11-04

Owner-approved branch feat/m5-operational-closure starts exactly at
0a645fdd63b6f21d9af66b988c027d77fb9eb14f. S1-01 remains present and partially
verified; main, migration/java25-storm31 and security/comprehensive-hardening
are protected from this work. User specs and DeepSeek audit remain untracked,
never automatically staged. Root AGENTS refactor is HOLD.

Order: DEV verification baseline, REC restart qualification, ARCH topology ADR,
ARCH read-only model, DOM state/history and REC bounded resume, deployment/service
seam. No second scheduler; Frontier owns scheduling/reservation/quota, H2 is an
observation catalog. External Tor and historical topology retirement excluded.

## DEV-01a — verification signal and Markdown file links

Baseline before implementation: Maven verify on approved JDK25/Maven3.10.0,
offline cache:124 run,123 PASS,1 SKIP,0 fail/error. Initial sandbox invocation
failed Kotlin daemon connection; actual local-runtime invocation passed. Logs:
.runtime/specs-dev01/baseline-maven*.log. This is not complete product acceptance.

Implementation: scripts/runners/verify.py is the single cross-platform local
verification entry point (usage in scripts/README.md). Unique JSON/log evidence,
no cleanup or system installs; default excludes real Tor, product Docker fixture
requires explicit opt-in. Native Frontier test compilation uses the exact pinned
base JAR, leaving production Bash build intact. Python suites isolated by process;
skipped tests or absent prerequisites cannot produce a top-level PASS.

Independently reproduced exactly11 broken tracked Markdown file links. Repointed
them to existing consolidated M0/M1, CLI and migration/Kotlin reports. Fragment
validation and prose/code references are not covered. Root AGENTS untouched.

Negative control: test_verification.py executes a disposable failing unittest
(1 != 2) and checks exit1; missing local link is detected before creating its
target; missing/wrong Frontier artifact is NOT RUN/FAIL. No product assertions
or acceptance conditions weakened. Five targeted verifier tests pass.

Current full baseline under .runtime/verification/run-*/: actual Frontier4/4
PASS; M2:113 run,105 PASS,2 FAIL,4 ERROR,2 SKIP; M1:32 run,31 PASS,1 SKIP;
M3:2 PASS; archive:2 PASS; security:4 PASS. Scripts suite:13 tests,18 subtest
errors due to unavailable Bash/POSIX commands on Windows (not auto-skipped).
These open platform gates prevent overall PASS. Product Docker E2E NOT RUN:
no supported Linux Docker runtime; real Tor OUT OF SCOPE. Existing M2 permission
failures and Bash errors retain their original security meaning/acceptance.

Initial runner spike exposed Windows .cmd relative path syntax; corrected to
an absolute executable path before acceptance. Intermediate failed logs retained.
No Maven wrapper/CI claimed yet: separate DEV-01b increment follows this gate.

Rollback: revert this additive runner/tests/link-change commit; no schema/data
migration or product scheduling change. Commit SHA resolves from git log for this
ledger path; each completed scope is immediately pushed and remote SHA verified.

DEV-01a final invocation: .runtime/verification/run-luwtg6wh/result.json.
Markdown PASS; Maven124 run/123 PASS/1 SKIP (gate NOT RUN due to skip); Frontier4/4
PASS; Python results above unchanged. Overall FAIL is expected and retained.
Command: python -B scripts/runners/verify.py --offline --maven <verified-mvn.cmd>
--maven-repository <local-cache>, with JAVA_HOME25 and URLFRONTIER_BASE_JAR set.
Targeted command: python -B -m unittest discover -s test/scripts -p test_verification.py -v.
No full CI/product acceptance asserted. Diff whitespace and Python AST checks pass.

## DEV-01b — pinned Maven Wrapper and CI

DEV-01a commit2b12c0688a9508c4f368e03d9194aab73c0953d6 immediately pushed to
origin/feat/m5-operational-closure; remote SHA matched.

Wrapper3.3.4 source-only scripts from official Maven Central, upstream SHA1 matched,
local archive SHA2566cb584c2bc907b849a0b931d8266d3ff3214cdd3127115ed4f49fb7176413d36.
Maven3.10.0 ZIP matched previously verified publisher SHA512; pinned SHA256
1f6d9909266510f039f59aa0e13dcd2c66da85f043e56276e41f2918f8bddaff. No binary in Git.
First Windows wrapper download/version invocation PASS on JDK25.0.5. Negative
fixture with false distribution checksum exited1 before Maven; actual log retained
under .runtime/specs-dev01/wrapper-negative-*/. Wrapper regression2/2, verifier5/5
PASS. YAML parse and Python AST checks PASS. Linux wrapper/hosted CI NOT RUN locally.

CI added for M5 pushes, PRs and manual dispatch, with read-only token, SHA-pinned
official Actions and JDK25. No image execution during base JAR recovery. CI must
remain non-green if required product E2E is NOT RUN; no continue-on-error or
acceptance assertion weakened. Product Docker assembly/qualification remains
DEV-02/G6 prerequisite, not silently credited by local JVM PASS.

Rollback: remove the additive workflow/wrapper/tests commit; no persistent migration
or runtime network policy changed. Wrapper first bootstrap needs Maven Central;
Maven -o does not prevent that first download. Existing S1-01 guard/client wiring
is untouched. Root AGENTS and user untracked files unchanged.

Final wrapper-backed run: .runtime/verification/run-zfzf9pt1/result.json; Maven
124 run/123 PASS/1 SKIP with fresh-report filtering, M1_HTTP_SMOKE=1:32/32 PASS,
Frontier4/4 PASS. M2 and scripts platform failures unchanged; overall FAIL retained.
Command: JAVA_HOME25, URLFRONTIER_BASE_JAR, MAVEN_USER_HOME=<isolated cache>,
M1_HTTP_SMOKE=1 python -B scripts/runners/verify.py --offline --maven-repository <cache>.

## REC-01 — current-path restart qualification, no production patch

DEV-01b commit51b910e959bd04de8b7988f4cd34ae56c9a3151d immediately pushed; remote
SHA matched. DEV-01 complete acceptance remains open: native Linux/CI/product
Docker qualification not credited by Windows local evidence.

Source: RocksDBService constructor170-174 unconditionally selects
recoveryQscan(true). readQueueInfos690-724 half-open deleteRange and omitted
incrementActive in recoveryQscan(false)262-264 remain dormant on the current
public constructors. REC-06/07 rejected as active defects; no schema/counter
production patch, no unsafe lastKey arithmetic.

Added RecoveryCountersConformance to both build.sh --test and native verify gate.
Actual fixture has three queues (active2/completed1, active0/completed2,
active3/completed0), eight durable reservations, a far-future due row and due
rows. Two clean close/reopens plus deliberate child Runtime.halt23 (no close)
verify exact active/completed counts, nextFetch dates and reservation/session
readback against pre-crash snapshot. Due membership exact, no duplicate leasing
within a call; replay leaves counts/quota/identity unchanged and completed URLs
unscheduled. Authentication remains covered by actual AdmissionRpcConformance,
not by this direct owning-service fixture.

Fresh compile with JDK25 --release11 and pinned service JAR; all5 Frontier drivers
PASS under .runtime/specs-dev01/rec01-final-*/. No prior PASS substituted. JVM-only
fixture requires no Docker/onion sockets; addresses are inert synthetic test data.
Clean fixture stats metadata is naturally written; crash skips metadata close.
No DB deletion, no schema change. Linux build.sh execution NOT RUN here: no Bash.
Not a power-loss/OS-crash or long-duration soak claim.

Rollback: remove added conformance/test-hook commit; no persistent migration.
Next ARCH-01 evidence map/ADR, then ARCH-02 requires truthful UNKNOWN for missing
terminal provenance. A completed storage row cannot prove success vs terminal
failure; no scheduler/category inferred by subtracting H2 pages.

## DEV-01c — Linux fresh-checkout fixture defect

REC-01 commit54da938665b8d93acfa9fc77cdf4e784145345ad immediately pushed and
remote SHA verified.

Actual hosted Linux run41840064032 (commit51b910e) completed FAIL, not merely
assumed to be missing E2E: Maven124/124 PASS, M1 localhost32/32, M2 113/113,
archive2/2, security4/4, scripts15/15 and Frontier4/4 PASS. M3 had1 error because
test_prepare used TemporaryDirectory(dir=.runtime/m3) before creating that parent.
Artifact13327544794 downloaded via GitHub connector, SHA256
bd25ef897dd7e38451cb4818965f8b053644730be3716a1054178528951c6245 verified;
logs under .runtime/specs-dev01/ci-41840064032.zip. Run URL:
https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/41840064032

One-line test setup correction creates its owned parent; all assertions unchanged.
Reproduced a fresh project namespace with no .runtime, invoking the actual test
and prepare() against copied crawler.flux: PASS1/1. Normal M3 suite PASS2/2.
No legacy/product topology substitution, network change or scheduler change.
Product Docker E2E remains NOT RUN. Newer CI must independently qualify REC5/5
and this fix; these results are scoped to exact commit51b910e.
Rollback: revert additive test setup correction. No persistent migration.

## ARCH-01 — canonical topology ADR and acceptance inventory

DEV-01c commitd133f2be015f7e63178ba17ae962645eba195d7e immediately pushed;
remote SHA matched. Added ADR0004 from actual CrawlExecution, root Flux and
fixture callers: product5 bolts/7 streams vs legacy9/15; mandatory capability
clients and ordered durable-discovery/archive filters documented. Exact test
entrypoints classified by topology; neither component tests nor stock fixture
PASS is inherited as generated product E2E. No topology extraction, retirement
or feature merge: working graph remains unchanged. Full archive-write-failure
ACK behavior on generated product still needs isolated E2E qualification.

Read-model stop condition reached per 01_ARCH.md: terminal writes delete
scheduling metadata and retain only an empty existence pointer. Storage completed
is UNSCHEDULED, not success/terminal reason. ARCH-02/DOM dependent implementation
held for owner contract decision (question sent): bounded UNKNOWN/null outcome
counts with proof-required COMPLETE, or separate durable terminal provenance
design/compatibility review. No H2 scheduling or silent DONE inference. Independent
documentation and verification work can continue.

ADR local file-link validation and diff whitespace PASS; no runtime claim from
this documentation-only scope. Product E2E NOT RUN, archive failure graph gate OPEN.
Rollback: documentation-only revert; no migration or behavior change.

## DEV-01d — explicit packaged CLI coverage

ARCH-01 commitb2daf99d45595fd2b074ddffa4f4440680fe9939 immediately pushed and
remote SHA matched. Owner read-model decision remains pending; no dependent
ARCH-02/DOM implementation while that required question is open.

Extended Python verification inventory with existing test/cli suite. Missing
Linux CLI bundle must be visible, not silently omitted from the baseline.
Actual local invocation --python-suite cli --suite-result <evidence>:12 tests
skipped,0 failure/error, exit2; gate NOT RUN. Evidence:
.runtime/specs-dev01/cli-baseline.json and cli-baseline.log. No skip/assertion
changed. Existing verifier regression5/5 PASS (exit/result aggregation already
checks required skips cannot be green). Package build/PTY qualification requires
Linux and remains a deployment prerequisite. No product logic/schema change.
Rollback: remove additive suite registration; no data migration.

## Current checkpoint / blocked dependent work

DEV-01d commit0581cbc9d90944b9a48c83f1f89b6f29a75f5267 immediately pushed and
remote SHA verified. A subsequent documentation-only correction repaired UTF-8
heading corruption introduced by Windows default-codepage read_text during ledger
append. Six affected headings restored; other changed Markdown inspected, no
product source/evidence change. Future ledger edits explicitly use UTF-8.

Actual Linux CI run41841699632 at b2daf99 completed every selected core gate PASS:
Maven, Python M1/M2/M3/archive/security/scripts, five Frontier conformance drivers
and local file links. Overall NOT RUN, exit2 because product E2E is not executed;
GitHub correctly shows an unsuccessful required gate. This supersedes M3's earlier
fresh-checkout error for that SHA only, not a current full product acceptance.
https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/41841699632
Artifact13328558207 SHA256b4aecf527fb8d5b812158d0934acc6a70bf9d6b640e7754646a0efb5fb25b79c.
CLI gate added later at0581cbc: Windows12 skipped/NOT RUN, not covered by the
b2daf99 run. Latest hosted runs must be inspected separately, no inherited PASS.

Completed local increments: explicit baseline/link gate, pinned wrapper/CI,
REC-01 characterization, fresh-checkout fixture fix, ARCH-01 ADR/inventory and
explicit CLI gate. Full DEV/ARCH acceptance remains PARTIAL. ARCH-02 and DOM plus
REC retry/resume remain held at the specification's required state-inference
decision; options sent to owner (UNKNOWN/null, or separately reviewed durable
terminal provenance). DEV-02 full paired deployment/E2E and service extraction
not implemented ahead of their read-model dependencies. No root AGENTS refactor,
schema change, recovery product patch, external Tor, source-branch change or merge.

Final checks: Python/XML/YAML syntax, tracked Markdown file links, diff whitespace,
limited secret signatures (synthetic baseline credential-URL fixtures only),
13-file audit SHA256 preservation, protected source/AGENTS unchanged. This is not
a complete entropy/secrets/CVE scan. All completed commits immediately pushed
to feat/m5-operational-closure and remote SHA verified. Final self-hash resolves
with git log; no recursive documentation commit. Continue from owner read-model
decision, then qualify packaged CLI and paired product fixture before claiming M5.

## ARCH-02 — authenticated read-only list/counts and strict typed client

Owner resolved the ARCH state-inference question: success/terminal unknown stay
null; COMPLETE requires proof. Prior hold resolved. Added read actions to existing
authenticated Admission/Command only, state_version1. No protobuf/persistent
schema/index, quota/reservation/recovery policy or Tor boundary change.

AdmissionState reads at most the current100 reservations using the existing
length-prefixed session key prefix, sorts immutable ordinals and validates complete
readback. Keyset cursor binds session/through/after, freezes membership under
concurrent appends; per-page scheduling observation remains fresh. limit<=200 plus
<60KiB JSON byte budget. admissionLock serializes durable state and the existing
queue monitor freezes lease publication; observational lease copy never prunes
expired entries. CF0/CF1 distinguish pending/held/scheduled_wait/unscheduled/unknown;
no invented done/success/terminal/retry. Earliest candidate time includes lease
expiry, not guaranteed execution. Full contract/rollback in frontier-server README.

Strict FrontierReadback/AdmissionClient typed methods reject unsupported outcomes,
versions, malformed/nonconsecutive pages, moving cursor watermark, malformed
counts/nullability and explicit deadline failures, without anonymous/stock fallback.
Old callers untouched; previous server rejects new reads explicitly.

Tests: actual RocksDB/RPC AdmissionStateConformance verifies five reservations/two
unscheduled, held and future scheduling, missing/wrong capability, read-only mode,
zero RocksDB latest-sequence change and identical raw lease map after reads,
concurrent append without pagination loss, long URLs/byte-bounded pages, invalid
cursor/limit, two clean restarts and separate-process Runtime.halt23. Leases are
ephemeral and expected to return pending after server restart. Six actual server
drivers PASS in .runtime/specs-dev01/arch02-final-zz1ixu3n. Targeted app tests
FrontierReadbackTest,AdmissionClientTest,CapabilityClientsTest:12/12 PASS;
.runtime/specs-dev01/arch02-client.log. Full verify recorded below after completion.
Commands use owner-verified JDK25/Maven Wrapper/offline cache and pinned Frontier
JAR as in earlier scopes; no external requests or Docker fixtures executed here.

Compatibility/rollback: additive actions and client methods; previous binaries
read identical persistent schema. Revert binaries together, no DB rewrite. This
does not close S1-01 full integration/independent review or product E2E. Next DOM
status derives from this contract and retains unknown completion provenance.

ARCH-02 full offline Maven verify completed:127 run,126 PASS,1 existing Windows
SKIP,0 failure/error; .runtime/specs-dev01/arch02-full-verify.log. Fresh full JVM
run, not targeted-only counts. Python verifier5/5 and tracked Markdown file-link
checks PASS. Diff whitespace PASS. S1-01 guard unchanged and actual RPC rejection
regressions included. Historical reservation enqueue ACK is omitted from read rows
to avoid confusing it with current scheduling state.

## DOM-01a - pure lifecycle/completion predicate

ARCH-02 commit24dce2aab79f3d37191b043811d966b9b4541061 immediately pushed and
remote SHA verified. Final server rerun6/6 PASS at
.runtime/specs-dev01/arch02-publish-qjmqcqw5.

Added JobStatus State/Completion/Worker/Assessment and ADR0005. Pending/future
work after any worker exit is PAUSED only with stopped-process evidence, not
FAILED/COMPLETE. Unavailable server, unknown liveness and unresolved storage rows
stay UNKNOWN. Positive COMPLETE/PARTIAL tests use explicit authoritative outcome
proof fixtures; current v1 real readback deliberately cannot supply those proofs.
No archive-count subtraction or historical failure-as-current-terminal inference.

Targeted fresh command: mvnw -o -B -Dmaven.repo.local=<cache>
-Dtest=JobStatusTest,FrontierReadbackTest test.10/10 PASS,0 skip/error; log
.runtime/specs-dev01/dom01-policy.log. This is pure semantic verification, not
CLI/product E2E. No runtime policy/schema migration, Tor or scheduling change.
Rollback is binary-only; status assembly/audit wiring is the next bounded scope.

## DOM-01b - live versioned status/start, process observation and bounded audit

DOM predicate commitf1d2c7932a346f2b63a418d3caf066f04fbcf583 immediately pushed
and remote SHA verified. Status/start now use authenticated typed Frontier counts
and the same JobStatus/JobStatusView schema. Legacy state/passed execution fields
remain, explicitly marked legacy. UNOBSERVED/CATALOG_PLAN_ONLY replaces stale
BLOCKED_ADR_0002 in plan presentation; related contract assertions updated to the
new required value, not weakened. Unknown success/terminal remains null and v1
exhaustion cannot manufacture COMPLETE/PARTIAL.

WorkerObservation requires local process scope plus matching PID start instant;
probes existing ownership lock without creating/writing it. Foreign/legacy/bad
markers or lock without identified process are UNKNOWN. New starts record process
identity; status never kills processes. Pinned metadata is observational, not
authorization. Status returns current or explicitly stale/null counts after RPC
failure, with no anonymous/stock fallback; start retains final worker evidence
when post-exit admission readback is unavailable.

Existing H2 schemaV2 JSON stores latest status and bounded32-transition audit.
Existing catalog guard serializes concurrent observations, preserves audit through
execution writes and retains the original admission observation clock on status-only
updates. Dropped/truncated transition count is explicit. No new table/schema or
H2 network/server mode; two-JVM contention is an explicit error, never empty.

Actual tests: targeted JobStatus/WorkerObservation/JobStatusIntegration/AdmissionClient/
ArchiveStore/ArchiveCommands/CrawlWorker:38 run/37 PASS/1 existing Windows SKIP.
Concurrent two-store audit:40 observations ->32 retained+8 dropped, no lost updates;
H2 schema remains2. Actual separate JVM holds catalog and causes explicit status
IOException, then normal status after owner exits. PID/start/scope, stale markers
and unchanged lock bytes checked. First Windows fixture failed reading through a
second handle under mandatory exclusive lock; corrected test to use its owning
handle, retaining before/after byte assertions and all deadlines/skips. Failure log
.runtime/specs-dev01/dom01-integration.log retained; corrected run
dom01-integration-after.log passed.

Actual CrawlExecution.start with valid local descriptor/mock admission and Java
executable rejecting Storm arguments returns versioned INCOMPLETE/PAUSED-or-UNKNOWN,
never COMPLETE/FAILED job inference. Not actual Storm graph E2E. Initial fixture
missing socks_host rejected before start; completed fixture passed7/7 with process
identity tests (dom01-start-after.log); negative logs retained. No external SOCKS/Tor
requests made. New main-class javac --release25 -Xlint:all exit0/no warnings at
.runtime/specs-dev01/dom01-lint-znptxdwt. Full verify follows.

Compatibility: additive job_status_version1 fields; old execution keys preserved.
Plan admission string intentionally corrected; old parsing of ADR blocker must
update. Old worker markers may return UNKNOWN until a new run records identity.
Rollback uses prior binaries against unchanged schemaV2; additive JSON fields are
ignored, audit retained. No source branch/main/security-RC changes.

Final DOM-01b full offline verify:141 run/140 PASS/1 existing Windows SKIP,
0 failures/errors; .runtime/specs-dev01/dom01-final-verify.log (BUILD SUCCESS).
Secondary diff review replaced nullable-Boolean ternaries with explicit branches;
added deadline reachability-null and precise held-lock basis assertions so generic
UNKNOWN exception handling cannot mask an internal branch error. Final changed
main classes -Xlint:all exit0/no warnings. Markdown links/diff/signature gates PASS.
No timing/security assertion weakened; S1-01 capability wiring/guards, protobuf,
Tor isolation and persistent schema remain unchanged in this scope.

## DOM-02 - structured append-only failure observations

DOM-01b commit87b719a30e57d9d9b306de381855d5b8deeff4a3 immediately pushed;
remote SHA verified. Before implementation compatibility spike at
.runtime/specs-dev01/dom02-compat-blvgl545 compiled actual Security RC ArchiveStore
source0a645fdd as previous reader, added a representative extension to an owned
fixture, and verified previous read/closed backup+restore/unchanged HTML hashes.

Added independently versioned failure_observations_v1 table/index, without changing
coreV2 tables or schema_version2. Persistent extension is explicit, no legacy
backfill. Legacy failure identity/aggregates preserved. Atomic legacy+event write
under existing catalog guard; event insertion failure rolls back aggregate.
1000 observations/job and existing catalog128MiB bound; no automatic deletion.
Event fields are bounded phase/source/HTTP/transport and scheduling-intent values.
Callbacks/replays are observations, not proved network attempts: fetch_attempt_count
and applied_to_frontier remain null; no H2 scheduler or terminal authority.

Added offline crawl errors paging with fixed sequence watermark and limit<=100,
readback count/latest observation/status hint. Raw exceptions never stored.
Installed SimpleFetcherBolt bytecode inspection proved stale HTTP/protocol fields
can survive pre-response failure; new tests mask those fields, map exact timeout
tag and retain UNKNOWN for lost cause. Bytecode retained under specs-dev01.
Existing scheduler3-error test now additionally verifies three observations rather
than two collapsed categories, without changing its schedule/terminal assertions.

Full final-DDL compatibility proof at dom02-finalcompat-lhlepdhk: previous reader
reads upgraded core, modern reader sees all three observations after old-reader
open/close, restored closed backup reads with old binary, blob hashes unchanged.
No source/user archive reset or destructive migration; fixtures retained.
Targeted history/pipeline/CLI15/15 PASS; earlier storage/integration selection
30 run/29 PASS/1 existing Windows SKIP. Logs dom02-history.log, dom02-cli-signal.log.
Full offline verify succeeded before final view hints; final targeted selection
checks those fields and H2/start integration. Final results below.

Packaged CLI source review found configured status previously required nonzero
on unavailable Frontier. Preserved that contract: structured UNKNOWN with exit6,
no admission; added actual gRPC UNIMPLEMENTED CLI regression. This corrects a
DOM-01 signal regression before declaring packaged CLI qualification. No assertion
weakened; the original Python distribution test remains unchanged.

Rollback: previous binaries still read coreV2 and ignore/preserve new table; no
DROP, old-row rewrite or version downgrade. Current v1 cannot prove distinct actual
attempt count or final terminal application, intentionally UNKNOWN. Archive failure
when catalog itself is unwritable may leave only worker evidence; no fake audit.

DOM-02 full offline verify:147 run/146 PASS/1 existing Windows SKIP,0 failure/error,
BUILD SUCCESS (dom02-full-verify.log). Final targeted19/19 PASS after common-view
hints and actual second-connection/thread append test (dom02-publish-targeted.log).
New fixture server explicitly binds127.0.0.1. Changed-main Xlint exit0 with two
pre-existing ArchiveStatusUpdaterBolt serialization warnings; compiled prior
87b719a source and reproduced the same two (dom02-baseline-lint-*). New record/
store/CLI/view classes had no extra warning. Link/diff/limited signatures pass.

## DEV verification isolation follow-up

DOM-02 commit61674319c670b736f1f0458cf370963ba1b2dabb immediately pushed and
remote SHA verified. Limited fixture review made RPC listeners explicitly IPv4
loopback rather than provider-default bind addresses: AdmissionClientTest,
FrontierReadbackTest, JobStatusIntegrationTest and two actual Frontier drivers.
Production RPC bind/transport policy and all auth/timeout assertions unchanged.
App selection19/19 PASS (loopback-rpc-fixtures.log); actual Frontier6/6 PASS at
.runtime/specs-dev01/loopback-rpc-km5mo26x. No external probe/onion request or host
network configuration change. Next REC ownership-crash characterization.

## REC-09 controlled crash characterization and owner design decision

Test-only loopback commit ec7e6c232462310500c4e5be6c8ad13a4779566b immediately
pushed and remote SHA verified. Actual CrawlWorker force-kill fixture CONFIRMED:
child with owned loopback listener remains alive, OS lock released, current
requireAvailable accepts, and actual start reaches a rejecting admission tripwire.
No second crawler or Frontier mutation occurred; exact owned child identity
rechecked before cleanup. This is JVM process fixture evidence, not full Storm.
Characterization1/1 and worker/status selection11/11 PASS; PASS means defect
reproduced, not safe recovery. Logs rec09-crash-characterization.log and
rec09-publish-characterization.log; small retained proof/owner log under
.runtime/worker-crash-characterization/case-*/.

Product HIGH conditional overlap/availability risk, security LOW/conditional MEDIUM
with authorized restart/crash prerequisites, not S1-01 auth bypass. ADR0007 records
affected symbols, conditions, compatibility and exact acceptance gates.
verify.py explicitly marks worker-crash-acceptance NOT RUN until an actual guard
regression exists; no all-green from a positive characterization.

Owner selected archive-level versioned fail-closed ownership: live child/UNKNOWN
blocks start, legacy migration only with proved stop, no OS components. This
checkpoint retains the pre-fix reproduction; implementation is next. No source
worker behavior changed in this checkpoint, no schema/Tor/host configuration change.

Checkpoint recheck initially FAIL:10 PASS/1 ERROR (rec09-checkpoint-current.log).
New fixture published child-ready before closing JSON; reader saw an empty file.
Changed fixture publication to atomic same-directory move; existing assertions and
deadlines unchanged. Cleanup first allows the owned wrapper shutdown hook when
child identity has not yet been read. Verification-runner unit tests5/5 PASS; tracked
Markdown links PASS (run-25jlsvpz); git diff --check clean. Full product/Storm
crash acceptance NOT RUN. Owner design accepted; implementation remains next.

After atomic-ready fix: current worker/status selection11/11 PASS,0 skip/error/failure
(rec09-checkpoint-atomic-ready.log). Python AST and limited credential signatures
for four scoped files PASS; not a full secrets/security audit. No production
worker change. Checkpoint is ready for its authorized atomic commit/push.


## REC-C approved fail-closed ownership implementation

Previous checkpoint1955ef75ddb9561765f76b44ac82e3f969e36ea6 was immediately
pushed to origin/feat/m5-operational-closure with matching remote HEAD. Owner
approved versioned archive-level ownership, UNKNOWN denial and no OS components.

Added WorkerOwnership v1 sidecar under existing archive OS lock. PREPARING intent
precedes config/JAR/admission, LAUNCHING uses a UUID handoff bound to exact live
parent scope/PID/startInstant; wrapper then pins owner/child and publishes RUNNING.
Atomic temp force+move, no non-atomic fallback. STOPPED requires observed owned
Process completion plus absent/stopped observed descendants; missing child identity
or unassisted crash remains UNKNOWN/BLOCKED. Unknown/reused process identities never
authorize a signal. Unpinned observed descendants may only discharge by complete
absence; a still-present/reused PID blocks. No signaling from stored metadata.

Actual legacy/history guard runs before resource writes/RPC. Read-only H2 history
check also blocks a used legacy archive with missing old lock. No retrospective
whole-tree migration is possible from old PIDs; legacy metadata remains intact and
BLOCKED pending an operational stopped-proof review. Changed work/plan cannot bypass.
Status prefers canonical marker and no longer infers stopped from disappeared legacy
root PIDs. Admission/token/reservation/quota/scheduler/schema/Tor contracts unchanged.

Current evidence:
- POC8/8 PASS (rec-c-spike.log), then selection19/19 PASS (rec-c-targeted3.log).
- Earlier targeted failures retained: missing fast-child identity correctly yielded
  UNKNOWN; normal positive fixture now keeps child observable for1s, without changing
  original deadlines. Ephemeral descendant with no startInstant is never signaled;
  wait for absence or retain UNKNOWN. Additional UNKNOWN->PAUSED transition changed
  the new status fixture's exact audit expectation to3, retaining prior PAUSED checks.
- First full verify157 run/1 FAIL/1 SKIP (rec-c-full-verify.log): a new test incorrectly
  assumed Process.destroy invokes a hook on Windows. Actual child survived. Replaced
  that unsupported assumption with explicit Windows live-child/BLOCKED assertions,
  pinned fixture-only cleanup; Linux expects the separate graceful hook behavior.
- Full offline JVM verify157 run/156 PASS/1 existing Windows SKIP,0 failure/error,
  BUILD SUCCESS (rec-c-full-final.log). No historical result counted as current.
- Actual Frontier6/6 PASS (rec-c-frontier-ak4dk1nn): admission/auth/sync restart,
  migration, shutdown, counters and read-only counts unchanged.
- Changed-main javac --release25 -Xlint:all exit0, no warnings (rec-c-lint-4jndllhd).
- Runner unit5/5 PASS. Final diff/link/limited-signature checks below.

Compatibility/limits: additive sidecar; no persistent schema change, no destructive
migration, no native/service/host network change. Old binaries ignore marker and
lose containment; rollback requires proved stopped processes and preserved evidence.
Marker is NOT OS process containment. Unobserved detached descendants, full Storm
crash-tree qualification, Linux native run and power-loss filesystem semantics stay
OPEN. verify.py retains worker-crash-acceptance NOT RUN specifically for Storm gate.
S1-01/full audit remains PARTIALLY VERIFIED / NOT CLOSED. Next scope stays within
REC retry/resume before DEV paired deployment/ARCH application service.

Final REC-C selection20/20 PASS,0 skipped (rec-c-publish-targeted.log), including
strict marker fields and preparation lifecycle checks. Links PASS (run-uh47r6qt),
Python AST/diff check/limited credential signatures13 files PASS. No user untracked
file staged. Publication SHA/remote result retained in ignored rec-c-publication.json
and user handoff after push; this journal never claims full Storm/security closure.


## DEV-B paired assembly prerequisite / REC-C publication receipt

REC-C commit4c2afb9fb9ba90e5b0f6f30049cd9750746f3ce1 immediately pushed;
origin/feat/m5-operational-closure HEAD verified identical. GitHub Compare with
migration/java25-storm31 reports ahead19/behind0, including5 Security RC commits
and14 M5 commits; source branch remains853934e57a17b827487b023e8e2c295375290b65.
Actual Linux CI run41886030820 on that exact commit: Maven PASS, Python M1/M2/M3,
archive/security/scripts PASS, all6 Frontier drivers PASS. Overall NOT RUN/exit2,
not a code failure: CLI bundle absent, full Storm crash and product E2E not run.
This also executes Linux WorkerOwnershipTest including its native destroy path;
no Windows-only result promoted to Linux proof. Full Storm tree remains OPEN.

Continued README order: REC-B actual 503/retry/resume needs generated Storm/SOCKS
fixture, whose paired product artifacts were not built by CI. Narrow prerequisite:
CLI build now invokes pinned project wrapper rather than unpinned PATH Maven.
CI builds app+CLI and real Frontier JAR via existing entrypoints before verify,
records source/artifact/base hashes, retains build logs; no new scheduler/runtime.
Two actual Bash negative contracts: missing wrapper never falls back, failed
wrapper exit42 propagates before packaging, including offline flag and spaced root.
Git Bash sandbox first could not initialize (0xC0000142); same tests run with
normal approved subprocess access2/2 PASS. This was not a product test failure.
YAML and embedded provenance Python parse; real Bash syntax check PASS. Linux
packaged assembly qualification is pending the next pushed CI run; no deployment
PASS or product acceptance claimed. Host tools/settings unchanged, no installed
component, real onion query or user untracked file included.


DEV-B commitfd874e9b69322b75838d12df2444d841e1890914 pushed immediately,
remote SHA matched. Actual Linux run41887165093 assembled app/CLI/Frontier
successfully, emitted paired-build.json; Maven157/157 PASS, packaged CLI PASS,
all6 Frontier PASS. Whole run FAIL because existing InstallShellTest build-cleanup
fixture mocked PATH mvn, not new project mvnw (expected37, actual127). Retained
artifact checksum41f105b43dbb0e447697900202bf30efba6bdee51b453686deaea91eac043bb0;
selected result/provenance/scripts log read without extracting runtime archives.

Minimal fixture correction supplies project wrapper stub, preserves exact37 and
cleanup-message assertions. Git Bash prepended GNU tools before fake rm; normalize
test tool PATH inside Bash so the same cleanup-failure assertion actually executes
on Windows as well. Real scoped fixture1/1 PASS locally with normal subprocess
access; no production/package source change. Linux repeat qualification follows
push. Original failed run remains FAIL; not relabeled as a passing global result.
