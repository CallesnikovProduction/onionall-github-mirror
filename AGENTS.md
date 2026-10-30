# Onionall Brawler — project context

## Purpose

Onionall Brawler is an open-source-oriented, JVM-first project to build an independent search engine for publicly discoverable and permitted-to-index Tor onion services. Its intended capabilities include seed-based discovery, recurring crawling, a searchable corpus, source metadata, a graph of relationships between services, and a research interface. Optional semantic search, summaries and AI-assisted research may come later; the core search system must work without AI.

Tor v3 has no global registry of onion services. Complete enumeration is not a realistic guarantee. Measure discovery and coverage of known, permitted sources instead of claiming comprehensive coverage.

## Evidence and current state

This is a bootstrap-stage project generated from the Apache StormCrawler 3.7.0 Maven archetype, not yet an onion search engine. The committed repository contains a Flux topology and configuration, not application-specific Java or Kotlin source code. The topology declares URLFrontier input and status updates, fetching, sitemap/feed and HTML parsing, optional Tika parsing, and `StdOutIndexer` output. These are configured components; an end-to-end crawl, persistent frontier, search index and topology execution have not been verified in this session. `StdOutIndexer` is not a searchable index.

There is no configured Tor transport or fail-closed guarantee, onion v3 validation or autonomous cross-service discovery, search API, desktop interface, graph storage, AI integration, or repository-owned test suite. Do not describe proposed components as implemented.

The user reports that the initial Maven build succeeded, Entire is enabled, Codex hooks were approved, and checkpoints sync to `origin`. On the user's host `entire doctor` reported installed hooks and approval records, and `entire status` showed Codex. A sandboxed session may not be able to read `.codex` or Git internals, so its diagnostic can differ from the host. Check actual host status when relevant; do not modify hook approvals to work around sandbox restrictions.

## Repository map

- `pom.xml` — Maven project `dev.onionall.brawler:onionall-brawler:0.1.0-SNAPSHOT`, Java source/target 17 and shaded JAR configuration.
- `crawler.flux` — Storm Flux topology, component parallelism and stream wiring; loads StormCrawler defaults and `crawler-conf.yaml`.
- `crawler-conf.yaml` — fetcher, URLFrontier, parser, scheduling and metrics settings.
- `src/main/resources/` — URL, regex, parse and JSoup filter configuration. No application source files are currently present.
- `README.md` — archetype instructions; see known gaps below.
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

Use a JDK compatible with Java 17, Maven, and the external Storm/URLFrontier services appropriate to the chosen execution mode. There is no Maven wrapper or Compose file in the repository. From the root, the documented build is:

```sh
mvn clean package
```

The shade plugin packages `target/onionall-brawler-0.1.0-SNAPSHOT.jar` with Flux as the manifest main class. The README gives seed injection and `storm local` commands; validate them in the target environment before relying on them. A URLFrontier service on `localhost:7071` is configured, but its presence, storage backend and persistence across restarts are not established by this repository.

The initial build succeeded according to the user. In the documentation session Maven was unavailable on the agent's PATH, so the build and tests were not rerun; existing files under `target/` are not proof of a fresh successful build. Record the command, environment and outcome whenever verification is performed.

### README gaps to resolve during M0

- It claims a `docker-compose.yaml` is provided, but no Compose file is tracked or present.
- It references `seeds.txt`, which is not supplied; create a local seed file with explicitly permitted URLs when testing.
- It describes an archetype crawler and does not explain the onion-search vision, Tor limitations, current transport risks or project status.
- Commands and external service setup have not yet been verified end to end. The example Docker run alone does not establish durable frontier storage.

## Configuration facts and constraints

The topology currently uses one worker and parallelism 1 for its components; `fetcher.threads.number` is 50, `topology.max.spout.pending` is 100, and the HTTP body limit is 65,536 bytes. The configured default revisit interval is 1,440 minutes and fetch-error interval is 120 minutes. These are configuration values, not demonstrated production behavior or safe limits for all targets. Tune conservatively for permitted tests.

HTTP and HTTPS use StormCrawler's OkHttp protocol implementation. No Tor proxy is configured in the project: onion traffic must not be attempted under the assumption that it is protected. The host filter has `ignoreOutsideDomain: true`, which may prevent the intended discovery across domains; assess actual filtering and domain handling before changing it. Regex filters reject several binary/media extensions and literal loopback and private IPv4 targets, but these rules alone are not a complete network boundary or content policy. `tika-config.xml` is referenced in configuration but is not present in the repository; determine whether a dependency supplies it and whether the Tika path works during M0.

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

All milestones are proposed; none is complete solely because configuration exists.

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

## Immediate next step

Establish a reproducible M0 runtime baseline: verify JDK 17 and Maven, build the existing project, provision a compatible URLFrontier and Storm runtime, then start and stop the unchanged Flux topology and capture the commands, logs and service prerequisites. Investigate the missing Compose and Tika configuration references while doing so. Use no public or onion seeds for this baseline; M1 will introduce a deliberately permitted test target and prove durable state. Update the README and this status after obtaining evidence.
