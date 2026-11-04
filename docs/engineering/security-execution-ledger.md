# Security execution ledger

Append-only execution history. Main findings and architecture:
[SECURITY-REVIEW.md](../security/SECURITY-REVIEW.md).

## 2026-11-04 — Stage1 discovery and baseline readiness

### Scope, identity and changes

- Continued Stage0; did not recreate branch or clone again.
- Root `C:\AI\SPETAN\onionall`; branch `security/comprehensive-hardening`.
- Initial/final source HEAD `853934e57a17b827487b023e8e2c295375290b65`.
- `git status --short --branch`, `git log -3 --oneline`, `git remote -v` checked;
  clean initial tree; origin is the requested GitHub mirror. No fetch in Stage1.
- Existing root/scripts AGENTS.md read; no CLAUDE.md found in tracked inventory.
- Three independent read-only agents researched admission/storage, archive/process,
  transport/networks. Primary agent reviewed core RPC/ledger/status-write evidence.
- No source/config/AGENTS/dependency edits, installation, commit or push.
  Only two approved documentation files added. Temporary evidence/helpers are
  ignored under `.runtime/security-stage1/`; `.runtime/m3/` parent prepared for an
  existing test. Neither folder existed initially; no user evidence replaced.

### Tool discovery

| Tool/input | Current observation | Consequence |
| --- | --- | --- |
| Java / JAVA_HOME | Temurin21.0.13+9 at `C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot`; java/javac/jar present. JAVA_HOME selects it. | JDK25 build BLOCKED; no downgrade/override of java.version. |
| JDK25 | Not found in checked standard, user and local toolchain paths; historical `.runtime/platform-migration/jdk25/jdk-25.0.5+8` absent. | Historical Linux JVM successes cannot be reproduced from this checkout alone. |
| Maven / cache | No mvn in PATH, no JetBrains bundled installation at documented location; no user `.m2`, repo `.runtime/maven-linux-repository`, wrapper or `.mvn` tracked. | Offline JVM dependency closure absent. |
| Docker | CLI absent from PATH and checked Program Files/user Docker locations. | Docker engine not queried; container E2E and compose config BLOCKED. Not whole audit blocker. |
| Python | 3.12.10, `%USERPROFILE%\AppData\Local\Programs\Python\Python312\python.exe`; PyYAML and pytest importable. | Safe offline unittest/source parsing available. |
| Git Bash | `C:\Program Files\Git\bin\bash.exe`, GNU bash5.3.15. Not in PATH; sandbox MSYS startup failed NtCreateDirectoryObject 0xC0000022. Same installed binary works with elevated tool execution. | Syntax available. Native Windows Python POSIX fixture suites still not equivalent to Linux. |
| WSL | wsl.exe exists; `wsl --list --quiet` reports WSL not installed. | No available Linux execution context established; nothing installed. |
| Scanners | Gitleaks, OSV Scanner, Trivy, Semgrep, Ruff not found in PATH/checked tool roots. | Full secret/dependency/SAST/lint scanning NOT RUN; limited fallback described below. |
| Frontier baseline | Required stock2.4 service JAR and frontier-server/target absent. | Hash-pinned server build/conformance BLOCKED independently of root Maven. |

Discovery used Get-Command, selected environment variables only, Test-Path,
Get-ChildItem of Program Files/Program Files(x86), exact real user Local Programs,
JetBrains, `.jdks`, `.gradle/jdks`, `.sdkman/candidates`, Scoop, Chocolatey,
`C:\tools`, `C:\AI\tools`, `.tools`, toolchains, project runtime/target,
and filename-only rg in user `.cache`/`.local`, Adoptium/Oracle/Common roots.
No registry, system PATH, host network or Docker setting changed. No full-disk
exhaustive absence claim: tools are absent from the checked locations.
Sandbox LOCALAPPDATA pointed to an isolated package path, so exact actual user
LocalAppData installation paths were checked separately.

### Build route once separately approved prerequisites are available

`pom.xml` requires Java25; `scripts/cli/build-cli.sh` verifies javac>=25 and calls
`mvn -B verify dependency:build-classpath -Dmdep.outputFile=target/cli-classpath.txt`.
ONIONALL_OFFLINE=1 adds -o, using `.runtime/maven-linux-repository` if present.
The packaging script is Bash/Linux oriented (colon-separated classpath handling).
No native Windows packaged CLI equivalence was established.

Windows root JVM build can use a separately approved portable JDK25 and Maven
without persistent environment changes (PowerShell example, NOT EXECUTED):

```powershell
$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:PATH
try {
    $env:JAVA_HOME = '<verified-JDK25-directory>'
    $env:PATH = "$env:JAVA_HOME\bin;$previousPath"
    & '<verified-Maven-directory>\bin\mvn.cmd' -version
    & '<verified-Maven-directory>\bin\mvn.cmd' -o -B '-Dmaven.repo.local=.runtime/maven-repository' verify
    if ($LASTEXITCODE -ne 0) { throw 'JVM baseline failed' }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:PATH = $previousPath
}
```

This offline command requires an already complete verified dependency cache; none
was found. Initial online dependency acquisition would need an explicit separate
approved step. `scripts/cli/build-project.ps1` accepts -JdkHome/-Maven/-Repository/
-Offline and restores environment, but runs clean package, not full verify. Prefer
direct verify for baseline and preserve any later target artifacts.

Candidate server documented command, also NOT EXECUTED:

```sh
JAVA_HOME=<JDK25> URLFRONTIER_BASE_JAR=<pinned-service.jar> bash frontier-server/build.sh --test
```

Required input SHA256:
`ff1b774bf81343234e6f34878f8b7c9a826371b3e305e96bf0e204895e3562df`.
Build source targets release11 under selected JDK25. Script uses Linux classpath
separators; run in a validated Linux environment or establish a separate reviewed
native invocation. Do not silently claim Git Bash plus native JDK is equivalent.
Server tests create unique disposable DBs; no migration of existing user volume.

Minimal acquisition proposal, no download/install performed:

- Windows x64 **JDK25 (JDK, not JRE)** portable ZIP from official
  [Eclipse Temurin releases](https://adoptium.net/temurin/releases/?version=25&os=windows&arch=x64&package=jdk).
  Historical patch was25.0.5+8; select and verify official build/signature/hash at
  acquisition. JDK21 is not an acceptable substitute.
- Official Apache **Maven3.9.x** binary ZIP (3.9.11 is an available concrete
  candidate, not a POM-enforced or already-tested version):
  [Apache release catalog](https://release-catalog.apache.org/maven/3.9.11/index.html).
  Verify publisher signature/checksum per
  [Apache distribution verification](https://www.apache.org/dyn/closer.cgi/maven/).
- Put approved portable artifacts only in project-local ignored tools/runtime;
  use temporary process environment, no global installation. Docker/WSL are not
  prerequisites for continuing source/JVM research and are not proposed now.

Official source pages checked 2026-11-04; no current CVE or latest-version claim.

### Baseline commands, chronological attempts and results

All Python commands ran from repository root with Python3.12.10 and `-B`.
Raw logs are `.runtime/security-stage1/<name>`; exit code shown is test process
code, not the surrounding PowerShell command's final print success.

| Command / attempt | Raw outcome | Interpretation / log |
| --- | --- | --- |
| `python -B -m unittest discover -s test/m2 -v` | exit1; 113 tests, 3 failures, 8 errors, 2 skips; 100 passed | **FAILED** full native run. `python-m2.log`. Errors: 3 localhost socket permissions, 4 hardcoded /bin/bash, 1 fake-JDK executable lookup. Failures: 2 POSIX0600 assertions report0666 on Windows, 1 fake-JDK mismatch regex. Two existing platform skips. |
| `$env:M1_HTTP_SMOKE='1'; python -B -m unittest discover -s test/m1 -v` in sandbox | exit1; 32 tests, 1 error, 31 passed | **FAILED** initial attempt, localhost WinError10013. `python-m1.log`. |
| `python -B -m unittest discover -s test/m3 -v` before parent preparation | exit1; 2 tests, 1 error, 1 passed | **FAILED** initial setup: test requires existing `.runtime/m3`. `python-m3.log`. |
| `python -B -m unittest discover -s test/archive -v` | exit0; 2/2 pass | **PASSED** controlled fixture behavior only. `python-archive.log`. |
| `python -B -m unittest discover -s test/security -v` | exit0; 4/4 pass | **PASSED** mocked signature/provenance guards, not real candidate acquisition. `python-security.log`. |
| `python -B -m unittest discover -s test/cli -v` | exit0; 12 discovered, all12 skipped | **BLOCKED**, not CLI PASS. Bundle absent; POSIX PTY unsupported. `python-cli.log`. |
| `python -B -m unittest discover -s test/scripts -v` | exit1; 8 top-level tests, 18 error events including subtests; 2 pass | **FAILED** full run: bash not on native PATH / POSIX harness. Python help and PowerShell source-contract tests pass. `python-scripts.log`. |
| `New-Item -ItemType Directory -Force .runtime/m3` then same M3 command | exit0; 2/2 pass | **PASSED**, no source/test modification. `python-m3-prepared.log`. Initial failed log retained. |
| Same M1 smoke command with authorized tool execution outside socket sandbox | exit0; 32/32 pass | **PASSED**, owned localhost HTTP only; no external request. `python-m1-loopback-authorized.log`. |
| `Push-Location test/m2; python -B -m unittest test_recovery_socks_fixture -v; Pop-Location` outside socket sandbox | exit0; 3/3 pass | **PASSED** owned loopback SOCKS cancellation/target/robots fixture. `python-m2-loopback-authorized.log`. This repairs evidence for 3 original errors, not full-suite PASS. |

Distinct M2 tests with pass evidence: 103/113 across initial run and targeted
loopback rerun. Eight remaining failing/error tests are platform/harness blocked;
two skipped. Keep original full-suite FAILED result. No tests were disabled,
assertions weakened, mocks rewritten or platform behavior emulated to force PASS.

The command environment was temporary; no system environment persisted. Controlled
fixtures do not replace JVM transport, M1 Docker, real Tor or M2/M3 acceptance.

### Static validation, lint and limited secret checks

- Bash5.3.15: `git ls-files '*.sh'` then absolute Git Bash `-n <file>` for every
  returned path. **15 files, 0 failures**; syntax only, no script execution.
  Sandbox first version probe failed MSYS permission; elevated version/syntax
  probes succeeded without installation or settings changes.
- PowerShell parser: `git ls-files '*.ps1'`, then
  `[System.Management.Automation.Language.Parser]::ParseFile(...)` on each.
  **5 files, 0 parse failures**; no operational script executed.
- `python -B .runtime/security-stage1/static_checks.py`: stdlib ast/ElementTree and
  PyYAML.safe_load on tracked source: **58 Python, 2 XML, 6 YAML/Flux, 0 errors**.
  `static-checks.json` records results. No external formatter/lint/SAST pass claimed.
- Compose interpolation/semantic `docker compose config`: **BLOCKED**, no Docker.
  YAML syntax parsing is not an equivalent check.
- Limited offline signature scanner used `git rev-list --objects --all` and
  `git cat-file --batch`: **62 reachable commits, 602 unique blobs, 5,772,364 bytes**.
  Patterns: private-key headers, classic/fine-grained GitHub tokens, AWS access
  IDs, HTTP credential URLs. Six matches were credential-URL negative fixtures;
  values were redacted before display and never saved to evidence.
- Triage: `DiscoveryReportTest.java` blob e7b67e0 / change0998050;
  `OnionallTest.java` blobs49ce32a/69bbf54 / changes0998050,7f7932e;
  `DiscoveryTest.java` blob58dd20f / change0a3148d;
  `TorPolicyTest.java` blobs340a59a/e01fc00 / changes3f121b7,9b5938d.
  All assert invalid userinfo handling; no operational secret established.
  Full OIDs/types/paths/change commits in ignored static-checks.json.
- Limits: not Gitleaks, no entropy/generic-password completeness, no unreachable
  objects, no user ignored secret files, no compressed-archive content scanning.
  No secret-absence guarantee. Full dependency/CVE/container scanning **NOT RUN**.

### Preliminary research and verification priorities

No confirmed vulnerability, fix or security commit. Review contains stable S1 IDs:
S1-01 unauthenticated reserved-URL status mutation STRONG/HIGH conditional;
S1-02 shared capability DESIGN GAP; S1-03 plaintext isolation dependency DESIGN
GAP; S1-04 supervisor loss HYPOTHESIS; S1-05 directory durability DESIGN GAP;
S1-06 backlog HYPOTHESIS; S1-07 container budgets DESIGN GAP; S1-08 local path
race and S1-09 SOCKS identity HYPOTHESES. Preconditions and exact source locations
are in the main review. Do not convert these into confirmed P0/P1 without evidence.

Stage2: agree trust model, recover approved build prerequisites, unchanged JVM
baseline/server conformance, then disposable S1-01 reproduction. Stage3: independent
read-only review, capability matrix, supervisor loss, parser/stream limits,
artifact/dependency advisories; consolidate before proposing fixes.

Stage1 completion means architecture/source boundaries reconstructed, available
baseline executed, failures retained/classified and next steps specified. It does
not require unavailable Docker and does not grant remediation/commit/push permission.

### Final checkpoint verification

- Independent read-only review found no blocking S1-01/ownership/baseline issues.
  DNS wording narrowed to destination-host rejection: SOCKS endpoint DNS resolution
  remains intentional before numeric pinning. No universal DNS-isolation PASS.
- `git diff --check`: PASS. Tracked application/config/AGENTS/scripts diff empty;
  cached diff empty; branch/source HEAD unchanged. New docs were additionally
  checked with `git diff --no-index --check -- NUL <file>`: exit1 reflected new-file
  differences, with no whitespace error; core.autocrlf emitted LF/CRLF notices.
- Both authored Markdown files parsed as UTF8; relative links exist, no trailing
  whitespace or matches of the limited secret signatures. No broad lint claim.
- Evidence hashes: `.runtime/security-stage1/evidence-sha256.json` covers ten
  retained test logs and static-checks.json (11 files). Failures remain retained.
- Final untracked inventory also includes `docs/audits/non-security/README.md`,
  which appeared concurrently after the initial clean state. This mission did not
  create/read/change/stage it. Preserve it as unrelated work. Authored additions
  remain only the two approved security documents; no files staged.
- Final status: **Stage1 PASS, audit PARTIALLY VERIFIED; JVM/server/container and
  remaining POSIX checks BLOCKED, full M2 native command FAILED, real Tor NOT RUN**.
  Stop here for user review. No remediation, AGENTS edits, commit or push.

## 2026-11-04 — Stage2/3 build recovery, persisted RPC proof and design checkpoint

### Authorization and preservation

User authorized portable verified build recovery and isolated verification, then
requested completion on persisted legacy RPC state, fail-closed design and Windows
CrawlWorkerTest diagnosis. Implementation remains forbidden. No tracked application
source, existing test, POM, Compose, AGENTS.md, DeepSeek document or Git ref changed.
Branch/HEAD remain `security/comprehensive-hardening` /
`853934e57a17b827487b023e8e2c295375290b65`. Existing untracked DeepSeek audit remains
unrelated. Only the existing security review and this ledger were edited.

Temporary downloads/tools/cache/fixture drivers/DBs/logs are project-local and
ignored under `.runtime/security-stage23/`; ordinary Maven compilation output is
under ignored `target/`. Scratch Java drivers are test infrastructure evidence,
not changes to repository source/test suites. No real crawler/Tor, user DB/volume,
host networking, system installation, commit, push or merge.

### Verified environment recovery

- Official [Adoptium API](https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse)
  returned Windows x64 Temurin25.0.5+8. Downloaded ZIP141,285,173bytes; SHA256
  `636bdc13dc649ddc7d5921c5c7d23cca6e44fd19d662b45cc9c02135a6c96c42` matches API.
  Detached GPG signature valid under official published key fingerprint
  `3B04D753C9050D9A5D343F39843C48A565F8F04B`; java.exe Authenticode Valid,
  Eclipse Foundation. Version actually executed25.0.5+8.
- [Official Apache download page](https://maven.apache.org/download.cgi) supplied
  Maven3.10.0 portable ZIP and SHA512/signature. SHA512:
  `22d31676d5b92ed53308c19ecded3245d0efd0cddfec51ec7bab62f4f13ad70ddddceff8e62940e0e8e2421f3cde8106737f4becec0aa034ffeff2a754e88337`.
  Valid signature fingerprint `88BE34F94BDB2B5357044E2E3A387D43964143E3`, key from
  official Apache Maven KEYS. Separate project GPG homedir; no user keyring edited.
  GPG trust is undefined in the fresh keyring; publisher-key provenance is HTTPS,
  not a claimed independent Web-of-Trust certification.
- Tools extracted without elevation into `tools/jdk-25.0.5+8` and
  `tools/apache-maven-3.10.0` under evidence root. JAVA_HOME/PATH set only in each
  build process. Sandbox elevation was for downloads/MSYS/owned sockets, not an
  administrator installer or host-setting change.
- Maven Central `urlfrontier-service-2.4.jar` SHA256
  `333be15af15b4faea282d4c7f75f1e737877f67a15063cc7e0669685fa49d0ca` differs from
  repository pin. Retained as rejected input; not used for compile/runtime.
- Recovered exact jar without running any container: official Docker Hub repository
  crawlercommons/url-frontier, project-pinned manifest digest
  `sha256:000f2c942778718f7f6554cb4b10b004df0eaad794f5fe46b3689239024b2095` verified;
  fetched/verified layer
  `sha256:c505e286c9b31a11d5c5ea66f2567c3d330a9480c3647000b6d7a235344c09d5`.
  Copied only regular member `home/urlfrontier/urlfrontier-service.jar`,92,015,772
  bytes; exact SHA256
  `ff1b774bf81343234e6f34878f8b7c9a826371b3e305e96bf0e204895e3562df`.
  Manifest/layer and `frontier-recovery.json` retained; registry bearer token only
  in memory, never logged. No arbitrary tar extraction or helper-container startup.
- Standalone Compose5.7.0 verified against official docker/compose GitHub release
  API digest AND checksums.txt:
  `f5dc759702aec5c0602e247aa861d14853216e3f481c4eaaf83621a035ecf92f`.
  Initial checksum filename parsing missed the published '*' prefix; corrected
  only local verification command, then both digest checks passed before execution.
- Official Docker29.6.2 static Windows ZIP downloaded; extracted docker.exe is
  unsigned, no separate publisher checksum established. It was **not executed**,
  installed as a service or used as an engine. Docker Engine/Linux runtime remains
  **BLOCKED**. Service manager inspection denied by sandbox; no absence inferred
  from denied service access. WSL unavailable in Stage1 and not installed here.
- Completing container acceptance requires a separately approved host setup.
  [Official Docker Windows instructions](https://docs.docker.com/desktop/setup/install/windows-install/)
  describe Docker Desktop per-user install and a working Linux backend; WSL setup
  may enable Windows features/install a kernel and require administrator/restart.
  No installer, WSL enablement, service creation or host networking performed.

### Actual build / baseline results

Root command (temporary JAVA_HOME selects the portable JDK25, Maven bin called by
absolute path):

```powershell
& '<root>/.runtime/security-stage23/tools/apache-maven-3.10.0/bin/mvn.cmd' `
  -B -C '-Dmaven.repo.local=.runtime/security-stage23/maven-repository' `
  verify dependency:build-classpath '-Dmdep.outputFile=target/cli-classpath.txt'
```

Initial dependencies acquired from Maven Central with strict checksum checking into
that local cache. **FAIL**, test-process/build exit1:118 tests,1 failure,1 error,
1 skipped,115 pass. `maven-baseline-online.log`; original Surefire reports retained
under target/surefire-reports. Java/Kotlin main/test compilation succeeded. Failure
stops verify before packaging/classpath goal; no full build/packaged PASS claimed.

Classpath goal separately executed with same JDK/Maven/cache and -B -C:
`dependency:build-classpath -Dmdep.outputFile=target/cli-classpath.txt`: **PASS**,
`maven-classpath.log`. It does not substitute for failed verify. Cache is recovered,
but no successful full offline verify or native packaged CLI claimed.

Native Frontier equivalent of build.sh, preserving sources and release11:

```powershell
& '<jdk>/bin/javac.exe' --release 11 -cp '<pinned-jar>' -d '<frontier/classes>' <16-source-paths>
& '<jdk>/bin/javac.exe' --release 11 -cp '<classes>;<pinned-jar>' -d '<frontier/tests>' <5-test-source-paths>
& '<jdk>/bin/java.exe' --enable-native-access=ALL-UNNAMED -cp '<classes>;<tests>;<pinned-jar>' `
  crawlercommons.urlfrontier.service.rocksdb.<ConformanceClass> '<fresh-db-root>'
```

Native ';' classpath intentionally replaces Bash/Linux ':' in command invocation,
not repository code. Migration conformance additionally receives pinned baseline
jar as second argument. First PowerShell launcher omitted parentheses around class
name concatenation: four class-not-found errors, **tests not executed**; original
logs retained. Corrected command uses a separate `$main` variable and fresh DBs:

| Current test | Result / evidence |
| --- | --- |
| AdmissionConformance | **PASS**, exit0:64-way quota, replay/reopen, prewrite fault, scope/source/extension/seal, actual halt before/after sync-WAL-before-ACK. `frontier-AdmissionConformance-run2.log`. |
| AdmissionMigrationConformance | **PASS**, exit0: fresh disposable legacy-copy fixture only, explicit upgrade/stock rollback refusal/compatible readonly protection. `frontier-AdmissionMigrationConformance-run2.log`. No persistent schema implementation or user migration changed. |
| AdmissionShutdownConformance | **PASS**, exit0: active transaction drain, late-command rejection, reopen/replay. `frontier-AdmissionShutdownConformance-run2.log`. |
| AdmissionRpcConformance | **PASS**, exit0: missing-token Command rejection, valid read, admin denial, legacy read. `frontier-AdmissionRpcConformance-run2.log`. |
| Compose config | **PASS** for m0/m1/m2, standalone verified Compose `-f compose.<mode>.yaml config --format json`; M1/M2 evidence path variables point to this runtime directory. `compose-m0.json`/m1/m2. No engine/container/network assertion. |
| Packaged CLI / Docker E2E / real Tor | **BLOCKED / NOT RUN**. Verify failed before package, no engine, no external Tor authorized. |
| Python suites | Stage1 results retained; not rerun or relabelled as new Stage2/3 PASS in this focused completion. |

### Persisted legacy RPC verification

Scratch drivers compile against actual candidate classes and exact pinned service
jar; no mocked admission, RPC or RocksDB. Commands use the Frontier classpath above:

```text
javac --release 11 -cp <cp> -d <tests> .runtime/security-stage23/LegacyRpcProbe.java
java --enable-native-access=ALL-UNNAMED -cp <cp> crawlercommons.urlfrontier.service.rocksdb.LegacyRpcProbe .runtime/security-stage23/rpc-reproduction
javac --release 11 -cp <cp> -d <tests> .runtime/security-stage23/PersistedRpcProbe.java
```

Actual raw-client probe **PASS** (exit0): tokenless schedule/metadata/terminal
updates ACK OK; metadata/state read back changed, quota/reservation unchanged.
Terminal removal survives close/reopen. Raw GetURLs takes1 lease and next worker
gets0. Protected unreserved Known/Discovered fail; raw unprotected-crawl enqueue
OK, session absent. Valid shared worker token opens/seals another session. No
URL was fetched. `rpc-reproduction/result.json` and `rpc-reproduction.log`.

Stronger restart proof: execute the following independently for each phase, waiting
for process exit before starting the next. These are five actual Java PIDs:

```text
java --enable-native-access=ALL-UNNAMED -cp <cp> crawlercommons.urlfrontier.service.rocksdb.PersistedRpcProbe .runtime/security-stage23/rpc-process-restart <phase>
```

| Phase | PID | Result |
| --- | --- | --- |
| reschedule |59072| **PASS**: authorized reservation, anonymous fetch-time+metadata ACK OK; persist expected reservation/nextFetch snapshot. |
| read-rescheduled |44080| **PASS**: changed nextFetch1793778883 and forged metadata present after JVM restart; zero currently fetchable rows. |
| terminal |58100| **PASS**: anonymous terminal nextFetch0 ACK OK. |
| read-terminal |59828| **PASS**: terminal0 and no fetchable rows after next JVM restart. |
| replay |31500| **PASS**: ALREADY_RESERVED with original reservation_id, quota1, unchanged terminal; protected new Known/Discovered FAIL; missing-token Command denied. |

Each phase asserts exact original reservation JSON and reserved_count1. Evidence:
`rpc-process-restart/<phase>.json`, logs `rpc-process-<phase>.log`. The surrounding
PowerShell output-print expression initially had a path concatenation error;
Java phases all exited0 and JSON was subsequently read independently. No test
phase rerun was needed or failed state hidden. Graceful process restart is proven;
power-loss/status sync-WAL guarantee is not.

Final negative security gate (scratch, existing tests untouched):

```text
java --enable-native-access=ALL-UNNAMED -cp <cp> crawlercommons.urlfrontier.service.rocksdb.LegacyRpcProbe .runtime/security-stage23/rpc-negative-final --assert-secure
```

**FAIL/RED as expected**, exit1: expected PERMISSION_DENIED plus unchanged
fetch-time/reservation/quota, got ACK OK and changed state.
`rpc-negative-final/result.json`, `rpc-negative-final.log`. Earlier exploratory
negative logs retained separately. This is a reproduced missing protection, not
a waived regression or successful security assertion.

`EndpointAuthorizationProbe` on another fresh DB: **PASS** verification matrix.
Missing/wrong token Command -> PERMISSION_DENIED; valid token legacy status OK;
wrong token legacy status ALSO OK. Six administrative mutation methods denied
with/without capability: DeleteCrawl/DeleteQueue/SetActive/SetDelay/BlockQueueUntil/
SetLogLevel. `endpoint-matrix/result.json`, `endpoint-matrix.log`.
No other allowed scheduler mutator identified beyond PutURLs and leasing GetURLs.

### Windows CrawlWorkerTest failure arbitration

- Original Surefire java.class.path21,474chars,168entries; nested worker+fixture
  launch carries it twice (>42,948chars before other args).
  `windows-classpath-diagnosis.json` records this vs32767 Windows command limit.
- Launched **unchanged** `CrawlWorkerTest$Initiator` with original21,781-character
  outer command; it exits1 at nested ProcessBuilder.start, CreateProcess206,
  `CrawlWorkerTest.java:25`; no readiness marker. Logs and structured outcome:
  `windows-initiator-long-cp/{initiator.log,result.json}`. This proves the first
  readiness failure is a launch-length error, not an observed deadline failure.
- Exact original Surefire dependency closure put into a scratch standard manifest
  Class-Path jar, with directory entries ending '/'. First manifest missed those
  slashes and failed class loading; `windows-worker-short-classpath.log` retained.
- Corrected launch (same source/class/closure/assertions/timeouts):
  `java --enable-native-access=ALL-UNNAMED -cp .runtime/security-stage23/test-classpath.jar org.junit.runner.JUnitCore dev.onionall.brawler.admission.CrawlWorkerTest`.
  **PASS2/2**, exit0,7.732s, `windows-worker-short-classpath-run2.log`.
  Initiator hard kill, living-owner rejection, child-owned deadline/lock, exit124,
  child termination and lock release asserted. No process-lifecycle fix applied.
- Maven's existing ArchiveStore symlink test explicitly assumes non-Windows
  (`ArchiveStoreTest.java:100-101`);1 SKIPPED retained. Do not count it PASS.
- Full ordinary Maven verify remains **FAIL**; targeted2/2 does not turn it green.
  Supervisor hard-kill/actual Storm TTL/overlap still **NOT RUN**, S1-04 HYPOTHESIS.

### Engineering conclusion and implementation boundary

S1-01 is **CONFIRMED/HIGH conditional**, P1 remediation; user's P0 investigation
priority is completed locally, not CRITICAL-severity attribution. Current trusted
legacy network contract deliberately permits unauthenticated status/lease clients;
confirmed impact assumes reachable compromised client, not remote onion-only access.
No protected quota/reservation corruption or protected unreserved enqueue proved.

Minimum proposal in review section13: reuse existing capability/header/constant-time
check for all PutURLs and GetURLs regardless of crawl ID; keep Command auth,
six admin denials and unknown-method default deny. Wire same capability into real
Spout lease and StatusUpdater streaming/reconnect paths first. Upstream channels
are private and ManagedChannelUtil has no configuration/auth hook; a concrete
source-pinned client adapter/patch needs design review. Token config alone is not
working integration. No second auth system/scheduler/schema or Tor change.

Authentication semantics change even with unchanged protobuf/schema; old tokenless
workers would break. **STOP for design review before implementation**. Coordinated
client/server rollout only after G1..G6: negative no-mutation/restart/lease tests,
valid worker ACK/retry/terminal/reconnect, admin denial, all four conformance checks,
controlled real-Storm/SOCKS/archive E2E. Rollback must retain the auth gate and
admission-aware schema-compatible binary; otherwise stop execution and preserve
data. No anonymous fallback, CF removal, stock rollback or reseeding.

Independent read-only review confirmed impact boundaries, need to protect all crawl
IDs and GetURLs, and prerequisite stock-client compatibility. Shared-token worker
authority, plaintext transport, unauthenticated reads, production reachability and
real-Storm supervisor loss remain residual gaps. DeepSeek audit arbitration is a
separate pending request, not performed or marked complete by this focused security
checkpoint. No DeepSeek document changed.

### Final validation / STOP

- `git diff --check` PASS; tracked source/test/script/config/AGENTS diff empty;
  cached diff empty; HEAD unchanged. DeepSeek audit files remain untracked and
  untouched by this mission. No new tracked-path report created.
- Both existing security documents: UTF8, trailing-whitespace, relative-link and
  limited secret-signature checks PASS. This is not full SAST/lint/secret assurance.
- Structured evidence cross-check asserts five distinct restart PIDs, all phases
  passed, unchanged reservation JSON and quota1, persisted forgery/terminal and
  stable replay. Negative security gate remains RED against current source.
- `.runtime/security-stage23/environment-artifacts.json` records tool/input hashes
  and verification disposition; `evidence-sha256.json` hashes retained logs,
  structured results, scratch drivers, original Surefire reports and final two
  documents. No ephemeral capability values stored.
- **STATUS: local legacy authorization verification complete; S1-01 CONFIRMED,
  UNFIXED. Full audit PARTIALLY VERIFIED. STOP before source changes.**
  NEXT is review of the authentication-contract/client compatibility design and
  regression gates G1..G6, then separate implementation authorization.

## 2026-11-04 — Approved S1-01 local implementation and regression checkpoint

User approved the section13 design for local implementation/testing and required
no commit/push/merge. Base HEAD remains853934e on security/comprehensive-hardening.
Initial worktree contained the two untracked security documents and unrelated
DeepSeek audit; those audit files remain untouched. No system installation,
external onion request, schema migration, destructive operation or host network
change. Only existing trusted build dependencies/sources fetched from Central.

### Implementation

- AdmissionRpc.guard now authenticates exact full Admission/Command, PutURLs and
  GetURLs for all crawl IDs with the existing token/header/constant-time comparison.
  Public reads require the real service prefix; admin/unknown methods remain denied.
- FrontierCapability uses standard CallCredentials, validates existing token file
  at initialization and rereads for each RPC/stream establishment. Sanitized local
  failure or server rejection cannot retry anonymously. No new authority/TTL system.
- Source-pinned CapabilitySpout/CapabilityStatusUpdaterBolt preserve the pinned
  StormCrawler3.7.0 logic; only class identity and credential setup/stub wiring
  differ. Source normalization exactly matches official source members SHA256
  031aa003910552f9327785cf378beb327f213f691dc40b688187c3d1864ecd91 and
  de66cdf9184c8283d9a944bee57bd681d1863237f64eb4caa9b30951811b2558.
  Original source JAR downloaded from official Maven Central, checksum verified;
  no dependency version change. Proof: `.runtime/security-remediation/upstream-provenance.json`.
- CapabilityArchiveStatusUpdaterBolt reuses existing outcome observation and then
  authenticated upstream storage. CrawlExecution selects authenticated spout/status
  only for the durable topology. Stock M0/M1 and local-test archive paths unchanged.
- Windows test ProcessBuilder commands now use nested test-owned Java argument
  files, original assertions/deadlines preserved. Added >40k classpath, spaced path
  and literal argument regression. Production process ownership code unchanged.
- Root AGENTS, Frontier README and existing M4.1 contract/deployment docs updated
  to prevent tokenless rollout. No new report or module AGENTS created.

### Tests / chronological failed attempts retained

Evidence root `.runtime/security-remediation/`; paths below relative to it.

1. Expanded server regression compiled against original guard: **RED**, anonymous
   mutation received ACK (`server/rpc-red.log`). Current guard then passes missing/
   wrong-token, multi-item stream/no ACK, no scheduling/metadata/reservation/quota
   change, unprotected-crawl rejection, no stolen lease, admin matrix and valid
   lease/retry/terminal/recreated channel/replay after separate-JVM restart.
   Final authoritative compile/run: `server/tests-lint-final3.log` and
   `server/AdmissionRpcConformance-final2.log` (**PASS**).
2. Initial server test expected immediate dequeue after a reschedule but ignored
   existing120s queue delay. New assertion corrected to persisted next-fetch date,
   without changing scheduler semantics. An intermediate cleanup IOException
   compile error is retained in tests-lint-final2.log; stale-class
   AdmissionRpcConformance-final.log is **NOT counted** as final verification.
3. Other real conformance drivers **PASS**: AdmissionConformance,
   AdmissionMigrationConformance, AdmissionShutdownConformance in server/*-green2.log.
   Fresh disposable DBs only. Current candidate packaged via release11 javac and
   exact pinned jar; output `frontier-server/target/urlfrontier-admission.jar`,
   SHA2566bbae7272fb35ab417e156a73fb21a3921bc9fc78f2129cb48341c81d7ad9b40.
4. Ordinary offline application compile initially **FAIL**: sandbox denied Kotlin
   daemon localhost registry connection (`application-compile.log`). Identical
   authorized offline compile **PASS** (`application-compile-authorized.log`).
   No sandbox workaround or Kotlin source change.
5. Client integration initial setup omitted required urlbuffer.class, then a fifth
   test assumed instant restart always enters TRANSIENT_FAILURE. Failed logs
   retained (`client-tests/junit.log`, junit-final.log and first root verify).
   Fixture corrected to hold its own server down and observe actual channel
   TRANSIENT_FAILURE before restart. Test-only reflection READS state; never sets
   fields/forces callback. Authentication/ACK assertions retained. **PASS5/5** in
   client-tests/junit-deterministic.log and final normal Maven run.
6. First full offline verify **FAIL124**, one new reconnect fixture failure
   (`maven-verify.log`). After fixture correction, next offline verify passed all
  124 tests but packaging **BLOCKED/FAIL** on missing jar-plugin dependencies
   (`maven-verify-run2.log`). These failures remain in history.
7. Official Central filled the project-local cache using strict checksums; full
   final verify **PASS**,124 run,0 failures,0 errors,1 existing Windows symlink
   skip (`maven-verify-complete-cache.log`, jvm-summary.json). Exact command:

```powershell
$env:JAVA_HOME = '<root>/.runtime/security-stage23/tools/jdk-25.0.5+8'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
& '<root>/.runtime/security-stage23/tools/apache-maven-3.10.0/bin/mvn.cmd' `
  -B -C '-Dmaven.repo.local=.runtime/security-stage23/maven-repository' verify
```

Environment changes are confined to this child shell. No POM/version/assertion
change and no skipTests flag. New5 real component tests exercise actual Spout and
StatusUpdater initial connection, wrong/deleted/rotated token, recreated channels,
real reconnect, normal ACK, retry date, terminal0, and no anonymous stream handler.
Windows worker3/3 pass under normal Surefire. Existing35 TorProtocol wire tests,
policy/cache/scope and archive/CLI JVM tests pass. Shaded application built; this
is not installed thin CLI or actual Storm-cluster acceptance.

Python commands (all `python -B -m unittest discover -s test/<suite> -v`):

| Suite | Current result / log |
| --- | --- |
| M1 with M1_HTTP_SMOKE=1 | **PASS32/32**, python-m1.log, owned localhost fixture only. |
| Archive / security / M3 | **PASS2/2,4/4,2/2**, python-archive/security/m3.log. |
| M2 native Windows | **FAIL113**,3 failures,5 errors,2 skipped,103 passed; python-m2.log. Failures remain POSIX0600 semantics/fake-JDK fixture; errors fake executable naming and four /bin/bash calls. No weakening/emulation to manufacture PASS. |
| Full local Storm/SOCKS / packaged CLI | **BLOCKED/NOT RUN**: no usable verified Linux Docker runtime. Component+wire fixtures do not count as complete G6; no system installation attempted. |

### Review, lint, compatibility and remaining gates

- Server guard/tests `javac -Xlint:all`: **PASS, no warnings**. New client classes
  -Xlint:all: **exit0 with14 warnings**, not warning-free: deprecated gRPC marker,
  dependency annotation and retained upstream/serializable field patterns.
  Logs in server/guard-lint.log, tests-lint-final3.log and lint/main.log. Existing
  Kotlin warnings and shade overlap warnings retained; no suppression/refactoring.
- Primary diff/provenance review finds only approved auth wiring and test launcher
  changes. AdmissionLedger, RocksDBService, QueueMetadata, H2 schema, TorProtocol,
  Compose and POM remain unchanged. Normalized adapter source matches official
  pinned source exactly, including existing scheduler/ACK/reconnect implementation.
- Independent reviewer launch **BLOCKED**: collaboration tool rejected it with
  "possible cybersecurity risk". No independent PASS claimed and no disguised
  retry. G6 and independent review remain closure/rollout gates.
- Authentication semantics changed without protobuf/schema change: tokenless
  workers cannot write/lease. Deploy reviewed compatible client/server pair;
  rollback only to schema-compatible pair retaining auth, or stop/preserve data.
- CallCredentials applies at RPC/stream establishment, not each PutURLs message.
  File removal blocks future calls/recreated streams, not an already-authorized
  stream. Existing static token has no TTL/rotation protocol. Shared worker/operator
  authority, public reads and plaintext RPC remain explicit separate risks.
- Upstream reconnect behavior outside an observed TRANSIENT_FAILURE (e.g. very fast
  restart) is not newly qualified or altered. No new application scheduler/retry.

### Exact worktree summary and STOP

`git diff --stat` relative to base HEAD: **8 tracked files,302 insertions,47 deletions**.
Additional new source/test files (untracked, not staged): **5 files,1021 lines**:
FrontierCapability53, CapabilityArchiveStatusUpdaterBolt30, CapabilitySpout256,
CapabilityStatusUpdaterBolt481, CapabilityClientsTest201. Two existing untracked
security documents are updated; unrelated docs/audits/non-security/** excluded.
Full per-path counts: `.runtime/security-remediation/git-diff-summary.json`.
No files staged; no commit, push, merge, tag or publication.

Final status: **LOCAL IMPLEMENTATION VERIFIED AT AVAILABLE JVM/COMPONENT/SERVER
GATES; S1-01 FIX IMPLEMENTED / PARTIALLY VERIFIED, NOT CLOSED**. Full audit remains
PARTIALLY VERIFIED. Stop after final diff/secret/artifact checks; no automatic
next-phase execution. Complete independent review and controlled G6 before closure.

Final checks: `git diff --check` PASS; no cached changes. Fifteen authored/modified
files pass UTF8, whitespace, limited secret-signature and local Markdown-link checks.
Shaded application contains FrontierCapability, CapabilitySpout,
CapabilityStatusUpdaterBolt and CapabilityArchiveStatusUpdaterBolt. Protected
admission/storage/Tor/Compose/POM diffs are empty. Final validation, exact diff
summary, artifact hashes and source/log hashes are saved under
`.runtime/security-remediation/{final-validation,git-diff-summary,evidence-sha256}.json`.

## 2026-11-04 — Security RC safe checkpoint

New user task authorizes reviewed local commits, never push/merge/PR. Existing
source branch and main remain unchanged. Full modified/new-file scope reviewed;
15 own code/test/doc paths, separate13-file DeepSeek audit protected by SHA256
manifest `.runtime/security-rc/deepseek-preservation-before.json` (not staged).
High-confidence secret signatures clean; no runtime/binaries/credentials included.
Local account installation paths in this document normalized to %USERPROFILE%.

Fresh quick gates: offline Maven selected CapabilityClientsTest,CrawlWorkerTest,
AdmissionClientTest: **12/12 PASS** (`checkpoint-jvm.log`). Fresh native Frontier
compile plus all4 real conformance drivers **PASS** (`frontier/*.log`). First RPC
launcher referred to nonexistent old scratch class directories and did not run a
test; `checkpoint-rpc.log` retained. Recompiled from current source into fresh RC
directories before authoritative conformance runs; no stale-class PASS counted.

Local checkpoints created after explicit path staging and cached diff checks:

- `9091f4ff5611fcb0878df2b884e73ad71a07e9b1` — security fix/client integration,
  six files +834/-7, no dependency/schema/Tor changes.
- `1f039dfbe70bed232bc79606d3908bbc6d5d9304` — regressions and Windows launcher,
  three files +439/-32. No existing assertion/deadline removal.

Documentation checkpoint follows; subsequent RC analysis will append evidence,
triage, roadmap and handoff in these same two reports. No automatic owner message.
