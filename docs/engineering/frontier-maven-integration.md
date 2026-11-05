# Frontier Maven integration

2026-11-04. Working checkout: `D:\GitHub\onionall-github-mirror`, origin
`git@github.com:CallesnikovProduction/onionall-github-mirror.git`.
No source was copied from the Entire checkout. No commit/push, Compose edits,
service deployment, existing database access or host-network changes occurred.

## Contract and structure

Outcome: import and compile the existing admission service as a Maven module,
retaining its runtime classes, dependencies and existing conformance tests.
Constraints: unchanged admission logic, schema, RPC/security policy and pinned
upstream artifact. Evidence: successful module verification, executable
conformance, identical Bash/Maven binary and observed IDEA import.

The former root JAR POM is now `crawler/pom.xml`, with only source/resource/build
paths adjusted. Original coordinates and checkout-root `target/` are preserved.
The root is an aggregation-only POM with `crawler` and `frontier-server` modules.
No server dependency is added to the crawler. Existing CLI/probe build commands
explicitly select `:onionall-brawler`; their dependency output files use absolute
checkout paths to preserve the earlier callers.

The server contains 16 production Java sources: the 14 vendored service files
and the added AdmissionLedger/AdmissionRpc. Existing test roots contain five
Java sources: four executable conformance suites and LegacyFixture. No upstream
source, generated API, application Java/Kotlin source or test was edited here.

Both server builders use JDK25 with `--release 11`, as the original Bash build
already did. This constrains language/API/bytecode compatibility, not the JVM
used to build/run tests. The crawler still targets Java25. Changing the server
release would be a separate compatibility decision.

`FrontierBuild.java prepare` checks the baseline hash before preparing an ignored
file Maven repository. It removes only replaced service classes; retained JAR
entries are checked byte for byte against the original. Maven also verifies the
actually resolved dependency. Packaging copies the original baseline and applies
the same class overlay and timestamp as Bash. No second upstream service class
set is present in the compile/test classpath. The packaged server is launched as
one fat JAR. The filtered dependency is optional to avoid downstream duplication.

## Exact input

Original image repository digest:
`sha256:000f2c942778718f7f6554cb4b10b004df0eaad794f5fe46b3689239024b2095`.
The service JAR was extracted from its registry layers without Docker Engine or
starting a container. Required and observed SHA256:
`ff1b774bf81343234e6f34878f8b7c9a826371b3e305e96bf0e204895e3562df`.
Local input: `.runtime/frontier-input/pinned-service.jar`.

The Maven Central artifact with nominal version2.4 had a different hash,
`333be15af15b4faea282d4c7f75f1e737877f67a15063cc7e0669685fa49d0ca`.
It was not used. An explicit negative preparation check rejects that file with
`Baseline service JAR checksum mismatch`.

## Verification

Environment: Windows11 amd64, JetBrains JBR25.0.4.1, Maven3.10.0, Git Bash,
Python3.12. Dependencies were cached in the checkout at
`.runtime/maven-repository`; the prepared dependency was also resolved into the
standard user Maven cache for the existing IDEA project. Network/loopback-capable
execution was needed for downloads and gRPC/socket tests. No restrictions were
disabled and no test assertion was weakened.

| Gate | Observed result |
| --- | --- |
| `mvn -B -o -f frontier-server/pom.xml -Dmaven.repo.local=<checkout-cache> verify` | PASS; 16 production and 5 test sources; all four conformance suites |
| `JAVA_HOME=<JDK25> URLFRONTIER_BASE_JAR=<pin> bash frontier-server/build.sh --test` | PASS; all four conformance suites, Git Bash on Windows |
| Bash/Maven comparison | PASS; identical complete JAR bytes and contents; 10314 entries, zero duplicate names |
| `mvn -B -o -Dmaven.repo.local=<checkout-cache> -DskipTests package` | PASS; both JAR modules and aggregate POM; packaging only, not a test pass |
| Full `mvn -B -Dmaven.repo.local=<checkout-cache> verify` | FAIL; crawler tests: 118 run, 1 failure, 1 error, 1 skipped; server skipped by reactor fail-fast |
| Original root POM, focused `CrawlWorkerTest` | Same 1 failure/1 error; Windows child JVM command exceeds CreateProcess limit (error206), readiness absent |
| IDEA2026.2.4 Maven reload | PASS; green sync; frontier-server module, pinned compile dependency, JDK25, language11, main/test roots observed in Project Structure; Java symbols recognized |
| Supplementary Python M2 suite | NOT GREEN on native Windows: 113 run, 3 failures, 8 errors, 2 skipped; failures include POSIX mode600, `/bin/bash`, POSIX JDK fixture names and restricted loopback |
| Source/Compose invariants and `git diff --check` | PASS; application and existing test sources, upstream hash file and Compose unchanged |

Both server artifacts have SHA256:
`269b06ffed8df4a9dd0189d08d0b2aa7a26e36c555af54606b35d91bd5081002`.
The only Bash changes normalize Git Bash paths/JAVA_HOME and use relative source
argfile entries; compilation, tests and overlay logic remain the same.

Ignored evidence:

- `.runtime/frontier-standalone-final.log`
- `.runtime/frontier-maven-verify-unrestricted.log`
- `frontier-server/target/maven-conformance-6528391298255459108/`
- `.runtime/frontier-shell-test.log`, `frontier-server/target/build.446miZ/`
- `.runtime/frontier-input/comparison.json`
- `.runtime/frontier-reject-wrong-pin.log`
- `.runtime/reactor-package.log`, `.runtime/reactor-verify-online.log`
- `.runtime/baseline-worker-test.log`, `.runtime/baseline-reports/`
- `.runtime/m2-python-tests.log`

Overall: server Maven integration and binary equivalence verified; full reactor
test acceptance remains incomplete on this host. IDEA import/index recognition
was observed; an independent IDEA JPS compilation was not run. No Docker/Tor or
M2/M3 acceptance claim follows from these checks. Reproduction and preparation:
[server README](../../frontier-server/README.md#maven-and-intellij-idea).
