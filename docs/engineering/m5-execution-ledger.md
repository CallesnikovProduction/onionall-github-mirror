# M5 Operational Closure execution ledger

## Execution boundary Р В Р вЂ Р В РІР‚С™Р Р†Р вЂљРЎСљ 2026-11-04

Owner-approved branch feat/m5-operational-closure starts exactly at
0a645fdd63b6f21d9af66b988c027d77fb9eb14f. S1-01 remains present and partially
verified; main, migration/java25-storm31 and security/comprehensive-hardening
are protected from this work. User specs and DeepSeek audit remain untracked,
never automatically staged. Root AGENTS refactor is HOLD.

Order: DEV verification baseline, REC restart qualification, ARCH topology ADR,
ARCH read-only model, DOM state/history and REC bounded resume, deployment/service
seam. No second scheduler; Frontier owns scheduling/reservation/quota, H2 is an
observation catalog. External Tor and historical topology retirement excluded.

## DEV-01a Р В Р вЂ Р В РІР‚С™Р Р†Р вЂљРЎСљ verification signal and Markdown file links

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

## DEV-01b РІР‚вЂќ pinned Maven Wrapper and CI

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
