# M5 Operational Closure execution ledger

## Execution boundary вЂ” 2026-11-04

Owner-approved branch feat/m5-operational-closure starts exactly at
0a645fdd63b6f21d9af66b988c027d77fb9eb14f. S1-01 remains present and partially
verified; main, migration/java25-storm31 and security/comprehensive-hardening
are protected from this work. User specs and DeepSeek audit remain untracked,
never automatically staged. Root AGENTS refactor is HOLD.

Order: DEV verification baseline, REC restart qualification, ARCH topology ADR,
ARCH read-only model, DOM state/history and REC bounded resume, deployment/service
seam. No second scheduler; Frontier owns scheduling/reservation/quota, H2 is an
observation catalog. External Tor and historical topology retirement excluded.

## DEV-01a вЂ” verification signal and Markdown file links

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
