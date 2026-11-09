# Astra engineering checkpoint

Updated: 2026-11-09 (Europe/Moscow). Pass 1: operational baseline, PARTIAL.
Branch: `feature/astra-onionall-reconstruction`.
Base: `4b0d4daa9a56aafc9303cd7d34972bf126b1288a` (`origin/main` at preflight).
This base includes Security RC/S1-01, M5 and the owner's portable runtime changes;
`af171e78d9863e7c1ad2e946c7574d596229ada8` is its ancestor. The delivered checkpoint
SHA is obtained from `git log -1` for this file; no self-referencing hash is stored.

## Continuation: canonical research acceptance

The continuation directive prioritizes actual depth-2 fetch/archive/recreation.
`test/admission/canonical_e2e.py` now exercises the public canonical launcher in
the existing portable CI. The fixture adds a relative 302 to an already admitted
target, preserving the original graph for legacy callers. Local new acceptance
negative controls + real loopback SOCKS redirect: 2 passed outside the socket-
restricted sandbox; existing fixture tests: 2 passed. Initial sandbox socket
attempt failed with WinError 10013. AST/YAML/diff/secret-signature checks passed;
18 owner file hashes remain unchanged.

Fresh [CI 42628616540](https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/42628616540)
on `bd2dcca3f594072d9dbb6868f37f3aa6cfd80570` passed the canonical E2E: 6 actual
HTTP requests; 503 then 200 retry; 302 redirect; 3 archived HTML pages at depths
0/1/2; 9 persisted edges; 5 Frontier reservations. SHA256 object readback, graph
identity and actual Frontier/SOCKS container recreation all passed. Archive,
graph, quota, scheduling counts and next-attempt date survived down/up. No DB
rows were injected. Final status was UNKNOWN / INCOMPLETE (0 pending/held,
4 scheduled-wait, 1 unscheduled); success/terminal remain null. This is not
proof of a terminal crawl or real Tor. The sanitized result is a CI artifact.

The next verified-local scope adds Kotlin `research plan/run/results/links/files`
over existing Java services using a narrow compilation-boundary adapter. No new
schema, scheduler, process owner or authorization is introduced. Bounds are
depth <=2/pages <=20; approval remains explicit. Results expose real persisted
pages/links/file candidates and labelled bounded metrics; unknown attempt counts
and durations remain null. First unit run exposed SQL `max_depth` versus public
JSON `depth` confusion; fixed without changing that contract. Current targeted
JVM regressions: 28 passed, 0 skipped. The next CI will exercise these new public
commands through the same crawl/recreation gate.

The user approved the Ahmia onion homepage, same-host depth 2/max 20. Real Tor
remains BLOCKED: no private local bridge file/runtime, no local Docker, and the
repository's Actions secret list is empty (names-only inspection). No bridge
defaults or direct fallback were substituted. [Ahmia terms](https://ahmia.fi/terms/)
prohibit scraping without permission; automatic provider search is not enabled.
Torch's programmatic endpoint/identity is not verified. Manual selected seeds
can already enter `crawl plan --import --provider ...`; no mock search results.

## Execution boundary and decisions

The owner master directive is the active reconstruction scope: preserve working
Java and durable state; new application functionality is Kotlin-first. Begin with
the existing canonical workflow. Do not replace Frontier scheduling, weaken S1-01,
change Tor isolation or silently migrate persistent state. Source comments/Javadoc
from the owner remain intact. No code/data deletion or language migration occurred.
The local Windows host cannot run the Linux Docker workflow: Docker was absent
from PATH/standard install location and WSL reported not installed. No installation,
service, host networking or security-setting change was attempted.

## Current repository inventory and implemented flow

| Area | Classification and actual responsibility |
| --- | --- |
| `onionall`, `compose.runtime.yaml`, `scripts/runners/runtime.py` | ACTIVE: canonical Linux/Bash build, lifecycle and CLI forwarding; custom Frontier, internal crawler network, retained volumes |
| `docker/toolchain/`, `.mvn/`, `crawler/pom.xml`, `frontier-server/pom.xml` | ACTIVE: container JDK25 + wrapper; separate crawler/server Maven builds |
| Root `pom.xml` | ACTIVE compatibility build, despite artifact name `onionall-reactor`; no `<modules>`. Shares source/output and much build configuration with crawler POM; duplication exists, deletion/consolidation is not yet justified |
| Java `crawl/`, `admission/`, `archive/`, `cli/` | ACTIVE: application boundary, approved launch, authenticated admission, worker ownership, catalog/HTML storage and presentation |
| Kotlin `tor/`, `discovery/` | ACTIVE transport/scope and offline planner; discovery Storm adapters explicitly local-test |
| `frontier-server/src/main/` | ACTIVE owning RPC/RocksDB authority; existing capability protects mutations, server owns reservations/quota/scheduling |
| `src/test/`, `frontier-server/src/test/`, `test/` | TEST INFRASTRUCTURE: JVM, durable conformance, platform/runtime fixtures; retained |
| `compose.m*.yaml`, legacy Flux/resources | TEST INFRASTRUCTURE / historical deployment contours with active callers; not competing product startup instructions and not proven dead code |
| `docs/adr`, operational contracts and ledgers | ACTIVE contracts plus HISTORICAL evidence; dated reports do not establish current execution |
| `docs/audits/`, `onion_codex_specs/` | Owner-authored untracked input; 18 files hash-preserved, excluded from commits |
| `target/`, `frontier-server/target/`, caches/logs | GENERATED; no generated files committed |
| `.runtime/local*`, Docker volumes, private files | Persistent/user state, not disposable merely because ignored; no user-state migration/deletion |

Product flow remains CLI plan/approval -> CrawlService/CrawlExecution -> protected
Frontier -> generated Storm graph/TorProtocol -> discovery reservation and HTML/H2
archive -> authenticated status updates. H2 is a catalog, never the scheduler.
The continuation adds Kotlin research CLI orchestration over that same flow;
no second scheduler, provider scraping or authentication subsystem is introduced.

## Concrete correction in this scope

The shell builder ran seven server suites, Python verification only six, Maven
only four. Maven now also runs RecoveryCountersConformance, AdmissionStateConformance
and AdmissionAmbiguousRpcConformance; Python now runs the last of these. Existing
assertions are unchanged. A dispatch regression enumerates actual conformance
sources, verifies every driver is selected and an ambiguous-RPC failure keeps the
overall result failed. This regression uses mocked execution; real runs are below.

The Frontier README incorrectly described a root reactor and `-pl frontier-server`.
It now documents the existing independent Maven imports and `-f` commands. The
portable workflow also triggers on the Astra branch, using its existing Linux
runner/internal fixture. Workflow execution is separate from local verification.

Read-only inspection of the owner's preceding portable CI run
[42518705000](https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/42518705000)
found a concrete startup failure: `Path.open(opener=...)` raises TypeError before
the capability file is created. This was reproduced locally with two real-file
regressions before changing implementation. The controller now uses built-in
`open(..., 'x', opener=...)`, retaining exclusive creation and mode 600, existing
token reuse and the strict permission check. This is a local correction to existing
Python deployment tooling, not a new application service or authorization system.
The historical CI build results are not credited as validation of the Astra SHA.

Published source checkpoints: `8f17d2f` (complete conformance selection),
`2e1df11` (capability creation) and `584d7d0` (single tmpfs mount). The fresh
[Astra CI run 42522560099](https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/42522560099)
on `2e1df11` built all images, passed container JVM/Frontier/tooling tests and
Compose syntax checks. Startup then exposed an independent existing YAML defect:
unquoted commas in the flow-list tmpfs entry created five mount paths, and Docker
rejected `exec` as nonabsolute. Quoting the single mount preserves rw/exec/nosuid/
nodev/mode=1777. A Compose CLI regression checks normalized mounts for all three
worker-derived services; it needs no Docker Engine. Local YAML parsing reproduced
the five-element input and confirms one corrected element. Windows without Docker
skips this Compose regression explicitly; the successful Linux run executed it.

The fresh [Astra CI run 42523305221](https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/42523305221)
on full source SHA `584d7d0b8ebb0a03e791e2e2b2bb17f4488341ef` completed successfully.
Both Maven application entrypoints ran the same 253 tests with no failures,
errors or skips; these are not 506 distinct tests. All seven real Frontier
conformance suites passed, and all 36 tooling tests passed, including private
token mode/exclusive creation and the actual Compose CLI mount regression.
The tooling negative-control test deliberately prints an ambiguous-RPC `FAIL`;
its assertion checks failure propagation, separately from the passing real suite.
Image build, Compose validation, fixture up/status and down all succeeded.
Authenticated Frontier returned READY, the SOCKS handshake returned READY,
and packaged CLI/Storm probes completed. This proves startup/readiness/shutdown,
not a crawl: no worker research job or real Tor request was launched. Shutdown
did not request volume deletion; persisted-data survival after container recreation
was not asserted. The previous failed run remains part of the evidence.

## Original-pass verification (before the continuation above)

Local commands ran with cached Temurin 25.0.5+8, Maven 3.10.0 and Python 3.12.
Kotlin 2.3.21 actually compiled at JVM target 25; no versions were changed.

| Gate | Result / evidence |
| --- | --- |
| Initial `verify.py --offline` in sandbox | FAIL recorded, not erased: Kotlin daemon and gRPC loopback denied; Windows/POSIX tooling failures; `.runtime/verification/run-0fjtym9f/result.json` |
| Root Maven `verify` with permitted loopback | BUILD SUCCESS; 253 tests, 0 failures/errors, **3 skipped** (POSIX/readability/symlink capability cases); `.runtime/astra-pass1/maven-host.log`. Not an all-tests acceptance pass |
| Frontier input preparation | Exact original SHA256 validated; filtered dependency entries checked byte-for-byte; `.runtime/astra-pass1/frontier-prepare.log` |
| Frontier first offline Maven attempt | BLOCKED: prepared file-repository artifact not yet resolved into Maven cache; preserved `frontier-maven-offline.log` |
| Frontier Maven `verify` after resolution | PASS: compile, all **7 real conformance suites**, overlay package; `frontier-maven-resolve.log`, `frontier-server/target/maven-conformance-7573300950520228686/` |
| Python Frontier entrypoint with permitted loopback | PASS: **7 real conformance suites**, fresh disposable DBs; `.runtime/astra-pass1/frontier-m03wtex5/result.json` |
| Verification-runner regressions | PASS: 7 tests including deliberate failure propagation; `.runtime/astra-pass1/verification-tests.log` |
| Runtime token preparation | Before: 2 errors with the reproduced TypeError; after: 2 tests PASS. Real files, exclusive-open race and token reuse checked; Windows permission rejection retained. `runtime-token-before.log` / `runtime-token-after.log`; Linux CI also passed mode enforcement |
| Existing Python groups | M3 2/2, archive 2/2, security 4/4 pass; M1 32 selected/1 skipped; CLI 12 skipped. M2: 113 selected, 2 failures/7 errors/2 skipped; scripts: 32 selected, 2 failures/28 errors. See initial result JSON; no platform failures relabelled as success |
| CLI entrypoint with resolved Maven classpath | PASS: `Onionall --help`, exit 0; `.runtime/astra-pass1/cli-classpath-help.log`. Direct root shaded-JAR probe lacked provided Storm Config; not the packaged CLI contract |
| Offline CLI persistence smoke | PASS: depth-2 plan -> approve -> show -> pages across separate JVMs and fresh H2; `.runtime/astra-pass1/cli-plan-pwbwvrmz/result.json`. Approval persists; admission remains UNOBSERVED and archived pages are empty, as no fetch was run |
| Local canonical Docker images/up/status/down | BLOCKED: Docker/WSL prerequisite absent; no real container/runtime acceptance inferred from native JVM tests |
| Linux portable CI on `584d7d0` | PASS: root/crawler Maven (253 tests each, 0 skips), 7 real Frontier suites, 36 tooling tests, paired images, Compose config, authenticated Frontier/SOCKS and packaged CLI readiness, shutdown. [Run 42523305221](https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/42523305221); no depth-2 crawl or data-retention readback |
| Real-network/depth-2 acquisition | NOT RUN: no new approved seed or operational Tor runtime; no provider availability or persisted research-data claim |

Reproducible native diagnostic commands, using an explicitly selected JDK25 and
Maven on PATH (`CACHE` is a checkout-local dependency cache):

```sh
mvn -B -o -Dmaven.repo.local="$CACHE" verify
java frontier-server/build-support/FrontierBuild.java prepare "$URLFRONTIER_BASE_JAR" frontier-server
mvn -B -Dmaven.repo.local="$CACHE" -f frontier-server/pom.xml verify
python -B -m unittest discover -s test/scripts -p test_verification.py -v
python -B scripts/runners/verify.py --links-only
```

The first Frontier resolution needs access to its prepared file repository and
uncached Maven plugins; later offline builds require that completed cache. Raw
evidence remains ignored. Native JDK execution does not replace the canonical
container workflow documented in [local runtime](../local-runtime.md).

## Blockers and next executable action

1. Canonical depth-2 fetch, archive/graph readback and persistence after actual
   container recreation passed in CI 42628616540. Repeat that gate through the
   new Kotlin research commands before claiming their end-to-end acceptance.
   The local Windows host still lacks Docker/WSL prerequisites.
2. Keep Windows Python portability failures distinct: missing POSIX shell paths,
   symlink/mode semantics, `os.getuid`, and sandbox MSYS startup restrictions. Do
   not weaken assertions or install OS components to produce a green summary.
3. An Ahmia seed is approved; real Tor remains blocked on private bridge/runtime
   prerequisites and automated provider access restrictions. Do not substitute
   direct networking. No actual real Tor fetch/provider result is claimed.

Tracked changes were checked with `git diff --check`, local Markdown-link
verification and scoped secret-signature inspection. All 18 owner untracked files
still match their preflight SHA256 values and remain outside commits. Publication
uses only `origin/feature/astra-onionall-reconstruction`; no force push is used.
The original e35bc85 checkpoint was documentation-only; continuation source/test
changes require their own actual runs as recorded above.

Full Pass 1, S1-01 security closure and real Tor acceptance remain open. No PR,
merge, main/source branch change or deployment is part of this delivery.
