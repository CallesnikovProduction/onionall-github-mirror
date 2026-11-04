# Script map

Use descriptive names below for new commands. Implementations live only in their responsibility directories. Root-level
wrappers and milestone aliases were removed; use the direct paths below.
`m0`/`m1`/`m2` identify evidence/acceptance milestones, not the command to remember.

## Layout

| Directory | Implementations |
|---|---|
| `cli/` | CLI packaging, installation, launcher and project build |
| `runners/` | Startup, controlled/Tor acceptance, fetch smoke, container helpers and volume initialization |
| `recovery/` | Gateway recovery and bounded runtime supervision |
| `security/` | Tor connectivity and artifact verification |
| `patches/` | Pinned Storm lifecycle patch build |

Only AGENTS.md and README.md remain as tracked root files. Compose mounts container
implementations directly. Tests stay under `test/`; there is no new test tree
inside operational tooling. `lib/` and `legacy/` are not created without content.

## Public commands

| Script | Platform | Purpose / inputs | Safe to rerun |
|---|---|---|---|
| `cli/build-cli.sh` | Linux/Bash | Verify and package CLI; no arguments | Yes; replaces managed `target/onionall` |
| `cli/install-cli.sh` | Linux/Bash | Build then install CLI; `--no-build` uses existing bundle | Yes for recognized managed installation |
| `cli/uninstall-cli.sh` | Linux/Bash | Remove managed CLI files; no arguments | Yes; preserves user-added files, archive and downloads |
| `cli/build-project.ps1` | Windows/PowerShell | Maven package; `-JdkHome`, `-Maven`, `-Repository`, `-Offline` | Yes; Maven clean replaces `target`, `.runtime/build.log` |
| `runners/verify.py` | Windows/Linux Python3.12+ | JVM/Python/Frontier/local-link verification; see below | Yes; unique evidence directories |
| `runners/run-startup-check.sh`, `runners/run-startup-check.ps1` | Linux / Windows | Empty-frontier startup (M0); no arguments | Sequential only; fixed project and overwritten `.runtime/m0-*.log` |
| `runners/run-controlled-crawl.sh`, `runners/run-controlled-crawl.ps1` | Linux / Windows | Controlled fixture + persistence assertions (M1); PS `-JdkHome`, `-Python` | Fresh project/evidence only; reuse refused; volume retained |
| `runners/run-tor-acceptance.sh`, `runners/run-tor-acceptance.ps1` | Linux / Windows | Full Tor acceptance (M2); `--cached-images`, PS `-CachedImages`, `-Python`, `-Help` and remaining runner arguments | Fresh project per run; real Tor and retained volumes |
| `security/check-tor-connectivity.sh`, `security/check-tor-connectivity.ps1` | Linux / Windows | Bounded real Tor probe; `--seconds N`, PS `-Seconds N`, `-Python` (default 180s) | Sequential only; fixed `.runtime/m2/connectivity-preflight` evidence |
| `runners/check-onion-fetch.sh` | Linux/Bash | Fixed permitted Ahmia fetch through existing Tor SOCKS; no arguments | Repeats network fetch; overwrites `.runtime/downloads/ahmia-*.log` |

Bash host commands can be called with `bash` without an executable bit, from any
working directory (use an absolute script path outside the checkout). PowerShell
uses `$PSScriptRoot`. Git attributes force LF for shell and PowerShell sources;
Compose also normalizes copied shell helpers. Arbitrary CRLF Bash files outside
Git checkout still need line-ending conversion. Linux tools use GNU utilities.
Bash build/install/uninstall/startup/controlled/fetch commands expose `--help`;
Tor wrappers pass help through to Python. Help does not start Docker or a build.

```sh
bash scripts/cli/build-cli.sh
bash scripts/cli/install-cli.sh --no-build
bash scripts/runners/run-startup-check.sh
M1_CACHED_IMAGES=1 bash scripts/runners/run-controlled-crawl.sh
bash scripts/security/check-tor-connectivity.sh --seconds 180
bash scripts/runners/run-tor-acceptance.sh --cached-images
```

```powershell
./scripts/cli/build-project.ps1 -JdkHome "$env:ProgramFiles/Java/jdk-25"
./scripts/runners/run-startup-check.ps1
./scripts/runners/run-controlled-crawl.ps1 -JdkHome "$env:ProgramFiles/Java/jdk-25"
./scripts/security/check-tor-connectivity.ps1 -Seconds 180
./scripts/runners/run-tor-acceptance.ps1 -CachedImages
```

These are usage examples, not an instruction to run all acceptance suites.

## Inputs, dependencies and effects

- Builds require JDK 25+ and Maven. `JAVA_HOME` selects Java. CLI build accepts
  `ONIONALL_OFFLINE=1` and reuses `.runtime/maven-linux-repository` when present.
  Install/uninstall use absolute `XDG_DATA_HOME` / `XDG_BIN_HOME` (defaults
  `$HOME/.local/share` / `$HOME/.local/bin`). Install calls `cli/build-cli.sh` unless
  `--no-build`; it installs `cli/cli-launcher.sh` and refuses unrelated destinations.
- M0 requires Docker/Compose and the prebuilt application JAR. It owns fixed
  Compose project `onionall-brawler-m0`, temporary Frontier storage, and two logs.
  Do not overlap runs or use that project for another workload. Exit zero still
  requires inspection of startup/RPC/shutdown logs before accepting M0.
- M1 additionally requires Python 3.12+ and JDK 25+. Bash selects `PYTHON`, Windows
  selects `-Python` (default `python`). `M1_PROJECT_NAME` optionally chooses a
  validated fresh project, `M1_CACHED_IMAGES=1` prevents pulls, `M1_STORM_IMAGE`
  selects the existing image override. Runners derive `M1_RUN_DIR` / `M1_RUN_REL`
  before Compose and retain `.runtime/m1/<project>/` and `<project>_frontier-data`.
  They call `test/m1/prepare.py`, probe/fixture helpers and `test/m1/verify.py`;
  only verified assertions AND cleanup produce `result.json`.
- M2 host wrappers call `runners/tor_acceptance_runner.py` (also directly runnable with
  Python, `--cached-images` / `--help`). It requires Python, Docker/Compose,
  JDK 25+, Maven, private bridges (`M2_BRIDGES_FILE`, default
  `.secrets/m2/bridges.conf`), and real Tor access. Optional settings are
  `M2_MAVEN_REPOSITORY`, `M2_STORM_IMAGE`, plus Maven's own environment settings.
  It calls `test/m2` harnesses and the M1 runner. It owns a fresh project, evidence
  `.runtime/m2/<project>/` and retains its persistent volumes. Cached images do
  not imply an offline Maven build or successful Tor acceptance.
- Connectivity wrappers call `test/m2/tor_preflight.py`, require Python,
  Docker, the existing Tor image and the same private bridge file; they perform
  a real network check. Bash accepts `PYTHON`. The runner sets `M2_RUNTIME_USER`
  for container permissions (preflight also accepts it). Fetch smoke requires
  Maven's cached dependencies, JDK and an already running local SOCKS proxy;
  it compiles and runs `OnionSmoke`, without provisioning Tor.

## Internal helpers and maintenance

| Script | Platform | Inputs / responsibility / dependencies | Safe to rerun / effects |
|---|---|---|---|
| `cli/cli-launcher.sh` | Linux/Bash, installed only | CLI arguments forwarded to Java; `JAVA_HOME`; reads installed `lib/*` | Depends on CLI command; launcher itself only starts Java |
| `runners/run-startup-container.sh` | Linux container/Bash | No arguments; `/workspace` JAR, Python TCP readiness, Storm | Compose-owned; temporary M0 state |
| `runners/run-controlled-crawl-container.sh` | Linux container/Bash | No arguments; `M1_RUN_REL`, Storm/JAR | Compose-owned bounded 45s crawl; run-specific evidence |
| `runners/run-tor-crawl-container.sh` | Linux container/Bash | No arguments; `M2_RUN_REL`, optional `M2_VERIFY_BEFORE_CRAWL`, `M2_SERIES_OUTPUT`, `M2_TOPOLOGY`, `M2_CRAWL_TTL`; Java/Storm | Compose-owned; optional verification before bounded crawl |
| `runners/frontier-volume-init.sh` | Linux container/POSIX sh | No arguments; image account `urlfrontier`, mounted `/crawldir` | Idempotent directory creation/ownership; never removes DB files |
| `recovery/recover_tor_gateway.py` | Python/Linux or Windows | `project run_dir`; existing scoped stack, private bridges; optional `M2_TOR_DIAGNOSTICS=1`; imports M2 harness | One recovery journal, not replayable; scoped gateway restart/quarantine; volumes retained |
| `recovery/supervise_local_crawl.py` | Python/Linux or Windows | `project run_dir`; existing stack/JAR; calls `recovery/recover_tor_gateway.py` and M2 readiness helpers | One supervision journal, not replayable; at most two batches and one recovery |
| `security/verify_tor_candidate.py` | Python + GnuPG | `bundle destination`; local artifact/signature/hash-chain verification, no environment settings | New destination only; offline evidence, no installation/update |
| `patches/build-storm-lifecycle.sh` | Linux/Bash | No arguments; JDK25, Docker, Python, patch/hash/tar tools, curl if source absent | Sequential build only; writes `.runtime/storm-lifecycle-build-cleanup<N>`, builds local image; no user volumes |

Container helpers deliberately use Compose's `/workspace` working directory:
they are copied into `/tmp`, so deriving checkout location from their own path
would be incorrect. They are not host entrypoints. Lifecycle build accepts
`JAVA_HOME` (required), `STORM_LIFECYCLE_REVISION`, `STORM_BASE`,
`STORM_LIFECYCLE_IMAGE`, `STORM_NIMBUS_SOURCE`; source/JAR hashes and patches remain
pinned. It owns one uniquely named, network-isolated temporary extraction container.

Cleanup is scoped to each runner's containers/networks. Persistent volumes,
evidence, secrets, Tor identities and host network configuration are retained.
Primary execution failures survive secondary cleanup failures; cleanup failure
alone fails the command. Nonzero exit means failure, never accepted recovery.
See [M1 evidence contract](../docs/SUMMARIZIED-M0-M1-REPORT.md) and
[M2 bridge setup and limits](../docs/m2-bridges.md).

## Direct paths

All commands use their directory paths: `scripts/cli/`, `scripts/runners/`,
`scripts/recovery/`, `scripts/security/` or `scripts/patches/`. Previous root-level
and milestone aliases have been removed. There is one executable body per script.
Repository callers and documented commands now refer to these direct paths.

## Verification and remaining boundaries

`python3 -B -m unittest discover -s test/scripts` checks installation and fake-tool
failure paths (spaces, another cwd, no executable bit, primary vs cleanup error,
M1 environment ordering and direct-entrypoint help). `test/m2` owns Python orchestration tests;
`test/m1` owns deterministic fixture assertions. These are not real Docker/Tor
acceptance. Windows source-contract checks do not replace PowerShell parsing or
native Windows execution. PowerShell and ShellCheck must be run where installed.

Remaining debt: operational M2 code still imports `test/m2` harness modules;
extracting that boundary is a separate refactor. M0/preflight logs use fixed paths
and need sequential runs. Bash and PowerShell M1 orchestration remain separate;
shared assertions/evidence contracts must continue to be checked on both hosts.

## Unified local verification (M5 DEV-01)

`python -B scripts/runners/verify.py --offline` runs Maven, independent Python
suites, six actual Frontier conformance drivers, and tracked Markdown file-link
checks. Set JAVA_HOME to JDK25 and URLFRONTIER_BASE_JAR to the verified stock2.4
service JAR (checksum enforced). Maven may be selected with --maven and the cache
with --maven-repository. Frontier verification compiles to a unique disposable
fixture directory; production assembly still belongs to frontier-server/build.sh.

Exit0 means every selected gate passed; exit1 means FAIL; exit2 means required
work was NOT RUN (including skipped tests). JSON and logs are retained under
.runtime/verification/run-*/. The full default result cannot be green while
product E2E is unexecuted. --links-only selects only the file-link gate (not
fragment validation); --product-e2e explicitly opts into the existing isolated
Linux Docker product fixture. Real Tor is out of scope and never started.
No dependencies, Docker images or runtime tools are installed by this runner.
Maven without --offline may resolve normal build dependencies. Existing Windows
POSIX permissions/Bash failures remain failures, never auto-skipped.

### Maven Wrapper / CI provenance

The source-only Apache Maven Wrapper3.3.4 pins Maven3.10.0 and its ZIP SHA256.
Use ./mvnw (Linux) or ./mvnw.cmd (Windows); JAVA_HOME must select JDK25. The first
wrapper use downloads the Maven distribution, even if Maven itself is passed -o;
subsequent offline use needs its existing cache. MAVEN_USER_HOME can isolate that
cache under .runtime. No wrapper JAR is committed. Official source/properties:
https://maven.apache.org/tools/wrapper/ and
https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper-distribution/3.3.4/ .

GitHub verify.yml uses SHA-pinned official Actions, read-only permissions and JDK25.
It extracts (never runs) the digest-pinned Frontier base image and verifies the JAR
checksum. It executes the same runner and retains failed evidence. It deliberately
fails closed when product E2E is NOT RUN: this workflow is not product acceptance.
The full Docker product fixture remains a separate opt-in gate until DEV-02 assembles
the paired app/server/Storm artifacts. No real Tor workflow is added.

Packaged CLI/PTY/installation tests are an explicit python-cli gate. If the Linux
CLI bundle is absent, its skipped tests produce NOT RUN (exit2), not PASS. Build
the package using scripts/cli/build-cli.sh before qualifying that gate on Linux.
A green Maven build alone neither packages nor qualifies the installed CLI.


## Paired M5 artifact assembly (Linux)

Select JDK25 and the checksum-verified URLFRONTIER_BASE_JAR, then run:

```sh
bash scripts/cli/build-cli.sh
bash frontier-server/build.sh --test
python -B scripts/runners/verify.py
```

CLI build uses the checked-in checksum-pinned Maven Wrapper, with no PATH Maven
fallback. ONIONALL_OFFLINE=1 still requires a complete dependency/wrapper cache.
Outputs are target/onionall-brawler-0.1.0-SNAPSHOT.jar, target/onionall/ and
frontier-server/target/urlfrontier-admission.jar. Clients and server must come from
the same checked source revision; tokenless old workers are incompatible.

M5 CI assembles this pair before running packaged CLI tests and records source
commit, artifact hashes and pinned Frontier-base hash in ignored paired-build.json.
Logs/provenance are retained even when a later gate fails. Assembly is not
Storm readiness or deployment acceptance. The patched Storm cleanup2 image and
internal SOCKS/generated-topology E2E remain separate requirements; real Tor is
out of scope. Linux build script is not a native Windows packaging claim.
