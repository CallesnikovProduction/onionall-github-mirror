# M0 verification record — 2026-10-30

**Decision: M0 accepted for the empty-frontier local runtime baseline.** The
user executed `./scripts/m0.ps1` on the Windows host. Both complete log files
were inspected for lifecycle events, warnings, errors and exceptions. The
agent did not execute a second runtime run: direct Docker API access remains
denied. Acceptance is based on the host run's logs and the user-reported
Compose teardown, together with the previously verified build.

## Environment and outcome

Windows 11 amd64. JDK selected explicitly: Oracle 17.0.12. Maven: 3.9.16 bundled with IntelliJ IDEA 2026.2.3. Pre-existing JAVA_HOME points to JDK 21; it was not changed persistently. Maven is absent from this session's PATH.

| Check | Observed result |
| --- | --- |
| JDK / Maven version | Java 17.0.12 / Maven 3.9.16 |
| Online clean package, empty workspace cache | Failed resolving repo.maven.apache.org |
| Offline clean package, copied cache, pinned plugins | BUILD SUCCESS, 27.757 seconds |
| Build through scripts/build.ps1 | BUILD SUCCESS, 19.071 seconds |
| Tests | No test source files; no tests ran |
| Flux dry-run | Exit 0; one spout, nine bolts, 15 streams |
| Tika resource | Present in dependency/JAR; runtime instantiated Tika with that configuration and prepared the bolt |
| Compose config validation | Exit 0, Compose 5.3.1 |
| PowerShell helpers | Build helper verified by agent; M0 helper executed on user's host, with runtime evidence reviewed |
| Docker CLI | 29.6.2, desktop-linux context |
| Docker daemon | Initially absent, then access denied in agent session; accessible to user's host run |
| Agent retry during log review | Compose ps and image inspect still return permission denied; no agent rerun |
| docker desktop start | Failed: Docker Desktop registry key not found in this environment |
| wsl --status | Wsl/E_ACCESSDENIED |
| Container startup/shutdown and frontier RPCs | Confirmed in host-run service/console logs; 14 requests and 14 empty-queue responses |
| Runtime Java / topology | Eclipse Adoptium 17.0.20; Storm 2.8.9 LocalCluster; crawler activated |
| Exit statuses | Console log: crawler 0; frontier 143 after orchestrated stop and RocksDB close |
| Containers/network removed | User report; post-down output was not saved by the original helper, independent Docker inspection denied |
| Crawl, state recovery, Tika document parsing | Not attempted |

These observations do not prove Docker/WSL is broken on the unrestricted host. This session has restricted access. No hook, security or approval settings were changed.

After the user started Docker Desktop, `docker version` was retried. The client
identified the desktop-linux context but access to the engine pipe was denied.
That access limitation remains, but the subsequent host run provided the
runtime evidence below. Do not weaken pipe permissions or expose an
unauthenticated Docker TCP API to bypass the agent's boundary.

## Build details

An isolated copy of the existing host Maven cache was placed under `.runtime/m2/repository`. Repository-origin tracking files in that disposable copy were removed to permit offline use of artifacts downloaded under IDE-specific repository IDs. The original host cache was not modified.

Unpinned Maven defaults selected unavailable plugin versions. The POM now pins clean 3.2.0, resources 3.3.1, surefire 3.6.0 and jar 3.5.1. Compiler 3.11.0 and shade 3.5.0 remain unchanged. No crawler dependency was changed.

Successful command with IntelliJ Maven and the existing JDK 17 selected for the process:

```powershell
mvn -Dmaven.repo.local="$PWD/.runtime/m2/repository" -o -B clean package
```

Here `mvn` denotes the full path to IntelliJ's `mvn.cmd`, absent from PATH. The helper accepts that path through `-Maven` and discovers a bundled installation automatically.

Maven Shade reported overlapping upstream resources/classes. Packaging and the empty-frontier runtime succeeded; this does not validate document processing paths. A clean-cache online build and Linux host reproduction are not verified. Logs: `.runtime/build-offline-cached.log`, `.runtime/build-helper.log`, `.runtime/build.log`.

## Flux dry-run

Executed from the repository root with Java 17, the freshly built shaded JAR and provided Storm API JARs from the copied cache:

```powershell
$repo = "$PWD/.runtime/m2/repository"
$jar = 'target/onionall-brawler-0.1.0-SNAPSHOT.jar'
$client = "$repo/org/apache/storm/storm-client/2.8.9/storm-client-2.8.9.jar"
$shaded = "$repo/org/apache/storm/storm-shaded-deps/2.8.9/storm-shaded-deps-2.8.9.jar"
java -cp "$jar;$client;$shaded" org.apache.storm.flux.Flux --dry-run crawler.flux
```

Exit 0. Log: `.runtime/flux-dry-run.log`. Log4j reported no logging provider in this reduced validation classpath; the full Storm host run subsequently produced logging normally. Dry-run alone does not open/prepare components or prove connectivity. No seeds were injected and no remote sites were fetched in either baseline check.

## Host-run evidence and acceptance

`compose.m0.yaml` supplies two versioned images, temporary RocksDB storage, an
internal network, no published ports and a bounded Storm local run. The
reviewed topology/configuration are unchanged. There is no separate distributed
Storm deployment: Nimbus, supervisors and ZooKeeper in the log belong to
LocalCluster. No Tor, OpenSearch or persistent document store is configured.

| M0 acceptance criterion | Evidence and limit |
| --- | --- |
| Reproducible Java/Maven build | Fresh offline clean package, pinned plugins, documented JDK/Maven and helper; clean-cache online fetch not verified |
| Prerequisites and exact commands | README, scripts/build.ps1, scripts/m0.ps1 and Compose recipe |
| Required services start | RocksDB loaded and URLFrontier listening; LocalCluster started; service log lines 19–29 and 5–6 |
| Topology starts | Activation/submission at lines 585–586; all 9 application bolts prepared at 966, 975, 980, 1047, 1053, 1065, 1067, 1076, 1077; spout opened/activated at 1073–1075 |
| Tika configuration gap resolved | Dependency/JAR resource; runtime configuration load at 1048 and prepared Tika bolt at 1067; document parsing untested |
| Frontier connection works | 14 matching request/empty-response pairs between log clock 15:53:04.748 and 15:53:30.336; no queues, no URLs |
| Runtime stops | All application executors shut down at 1609–1647; worker dead at 1688; in-process ZooKeeper shutdown complete at 1735; console crawler exit 0 |
| Frontier stops | RocksDB closed and server shutdown at service lines 83–88; console frontier exit 143 after Compose stop |
| Environment teardown | User reports temporary containers and network removed; helper invokes down in finally; independent confirmation unavailable |
| Configuration/README gaps | Compose supplied, seed requirement removed for M0, Tika reference resolved; remaining limits documented |

The service log is not globally sorted by timestamp: Compose interleaves
containers' output. Line references identify evidence, not cross-service event
order. Container log clock 15:52:49–15:53:34 corresponds to 18:52:49–18:53:34
Moscow time on 2026-10-30 (the topology epoch timestamp provides UTC context).

Log identity (raw files intentionally excluded from Git):

| File | Lines / bytes | SHA-256 |
| --- | --- | --- |
| .runtime/m0-services.log | 1735 / 338000 | B61E265F1D8DAB18BF681E1E74FF627A88D705BE1AFA815417AB739AA9502CE5 |
| .runtime/m0-console.log | 338879 bytes | 09887B7F648AD1E65BFBF475A4DE0FFD9979B854EBA696B2D085E959E858F437 |

Raw logs include runtime-generated ZooKeeper credential material and verbose
environment/configuration dumps. Commit only this summary and log hashes,
not raw logs. No credential values are reproduced here.

## Warning assessment

The full service log contains **0 ERROR/FATAL records and 34 WARN records**.
The declared `throws java.lang.Exception` in Flux's printed method signature
is not a thrown exception. No exception stack trace was found.

| Warning group | Count | Assessment |
| --- | --- | --- |
| ZooKeeper maxCnxns unset, default 0 | 1 | Default connection-limit setting in the isolated local runtime; no startup failure. Production limits are outside this baseline. |
| CGroupMetricsBase CPU/memory metrics disabled | 6 | Those cgroup-derived metrics are unavailable in this runtime. Queue/fetcher/spout metrics are logged, but CPU/memory monitoring is not established. |
| ReadClusterState waiting for SLOT_1024 | 27 | Waiting rose from 1001 to 3606 ms, then worker was dead and slot empty. Teardown completed; this is not a stuck shutdown. |

ZooKeeper's INFO-level SASL message says authentication is not attempted;
successful local sessions follow. This is an unauthenticated isolated local
baseline, not evidence of a secured deployment. No warning requires changing
the working topology to accept M0. Frontier 143 follows the deliberate SIGTERM
stop and resource-close messages, rather than an observed service crash.

Non-blocking follow-ups: capture resolved image digests, preserve post-down
inspection output on the next run, reproduce on Linux and a clean online Maven
cache. The helper currently captures console output from up and service logs
before down, so teardown removal is not in those files. Versioned image tags
can move; this record does not claim digest-level reproduction.

## M1 scope and blockers

M1 remains open. Existing components initialized successfully, but no URL tuple
traversed the fetching/parsing/status/indexing pipeline. Current metrics show
zero work; they do not prove crawl outcomes or enforced rate limits.

- Add a small repository-owned permitted test site with known pages/links and
  robots policy, isolated from public/onion resources. No fixture exists yet.
- Define test-only target/URL policy and conservative fetching overrides; the
  baseline's 50 fetcher threads are not validated crawl limits. Existing host
  and private-address filters must be assessed against the chosen fixture.
- Inject controlled seeds and assert expected HTTP requests, extracted links,
  deduplication, status updates and stdout documents. None is demonstrated.
- Create a separate persistent RocksDB volume/configuration. M0's tmpfs is
  intentionally ephemeral; restart recovery is not implemented or checked.
- Restart the service and assert queued/completed/scheduled state survives;
  record backend location, volume lifecycle and non-destructive cleanup.
- Measure queue counts, fetch outcomes/errors and request spacing under load;
  provide CPU/memory observation independently of missing cgroup metrics.

Concrete execution blocker: the agent's Docker API access remains denied;
runtime checks require host execution or an appropriately provisioned execution
environment. No M1 target, persistent setup or verification harness exists yet.
Tor transport/discovery is M2/M3, a search index is M4, and distributed scaling
is M8; none is made ready by this local baseline.

## Review checks and commit status

On review, Compose config validation and git diff --check passed. The proposed
file list was checked for private-key and common access-token patterns with no
matches. This is a targeted hygiene check, not an exhaustive security audit.
No .runtime/ or target/ files are tracked; both logs and the built JAR match
ignore rules. The unrelated local .codex/config.toml is outside the commit scope.

The requested commit is `test(infra): verify stormcrawler m0 baseline`.
Staging the explicit M0 file list failed because this session cannot create
`.git/index.lock` (Permission denied). No commit was created and no history
was rewritten. The source/documentation changes remain in the working tree.
