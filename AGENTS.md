# Onionall Brawler — project context

## Purpose

Onionall Brawler is an open-source-oriented, JVM-first project to build an independent search engine for publicly discoverable and permitted-to-index Tor onion services. Its intended capabilities include seed-based discovery, recurring crawling, a searchable corpus, source metadata, a graph of relationships between services, and a research interface. Optional semantic search, summaries and AI-assisted research may come later; the core search system must work without AI.

Tor v3 has no global registry of onion services. Complete enumeration is not a realistic guarantee. Measure discovery and coverage of known, permitted sources instead of claiming comprehensive coverage.

## Evidence and current state

M1 is accepted on 2026-10-31 from the user-run Docker evidence `.runtime/m1/onionall-brawler-m1-df35366e0994/`, independently rechecked by the agent's offline verifier. All assertions, crawl evidence and both process exits pass; six frontier records persist across graceful recreation, pending work resumes, HTTP 404 is FETCH_ERROR, robots/deduplication/scope pass and minimum document gap is 2.00265402s. Both crawler logs contain zero ERROR/FATAL and 59 WARNs total: 12 unavailable cgroup metrics, two ZooKeeper defaults and 45 shutdown waits, all resolved. Backend logs have no WARN/ERROR. result.json records container removal and retained volume. Docker Engine was not rerun by the agent; evidence is host-run. M0 is unchanged. Tor, search/OpenSearch, crash recovery, distributed deployment and Tika document parsing are not verified. See docs/m1-controlled.md.

This is a bootstrap-stage project generated from the Apache StormCrawler 3.7.0 Maven archetype, not yet an onion search engine. The repository contains a Flux topology and configuration, not application-specific Java or Kotlin source code. The topology declares URLFrontier input and status updates, fetching, sitemap/feed and HTML parsing, optional Tika parsing, and `StdOutIndexer` output. On 2026-10-30, Java 17.0.12 / Maven 3.9.16 built the shaded JAR successfully offline; Flux dry-run constructed one spout, nine bolts and 15 streams. The user then ran scripts/m0.ps1 on the Windows host. Reviewed service/console logs confirm all nine bolts prepared (including Tika configuration loading), the spout opened/activated, 14 empty-frontier RPCs completed and LocalCluster shut down with crawler exit 0. URLFrontier closed RocksDB on orchestrated SIGTERM (143); Compose container/network removal is user-reported. M0 is accepted for this empty-frontier local baseline. Actual fetching, document parsing, status writes, durable recovery and search remain unverified. `StdOutIndexer` is not a searchable index. See `docs/m0-baseline.md` for evidence, hashes, warning assessment and limits.

There is no configured Tor transport or fail-closed guarantee, onion v3 validation or autonomous cross-service discovery, search API, desktop interface, graph storage or AI integration. A standalone M1 test harness now exists under test/m1, with Python verifier/fixture tests and Java gRPC/filter probes; it is not wired into Maven test and has not completed a Docker end-to-end run. Do not describe locally checked harness code as a verified crawl.

M0 is committed as `164c2ba`. M1 uses a separate internal Compose network, synthetic fixture, named RocksDB volume and generated Flux with the stock SimpleFetcherBolt. Test-only Java/Python checks remain outside Maven test.

The user reports that the initial Maven build succeeded, Entire is enabled, Codex hooks were approved, and checkpoints sync to `origin`. On the user's host `entire doctor` reported installed hooks and approval records, and `entire status` showed Codex. A sandboxed session may not be able to read `.codex` or Git internals, so its diagnostic can differ from the host. Check actual host status when relevant; do not modify hook approvals to work around sandbox restrictions.

## Repository map

- `pom.xml` — Maven project `dev.onionall.brawler:onionall-brawler:0.1.0-SNAPSHOT`, Java source/target 17 and shaded JAR configuration.
- `crawler.flux` — Storm Flux topology, component parallelism and stream wiring; loads StormCrawler defaults and `crawler-conf.yaml`.
- `crawler-conf.yaml` — fetcher, URLFrontier, parser, scheduling and metrics settings.
- `src/main/resources/` — URL, regex, parse and JSoup filter configuration. No application source files are currently present.
- `README.md` - project status, build and empty-frontier runtime instructions.
- `compose.m0.yaml` - host-verified two-container empty-frontier M0 runtime; ephemeral RocksDB, internal network, no published ports.
- `scripts/build.ps1` - verified Windows build helper; supports JDK selection, IntelliJ Maven discovery and offline cache.
- `scripts/m0.ps1`, `scripts/m0-container.sh` - host-run baseline orchestration, readiness wait, bounded local run and cleanup; agent reviewed logs, not an agent runtime rerun.
- `docs/m0-baseline.md` - 2026-10-30 M0 acceptance evidence, warning assessment, limits and M1 checklist.
- `compose.m1.yaml`, `scripts/m1.ps1`, `scripts/m1-container.sh`, `scripts/frontier-volume-init.sh` - controlled M1 orchestration; host verified; M1 accepted after volume ownership, pacing and verifier corrections.
- `test/m1/` - synthetic Python HTTP fixture, M1-only settings/resources, Java frontier/filter checks and Python evidence verifier/tests. Test Java sources are not application code and are compiled by the M1 helper, not Maven.
- `docs/m1-controlled.md` - M1 run command, evidence contract, observed local checks, retention and limits.
- `.gitignore` — ignores Maven output, IDE files, common secrets and local crawl data.
- `.codex/hooks.json`, `.entire/` — Entire/Codex integration configuration. Preserve existing settings.

## Dependencies and build

Declared direct dependencies in `pom.xml`:

| Dependency | Version | Role |
| --- | --- | --- |
| `stormcrawler-core` | 3.7.0 | Crawler components and protocols |
| `storm-client` | 2.8.9, provided | Storm runtime API; supplied by the Storm runtime |
| `flux-core` | 2.8.9 | Flux topology loading |
| `stormcrawler-tika` | 3.7.0 | Tika parsing integration |
| `stormcrawler-urlfrontier` | 3.7.0 | Frontier spout and status updater |
| `urlfrontier-client` | 2.4 | URLFrontier client, including seed injection |

Use JDK 17, Maven, and the external Storm/URLFrontier services appropriate to the chosen execution mode. There is no Maven wrapper. `compose.m0.yaml` provides a host-verified empty-frontier baseline using URLFrontier 2.4 and Storm 2.8.9-jre17. The Storm container logged Eclipse Adoptium Java 17.0.20; frontier Java version and image digests were not captured. Lifecycle plugins are pinned: clean 3.2.0, resources 3.3.1, surefire 3.6.0 and jar 3.5.1. From the root, the documented build is:

```sh
mvn clean package
```

The shade plugin packages `target/onionall-brawler-0.1.0-SNAPSHOT.jar` with Flux as the manifest main class. The README documents build and M0 commands. The host run verified `storm local ... --local-ttl 30` and URLFrontier on `localhost:7071` via a shared container network namespace. RocksDB initialized in temporary /crawldir/rocksdb; persistence across restarts is deliberately absent from M0 and remains M1 work. Seed injection has not been tested.

On 2026-10-30 Maven 3.9.16 was found in IntelliJ IDEA 2026.2.3 and used with the installed Oracle JDK 17.0.12. Online resolution failed for repo.maven.apache.org. A workspace-local copy of the existing Maven cache enabled a fresh offline clean package (BUILD SUCCESS, 27.757 seconds), also verified through scripts/build.ps1 (19.071 seconds). At those M0 builds no repository-owned tests existed or ran; the later standalone M1 tests run separately from Maven. `.runtime/` is ignored and contains disposable dependencies and logs; the host cache was not modified. Record the command, environment and outcome whenever verification is performed.

### M0 gaps and resolved references

- README now documents the project vision, actual status and missing Tor transport; the nonexistent Compose reference was replaced by compose.m0.yaml.
- No seeds are required or supplied for M0. Future permitted targets belong in ignored seeds.local.txt.
- tika-config.xml is supplied by stormcrawler-tika 3.7.0 and is present in the shaded JAR. Runtime logs confirm Tika loaded it and prepared successfully; document parsing remains unverified.
- Docker CLI 29.6.2 and Compose 5.3.1 are installed, but the desktop-linux engine pipe was unavailable. docker desktop start could not find its registry key; wsl --status returned Wsl/E_ACCESSDENIED. Do not infer unrestricted host status or modify approvals from these sandbox observations.
- Runtime recipe uses temporary RocksDB tmpfs, an internal network, no published ports and a 30-second local topology TTL. It deliberately provides no durable state; M1 must prove persistence separately.
- The host run verified images starting, all application components preparing/opening, empty-frontier gRPC interactions, topology activation and shutdown. M0 is complete for the local baseline. No M1 acceptance criterion is demonstrated by an empty run.
- Review found 0 ERROR/FATAL records and 34 WARN records: 6 unavailable cgroup metrics, 1 ZooKeeper connection-limit default and 27 shutdown waits that resolved. No blocking runtime defect was found; do not suppress warnings or change topology merely to make logs quiet.
- Agent Docker access remains denied, including post-run Compose ps/image inspect. User supplied the successful host run; container/network removal is user-reported, not independently inspected. Do not modify security settings to bypass this boundary. Preserve raw logs under ignored .runtime/: they contain runtime-generated ZooKeeper credential material and environment dumps. Commit only sanitized summaries/hashes.
- Image digests, clean-cache online build, Linux host reproduction and saved post-down inspection remain follow-ups, not claims made by M0.
- The user's commit `164c2ba` completed M0 versioning after the agent's staging attempt was denied. New M1 work is separate. Exclude unrelated .codex/config.toml and all .runtime/, target/ and Python cache content from commits. Do not bypass the Git access boundary.

## Configuration facts and constraints

The topology currently uses one worker and parallelism 1 for its components; `fetcher.threads.number` is 50, `topology.max.spout.pending` is 100, and the HTTP body limit is 65,536 bytes. The configured default revisit interval is 1,440 minutes and fetch-error interval is 120 minutes. These are configuration values, not demonstrated production behavior or safe limits for all targets. Tune conservatively for permitted tests.

HTTP and HTTPS use StormCrawler's OkHttp protocol implementation. No Tor proxy is configured in the project: onion traffic must not be attempted under the assumption that it is protected. The host filter has `ignoreOutsideDomain: true`, which may prevent the intended discovery across domains; assess actual filtering and domain handling before changing it. Regex filters reject several binary/media extensions and literal loopback and private IPv4 targets, but these rules alone are not a complete network boundary or content policy. `tika-config.xml` is dependency-supplied and packaged in the shaded JAR; Tika runtime initialization passed, but document parsing remains unverified. Internal Docker networking is configured, not a verified fail-closed Tor boundary.

## Direction and engineering guidelines

Keep discovery, fetching, parsing, scheduling, indexing, query APIs and presentation as distinct responsibilities with clear contracts. Prefer a local-first modular design and mature libraries over custom protocol or indexing implementations. Avoid premature microservices; consider distributed deployment after evidence warrants it. Ensure observable queues, crawl outcomes, errors, resource use and discovery effectiveness, and make state recovery explicit.

Architectural preferences, not implemented decisions: StormCrawler and Storm for crawling; URLFrontier for scheduling; Lucene or OpenSearch for full-text search; PostgreSQL for structured metadata if needed; Kotlin, Ktor and Koin for a future API; Compose Multiplatform for a desktop client; official Tor software for connectivity; Docker Compose for reproducible infrastructure. Select and document actual technologies when requirements and operational tradeoffs are understood.

Develop on Windows 11 and Omarchy/Arch Linux. Avoid platform-specific absolute paths in shared configuration; document shell-specific commands, runtime versions, service addresses and volume behavior. Keep credentials and local datasets out of Git. Preserve working configuration, inspect before changing, and update this file when verified architecture, commands or status change. Separate observed facts from assumptions and plans.

## Security and crawl boundaries

- Route all onion requests exclusively through the official Tor client. Fail closed when the transport is unavailable; never silently connect directly. Verify proxy routing and DNS behavior, redirects, retries and failure paths before any onion crawl.
- Treat remote content and metadata as untrusted. Do not execute downloaded scripts or binaries. Bound response size, processing time, concurrency and resource consumption.
- Crawl only authorized or publicly discoverable resources permitted to be indexed; observe applicable restrictions and access boundaries. Do not bypass authentication, CAPTCHA or access controls.
- Apply a content handling and filtering policy before persistence; avoid storing unlawful material or stolen personal data. Define retention, deletion and incident handling when storage is introduced.
- Do not assume the current URL filters prevent all access to local or private services. Design and verify network isolation and redirect validation as part of the transport boundary.

## Milestones and acceptance criteria

M0 is accepted based on the host-run evidence described above and in docs/m0-baseline.md. M1 is accepted for the controlled synthetic crawl and graceful recreation; M2–M8 remain open; no milestone is complete solely because configuration exists. Nimbus/supervisor/ZooKeeper messages in M0 belong to LocalCluster, not a verified distributed deployment. Tor and OpenSearch are not provisioned or verified.

| Milestone | Acceptance criteria |
| --- | --- |
| M0 — Foundation | Reproducible Java/Maven build; documented prerequisites and exact commands; verified topology startup and shutdown with required services; configuration and README gaps resolved or explicitly recorded. |
| M1 — Controlled crawling | An explicitly permitted test site is fetched, links are extracted, and crawl state survives a service restart using a documented persistent backend; rate limits and results are observable. |
| M2 — Tor transport | Official Tor client integration demonstrated; onion requests traverse the proxy, and unavailable Tor, redirects, DNS and retry paths fail closed under meaningful checks. |
| M3 — Onion discovery | Seeds are ingested, onion v3 addresses validated, permitted links extracted and deduplicated, and discovery metrics reported; cross-service filtering behavior is verified. |
| M4 — Search index | Collected, policy-approved documents are indexed and retrievable by keyword with relevance ranking and source metadata; indexing failures and updates are handled. |
| M5 — Application API | Kotlin/Ktor/Koin API exposes appropriate search, crawl management, statistics and configuration operations with documented contracts and access controls. |
| M6 — Desktop interface | Compose Multiplatform client supports practical search and exploration against the API on supported platforms. |
| M7 — Advanced research | Content changes and service relationships can be explored; semantic search and local AI assistance are optional and independently evaluated. |
| M8 — Scaling | Distributed Storm deployment and larger permitted crawls are evaluated using measured throughput, recovery, resource use and operational costs. |

## Current boundary

M0 and M1 are accepted. Do not begin M2 without a new request. Repeat M1 with `./scripts/m1.ps1 -JdkHome "$env:ProgramFiles/Java/jdk-17"`; each run creates a fresh project and retains its volume. Keep raw evidence ignored, never weaken assertions, and never infer Tor/search readiness from this fixture run.
