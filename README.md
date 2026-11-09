# Onionall Brawler

A JVM-first foundation for an independent search engine over publicly discoverable onion services that permit indexing. Planned capabilities include seed discovery, recurring crawling, keyword search, source metadata and service relationships. The core search system must work without AI.

Tor v3 has no global registry. Measure coverage of known, permitted sources rather than claiming complete enumeration.


## Development without installing Java

Prerequisites: Git, Docker Engine/Desktop with Compose, and Bash with standard
Unix utilities (Linux or WSL2). No host Java, Maven, Python or JAVA_HOME is needed.
The pinned runtime baseline targets **linux/amd64**; ARM hosts need Docker's
amd64 emulation. Native ARM runtime is not qualified.

```sh
./onionall build --images   # first build: application, custom Frontier, Storm and Tor
./onionall build            # incremental JVM build; persistent Maven cache
./onionall build --clean    # clean generated build outputs only
./onionall test             # JVM tests, Frontier conformance, script contracts
export ONIONALL_BRIDGES_FILE=/absolute/path/to/private-bridges.conf
./onionall up
./onionall status
./onionall down             # retains volumes and archive
```

Build/test run in the Temurin JDK 25 toolchain container using the checksum-pinned
Maven Wrapper. Both root and crawler POMs are verified sequentially; Frontier's
independent Maven project uses its exact SHA256-checked upstream dependency.
The baseline is extracted automatically from the pinned upstream image; only
the custom Admission server is launched. Storm packaging reuses the existing
Nimbus patches and lock/drain probes in a multi-stage build. Runtime images do
not include the build toolchain. No registry login or image publication required.

Maven dependencies persist in `.runtime/maven-linux-repository/`, wrapper files
in `.runtime/toolchain-home/`; build outputs retain the invoking UID/GID.
Use `ONIONALL_OFFLINE=1 ./onionall build` only after dependencies and image layers
are cached (image builds may still require registry access).

Runtime data: `.runtime/local/` holds the private Admission token, H2 archive
catalog, HTML `archive/objects/`, execution configs/logs. Named volumes
`onionall-runtime_frontier-data` and `onionall-runtime_tor-state` retain Frontier
and Tor identity. Fixture mode uses separate `.runtime/local-fixture/` and
`onionall-runtime-fixture_frontier-data`. Clean/build/down never remove these.

Troubleshooting: check `docker info` if the engine is unavailable; use
`./onionall build --images` for missing runtime images. `up` requires a private
[obfs4 bridge file](docker/m2/bridges.example.conf) and waits for Tor bootstrap;
`status` checks authenticated Frontier, Tor/SOCKS, packaged CLI and Storm.
The lifecycle controller uses the local Unix Docker socket with the caller's
UID/groups; remote TCP Docker contexts are unsupported. On Linux, set DOCKER_HOST
to the local Unix socket if Docker uses a non-default context/rootless engine.
Keep the checkout on the Docker host so bind mounts resolve to the same path.
No real Tor connection is needed for build/test or ordinary PR checks.
See [runtime details](docs/local-runtime.md) for approval and crawl commands.

## Canonical local runtime (M4.2-LITE)

Use `./onionall` for product operation. It builds the existing crawler and custom
admission-aware Frontier independently. Requires Bash, Docker Engine and Compose. Internal services have no published ports.

```sh
./onionall build --images
export ONIONALL_BRIDGES_FILE=/absolute/path/to/private-bridges.conf
./onionall up
./onionall status

# Replace with your explicitly approved, permitted onion seed.
SEED_URL='YOUR_EXPLICITLY_APPROVED_ONION_URL'
PLAN_ID=$(./onionall cli crawl plan --seed "$SEED_URL" --scope same-host --depth 2 --max-pages 20 | python3 -c 'import json,sys; print(json.load(sys.stdin)["plan_id"])')
./onionall cli crawl show "$PLAN_ID"
./onionall cli crawl approve "$PLAN_ID"
./onionall cli crawl start --plan "$PLAN_ID"
./onionall cli crawl status "$PLAN_ID"
./onionall cli pages --job "$PLAN_ID"
./onionall cli crawl errors "$PLAN_ID"
./onionall down
```

Start is foreground and bounded to 180 seconds; restarting the same approved
plan resumes durable reservations. Worker exit zero is not proof of completion.
The existing single-host graph fetches sequentially: effective concurrency 1,
within global ceiling 4 and per-host ceiling 1. H2, HTML objects and execution
data live in `.runtime/local/`; RocksDB and Tor identity use retained named
volumes. [Runtime guide, controlled fixture, readiness, storage and current
verification limits](docs/local-runtime.md). Historical recipes below are separate.

## Historical milestone status

Bootstrap stage, based on Apache StormCrawler 3.7.0. The repository contains a Flux topology and filters, not an implemented onion search engine. Output goes to `StdOutIndexer`; there is no searchable index or application API.

Verified on Windows 11 on 2026-10-30:

- JDK 17.0.12 / Maven 3.9.16: `clean package` succeeded using an offline copy of the existing dependency cache. No project tests existed at the M0 build; the standalone M1 harness below was added afterwards.
- Flux `--dry-run crawler.flux` succeeded: one spout, nine bolts, 15 streams. This constructs the graph without opening/preparing components.
- The shaded JAR contains the dependency-supplied `tika-config.xml`.
- `docker compose -f compose.m0.yaml config --quiet` succeeded.
- The user ran `scripts/runners/run-startup-check.ps1` on the host (now `scripts/runners/run-startup-check.ps1`). Reviewed service and console logs confirm all nine bolts prepared, the spout opened/activated, Tika initialized, 14 empty-frontier RPCs completed and LocalCluster shut down with crawler exit 0. URLFrontier closed RocksDB on SIGTERM (exit 143). The user reports Compose removed the containers and network.

**M0 is complete for the empty-frontier local baseline.** The agent still cannot access Docker Engine directly; runtime evidence comes from the user's host run and the reviewed logs, not an agent rerun. Container cleanup is user-reported; image digests were not captured. See [the verification record and warning assessment](docs/SUMMARIZIED-M0-M1-REPORT.md).

**Tor transport is not configured in the default M0/M1 topology.** Do not inject onion URLs or begin a real crawl yet. DNS, redirects and fail-closed verification remain M2 work. Existing URL filters alone are not a complete network boundary.

Current migration branch uses **Java25 LTS / Storm3.1.0**, retaining
StormCrawler3.7.0. Real local M3 passes; Tor acceptance and durable global budgets
remain BLOCKED. Earlier Java17 results below are historical. See the
[migration report](docs/SUMMARIZIED-MIGRATION-AND-CLEANLINESS-REPORT.md) for exact gates and rollback.

Recovery recheck (2026-11-02): **M2/M3 remain BLOCKED**. Targeted gateway
restart restores the canary and JVM series, but resumed Storm robots still times
out before HTTP. A fresh M1 regression preserves/reuses six records but fails
the unchanged no-ERROR gate on Nimbus blob submission/cleanup. Cached M1 runs
are available via `M1_CACHED_IMAGES=1 bash scripts/runners/run-controlled-crawl.sh`; M2 `--cached-images`
propagates that policy and records deterministic child evidence and real exit
codes. See [recovery evidence and boundaries](docs/m2-hardening.md).

## Terminal CLI

The Java25 CLI exposes `fetch`, offline `inspect`/`discover`, and `status`; launching without
arguments opens a violet onion menu only in a real terminal. Pipes receive usage,
never a blocking prompt. `--json` emits JSON without branding/animation; `--no-color`,
`NO_COLOR` and non-TTY output disable colors. Branding uses ASCII, no special font.

Select a JDK25 with `JAVA_HOME` (and its bin on PATH for Maven), then:

```sh
# Uses Maven Central; prefix ONIONALL_OFFLINE=1 when the dependency cache is complete.
./scripts/cli/install-cli.sh
onionall --version
onionall --help
onionall status --json
onionall fetch http://juhanurmihxlp77nkq76byazcldy2hlmovfu2epvl5ankdibsot4csyd.onion/ --output ./ahmia.html
onionall inspect ./ahmia.html --json
onionall discover ./ahmia.html --base-url http://juhanurmihxlp77nkq76byazcldy2hlmovfu2epvl5ankdibsot4csyd.onion/ --json
# Optional explicit export; refuses to replace an existing file:
# onionall discover ./ahmia.html --base-url <approved-source-url> --export .runtime/discovery.jsonl
```

Offline discovery reads at most 4 MiB and processes at most 1,000 anchors. It uses
JSoup and TorPolicy, preserves Ahmia-only scope, reports excluded links and never
fetches or enqueues. JSONL export is a report, not a Frontier queue. No recursive
crawl is enabled. See [discovery contracts](docs/SUMMARIZIED-CLI-REPORT.md). Status remains
a TCP-only local check, with Tor/onion connectivity explicitly NOT_TESTED.

No Maven is used by the installed launcher. Default installation:
`~/.local/bin/onionall` → `~/.local/share/onionall/cli/bin/onionall`.
`XDG_DATA_HOME` and optional `XDG_BIN_HOME` select absolute installation paths.
The installer reports if its bin directory is absent from PATH; it never edits
shell configuration. Update by rerunning the installer. Remove with
`./scripts/cli/uninstall-cli.sh` using the same XDG variables; downloads/user-added
files are preserved. Unknown executables/install contents are never overwritten.

Repository-only usage: `./scripts/cli/build-cli.sh`, then
`target/onionall/bin/onionall --help`. The distribution copies an explicit dependency
allowlist, including necessary Storm API/StormCrawler classes; it excludes Flux,
Tika, gRPC, URLFrontier and test libraries. It does not start a topology or daemon.

Default downloads use `.runtime/downloads` when invoked from this checkout,
otherwise `$XDG_DATA_HOME/onionall/downloads` (default `~/.local/share/onionall/downloads`).
Override with `--download-dir` or `ONIONALL_DOWNLOAD_DIR`; `--output` selects an exact
file. Default filename is `ahmia.html`. Existing files require `--overwrite`.
Publication is atomic: same-directory hard link without overwrite, atomic rename
with overwrite. Filesystems lacking these operations fail safely. HTML is untrusted
data; no scripts, links, browsers or binaries are launched. Inspection is bounded
at4MiB and uses JSoup charset detection/replacement decoding, not strict UTF-8.
Link statistics count raw hrefs, including relative and protocol-relative links.

`fetch` calls the existing JVM TorProtocol. Only the approved Ahmia v3 hostname,
HTTP/HTTPS and standard ports are allowed; userinfo/ambiguous paths are refused.
SOCKS is fixed to local127.0.0.1, with `--socks-port` (default19050).
`--timeout-ms` bounds the entire request (default30000, maximum120000);
`--max-bytes` bounds HTML (default/maximum131072). No automatic redirects,
retries, clearnet or DIRECT fallback. Fetch is a single transport diagnostic,
not a robots-aware crawl or M2/M3 acceptance. No crawl/discover commands are exposed.

Status checks only local TCP reachability with a1500ms bound. It explicitly marks
Tor/onion availability NOT_TESTED. An open SOCKS port does not establish either.
Exit codes:0 success;1 unexpected failure;2 arguments/config;3 rejected URL;
4 transport (including timeout/body gate);5 unacceptable HTTP/content;
6 filesystem/output failure;130 usual Ctrl+C. Ctrl+C invokes protocol cleanup;
no completed output is published before a successful response. On monochrome
terminals progress is a plain wait message; animated elapsed time is TTY/color-only.

Troubleshooting: select Java25 if launcher exits69; build once before offline
installation; use a new output path if a download already exists. A SOCKS refusal
or Tor timeout is an error, not a reason to change host DNS/VPN or bypass transport.
Current full M2/M3 acceptance and durable global budget remain BLOCKED.

CLI verification: select Java25, run `mvn verify` or `scripts/cli/build-cli.sh`, then
`python3 -B -m unittest discover -s test/cli -p 'test*.py'` against the built bundle.
Actual delivery results and retained failures: [CLI verification](docs/SUMMARIZIED-CLI-REPORT.md).

Interactive ANSI terminals now keep a layered onion/gradient wordmark fixed above
a selective-update content region, with80/120/narrow layouts and resize reflow.
Enter returns from an action to the menu. NO_COLOR/--no-color retain plain output
without cursor controls (no physical pinning); JSON/direct commands are unchanged.
The surface mascot uses headless JDK java.desktop and ANSI half-blocks; custom
jlink runtimes must include java.desktop. `ONIONALL_ASCII=1` selects its density
fallback. The wordmark is unchanged. Renderer contracts and actual PTY captures: [interactive rendering](docs/SUMMARIZIED-CLI-REPORT.md).

## Build

See the [script map, platform support, inputs and resource ownership](scripts/README.md) for direct entrypoints organized by responsibility.

For the portable build use `./onionall build` above. The following native/IDE
workflow additionally requires JDK 25. The wrapper pins Maven 3.10.0; the root
POM has no modules. A clean machine needs Maven Central access. The native
crawler-only build does not require the Frontier service JAR:

```sh
./mvnw -B -f crawler/pom.xml verify
```

Linux / a shell with Java and Maven on PATH:

```sh
java -version
./mvnw -version
./onionall build
```

PowerShell:

```powershell
./scripts/cli/build-project.ps1 -JdkHome "$env:ProgramFiles/Java/jdk-25"
```

The helper builds only the crawler, preserving its previous dependency boundary.
It finds `mvn` on PATH or Maven bundled with IntelliJ under `$env:ProgramFiles/JetBrains`. For other installations, pass `-Maven` with the path to `mvn.cmd`. `-Repository <path>` selects a writable dependency cache; `-Offline` requires a complete cache. Environment changes are restored afterwards. Log: `.runtime/build.log`.

Output: `target/onionall-brawler-0.1.0-SNAPSHOT.jar`. Storm supplies the provided runtime classes; `java -jar` alone is not the launch command.

## M0: empty-frontier runtime baseline

Requires Docker Engine with Linux containers and Docker Compose. Start Docker Desktop on Windows first. The original host-verified recipe used `crawlercommons/url-frontier:2.4` and `storm:2.8.9-jre17`. Current Compose selects `storm:3.1.0-jre25` on the migration branch. Image tags are versioned but not locked by digest. The historical runtime was Eclipse Adoptium17.0.20; the current candidate image was checked as Temurin25.0.5+8.

```powershell
./scripts/runners/run-startup-check.ps1
```

The helper checks prerequisites, pulls images, runs the topology, captures `.runtime/m0-console.log` and `.runtime/m0-services.log`, and tears down the baseline containers. Linux wrapper: `bash scripts/runners/run-startup-check.sh`. Equivalent manual commands:

```sh
docker info
docker compose -f compose.m0.yaml config --quiet
docker compose -f compose.m0.yaml pull
docker compose -f compose.m0.yaml up --abort-on-container-exit --exit-code-from crawler
docker compose -f compose.m0.yaml logs --no-color
docker compose -f compose.m0.yaml down --timeout 60
```

Always run the final `down`, including after failure or interruption.

The recipe uses unchanged `crawler.flux` and `crawler-conf.yaml`. RocksDB lives in temporary tmpfs: no seeds, no reused records, and no persistence after the frontier container stops. Sharing the frontier network namespace preserves `localhost:7071`. No ports are published. The Compose network is internal; its outbound-isolation behavior has not been tested adversarially and it does not establish a Tor transport boundary.

The runner waits up to 60 seconds for the frontier TCP listener, then invokes `storm local ... --local-ttl 30`. TCP readiness does not prove gRPC readiness. Storm local mode needs no separate Nimbus, Supervisor, ZooKeeper or UI services.

When repeating M0, inspect logs for all components initializing, successful empty-frontier gRPC interactions and clean shutdown. A zero exit code alone is insufficient. Confirm no containers remain:

```sh
docker compose -f compose.m0.yaml ps -a
```

## Following milestones

M1 introduces a deliberately permitted test target, conservative limits and a persistent backend with a restart/recovery check. M2 establishes official Tor integration and fail-closed transport. M3 adds onion v3 validation/discovery; M4 adds a real search index.

M1 now has a separate controlled test harness: `compose.m1.yaml`, a synthetic site, a named RocksDB volume, gRPC snapshots, two bounded crawler runs and assertions for links, robots, HTTP outcomes, request spacing and graceful container-recreation recovery. **M1 passed full user-run Docker tests on Windows and Omarchy Linux on 2026-10-31.** Reviewed evidence confirms six records, robots denial, deduplication, HTTP 404 as FETCH_ERROR, resumed pending work and preserved state across graceful container recreation. Minimum observed document gap: 2.00265402 seconds on Windows and 2.003614258 seconds on Linux. Both crawler exits and cleanup passed; the synthetic RocksDB volume is retained. This does not prove crash recovery. Tor, OpenSearch and distributed Storm remain unprovisioned/unverified.

Linux / Omarchy (from the repository root, after building):

```sh
bash scripts/runners/run-controlled-crawl.sh
```

Requires Bash, Python 3.12+, JDK 25+ (`javac` and `jar`), and Docker Engine / Compose.
`JAVA_HOME` optionally selects the JDK; `PYTHON` optionally selects Python.
The runner uses the same assertions and evidence layout as Windows, retains the
RocksDB volume, and checks cleanup even on failure or interruption.

Linux checks on 2026-10-31: both Compose configurations validated; Maven offline
`clean verify` passed using a writable copy of the local cache (JDK 21.0.13,
Maven 3.10.0); 31 standalone Python tests passed, one optional HTTP smoke skipped.
Maven reports no Maven-managed tests. Full Linux Docker evidence from host run
`e55203e97536` was independently rechecked offline: assertions_passed=true,
crawl_evidence_passed=true, runtime_verified=true, six frontier records, persisted
state across recreation and resumed pending work. Both crawler exits and
container/network cleanup passed; the RocksDB volume is retained.

Windows PowerShell:

```powershell
./scripts/runners/run-controlled-crawl.ps1 -JdkHome "$env:ProgramFiles/Java/jdk-25"
```

The helper uses only hard-coded fixture URLs and saves evidence under `.runtime/m1/<project>/`. It removes its containers/network and retains its uniquely named synthetic volume for inspection. The M0 graph/configuration and original JAR are unchanged. See [M1 commands, checks, limits and retention](docs/SUMMARIZIED-M0-M1-REPORT.md). Local regression tests pass. Docker evidence comes from user host runs `df35366e0994` (Windows) and `e55203e97536` (Omarchy Linux), rechecked offline by the agent. The agent cannot directly access Docker Engine. No blocking log defects were found; unavailable cgroup metrics and resolved shutdown warnings are documented.

No `seeds.txt` is supplied or needed for M0. Future local seeds belong in ignored `seeds.local.txt`. Do not commit datasets or credentials. Define content handling, retention and deletion policy before persisting crawled documents.

Architecture, limitations and acceptance criteria: [AGENTS.md](AGENTS.md).

## M2: bridge-enabled Tor transport, acceptance blocked

The separate M2 stack now bundles Tor and obfs4 and mounts private bridge lines
read-only at runtime. Container bootstrap, SOCKS forwarding and a real HTTPS
Tor check pass. A real controlled onion crawl and graceful frontier persistence
also passed stages, but the complete security/outage/recovery gate has not yet
passed. **M2 remains BLOCKED.** Historical failed evidence is preserved.

On 2026-11-01 the bounded real Tor-only check passed, including a JDK17
InetAddress guard with zero target-onion resolver calls and denial of direct
egress/proxy fallback in the tested crawler namespace. The fresh full run passed
controlled crawling, frontier recreation and persisted Storm outage/retry, but
its failure matrix failed after the controlled onion daemon restart: bootstrap
and SOCKS were ready while onion health timed out for90s. Gateway restart later
restored health; the underlying cause is unconfirmed. This does not establish
system-wide absence of DNS leaks. Details and evidence are in the hardening doc.

See [private bridge setup and Linux/Windows commands](docs/m2-bridges.md),
[hardening audit results](docs/m2-hardening.md), and
[the transport decision](docs/adr/0001-tor-only-transport.md).
M0/M1 runners remain supported. Native Windows M2 execution is not yet verified.

## Upstream references

- [Storm 2.8.9 local mode](https://storm.apache.org/releases/2.8.9/Local-mode.html)
- [Storm Flux](https://storm.apache.org/releases/2.8.9/flux.html)
- [Official Storm image](https://hub.docker.com/_/storm)
- [URLFrontier service and RocksDB configuration](https://github.com/crawler-commons/url-frontier/blob/master/service/README.md)
- [URLFrontier image tags](https://hub.docker.com/r/crawlercommons/url-frontier/tags)

## Bounded local research workflow

The canonical launcher also exposes Kotlin `research plan --url URL`,
`research run --plan ID --approve`, and `research results|links|files ID`.
`research search --query TEXT [--job ID] [--host ONION_HOST]` searches archived
URL/title/text locally; keyword discovery through external providers remains separate.
Research plans are bounded to depth 2/20 reservations; file links are metadata
only. See [verified workflow and storage](docs/local-runtime.md#research-commands)
and the [current Astra checkpoint](docs/engineering/astra-checkpoint.md).
`config proxy show/set` updates the existing runtime descriptor; `system doctor`
checks SOCKS5 negotiation in the current network namespace, not real Tor HTTP.

Configured M4.1 supports approved plan → durable Frontier admission → bounded
Storm/TorProtocol crawl → archive → local search. The versioned server must run
on an isolated fresh volume; stock Frontier is not a fallback. See
[CLI/runtime setup](docs/m41-durable-admission.md),
[server contract and rollback](frontier-server/README.md), and
[executed verification](docs/engineering/m41-execution-ledger.md).
Real-Tor M2/M3 qualification remains separate from the controlled local fixture.

## M5 verification entry point

Run `python -B scripts/runners/verify.py --offline` with JDK25 and the pinned
Frontier base JAR. See [verification commands](scripts/README.md) and the
[M5 execution ledger](docs/engineering/m5-execution-ledger.md) for current
results, platform blockers and scope. Historical milestone results above are
not a current product acceptance result.
