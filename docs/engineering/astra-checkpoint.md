# Astra engineering checkpoint

Updated: 2026-11-09 (Europe/Moscow). Pass 1: operational baseline, PARTIAL.
Branch: `feature/astra-onionall-reconstruction`.
Base: `4b0d4daa9a56aafc9303cd7d34972bf126b1288a` (`origin/main` at preflight).
This base includes Security RC/S1-01, M5 and the owner's portable runtime changes;
`af171e78d9863e7c1ad2e946c7574d596229ada8` is its ancestor. The delivered checkpoint
SHA is obtained from `git log -1` for this file; no self-referencing hash is stored.

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
No new research orchestration/provider/authentication subsystem is claimed.

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

Published source checkpoints: `8f17d2f` (complete conformance selection) and
`2e1df11` (capability creation). The fresh
[Astra CI run 42522560099](https://github.com/CallesnikovProduction/onionall-github-mirror/actions/runs/42522560099)
on `2e1df11` built all images, passed container JVM/Frontier/tooling tests and
Compose syntax checks. Startup then exposed an independent existing YAML defect:
unquoted commas in the flow-list tmpfs entry created five mount paths, and Docker
rejected `exec` as nonabsolute. Quoting the single mount preserves rw/exec/nosuid/
nodev/mode=1777. A Compose CLI regression checks normalized mounts for all three
worker-derived services; it needs no Docker Engine. Local YAML parsing reproduced
the five-element input and confirms one corrected element. Windows without Docker
skips this Compose regression explicitly; the next Linux run must execute it.

## Fresh verification

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
| Runtime token preparation | Before: 2 errors with the reproduced TypeError; after: 2 tests PASS. Real files, exclusive-open race and token reuse checked; Windows permission rejection retained. `runtime-token-before.log` / `runtime-token-after.log`; Linux mode enforcement still requires the Linux run |
| Existing Python groups | M3 2/2, archive 2/2, security 4/4 pass; M1 32 selected/1 skipped; CLI 12 skipped. M2: 113 selected, 2 failures/7 errors/2 skipped; scripts: 32 selected, 2 failures/28 errors. See initial result JSON; no platform failures relabelled as success |
| CLI entrypoint with resolved Maven classpath | PASS: `Onionall --help`, exit 0; `.runtime/astra-pass1/cli-classpath-help.log`. Direct root shaded-JAR probe lacked provided Storm Config; not the packaged CLI contract |
| Offline CLI persistence smoke | PASS: depth-2 plan -> approve -> show -> pages across separate JVMs and fresh H2; `.runtime/astra-pass1/cli-plan-pwbwvrmz/result.json`. Approval persists; admission remains UNOBSERVED and archived pages are empty, as no fetch was run |
| Local canonical Docker images/up/status/down | BLOCKED: Docker/WSL prerequisite absent; no real container/runtime acceptance inferred from native JVM tests |
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

1. Observe the portable CI run on the published Astra SHA; investigate its first
   actual failure before expanding architecture. Image build, fixture readiness
   and retained data must be observed; a pushed workflow is not success evidence.
2. On an available Linux/WSL2 Docker host, run `./onionall build --images`,
   `./onionall test`, then controlled `--fixture up/status`, approved fixture crawl
   and archive/link readback, followed by data-preserving `--fixture down`.
3. Keep Windows Python portability failures distinct: missing POSIX shell paths,
   symlink/mode semantics, `os.getuid`, and sandbox MSYS startup restrictions. Do
   not weaken assertions or install OS components to produce a green summary.
4. Only after operational local workflow verification, use one explicitly approved
   real seed for bounded depth-2 research. Provider integration, binary acquisition,
   authentication and GUI remain later scope, not requirements for this checkpoint.

Full Pass 1, S1-01 security closure and real Tor acceptance remain open. No PR,
merge, main/source branch change or deployment is part of this delivery.
