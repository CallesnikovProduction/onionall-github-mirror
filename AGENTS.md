# Onionall Brawler — architecture navigator

## MODULE PURPOSE

JVM-first, local-first research crawler for permitted Tor onion sources: terminal
CLI, bounded discovery, HTML archive and local catalog search. Read this navigator
first, then the local AGENTS.md for the component being changed.

## RESPONSIBILITIES

Root instructions apply repository-wide. Nested AGENTS.md add narrower rules;
parent constraints still apply. Explicit user instructions take precedence.
Resolve contract conflicts before editing; local silence never overrides a guard.
Keep AGENTS.md about implemented architecture and durable instructions, not test
results, execution history or task status.

## IMPLEMENTED ARCHITECTURE

Java owns CLI/application services, crawl orchestration, archive and capability
adapters. Kotlin owns Tor transport, URL scope and local-test discovery adapters.
Maven compiles Kotlin before Java; both target Java 25. Preserve Java-callable
constructors and Storm reflection entrypoints. The separately built URLFrontier
candidate owns RocksDB scheduling and durable Admission. H2 is an embedded archive
catalog. Compose supplies infrastructure/fixtures, not another scheduler.

New application-level services, models, CLI commands and adapters use Kotlin.
Preserve correct existing Java; do not migrate it for language uniformity.
Java exceptions are limited to demonstrated integration/generated/build-tool needs.
Preserve owner Javadoc/KDoc and third-party authorship. New or substantially
rewritten project-owned source documents nontrivial contracts and attributes
Стефан Колесников and GPT-6.1 Sol for new work; preserve prior agent/upstream attribution.

Kotlin [ResearchCommands](src/main/kotlin/dev/onionall/brawler/cli/ResearchCommands.kt)
adds bounded plan/run/result/graph/file-candidate and local-search commands. Its Backend is wired
to existing Java CrawlService/ArchiveStore by ArchiveCommands; Kotlin does not
depend on Java sources at compile time. Research ID is the existing plan ID.
Kotlin [RuntimeCommands](src/main/kotlin/dev/onionall/brawler/cli/RuntimeCommands.kt)
edits only SOCKS host/port in the existing descriptor and probes SOCKS negotiation.
Canonical up retains that endpoint. Doctor does not prove Tor identity/bootstrap
or Frontier readiness; credentials and host networking remain outside its scope.

M6 [research sessions](src/main/kotlin/dev/onionall/brawler/research/AGENTS.md)
add actual authorized Ahmia form search, durable candidates/selection/approval,
bounded multi-seed coordination and local exports. A session owns separate existing
same-origin crawl plans; the sum of child page caps fits its immutable global cap.
Frontier remains scheduler/quota authority. Kotlin ResearchCatalog adds only
research_sessions_v1 in the existing H2 catalog through Java forwarding adapters.
File candidates are observations, not downloaded files. See [M6 contract](docs/m6-research.md)
and [M6 checkpoint](docs/engineering/m6-checkpoint.md) for current continuation.

M6.1 [forum research](docs/m61-forum-research.md) adds a separately approved
thread-only plan (`provider=m61:thread`), verified numeric pagination, conservative
DOM post extraction and readable exports. Frontier depth in that plan counts
pagination hops; ordinary site navigation retains its own scope/depth allocation.
Derived thread/post observations stay in the existing research document. Source
post IDs, internal observation IDs and page-text fallback are distinct. A persisted
paused run and unavailable live scheduler counters are separate observations.
Continuation/evidence: [M6.1 checkpoint](docs/engineering/m61-checkpoint.md).

Canonical local operation uses root `./onionall` ->
`scripts/runners/runtime.py` -> `compose.runtime.yaml`; see
[local runtime](docs/local-runtime.md). `./onionall build`, `test` and `build --images` use the containerized JDK 25
workflow in `docker/toolchain/`; prefer it when host Java is unavailable. It
verifies root/crawler POMs and builds the independent Frontier Maven project
using the image-extracted SHA-pinned baseline. The root POM declares no modules.
Maven caches live under ignored `.runtime/`; clean/down preserve runtime data.
Fixture mode has separate retained state and cannot run alongside canonical Tor
mode. The launcher supplies deployment defaults; JVM CLI owns explicit approval
and generated graph execution. Stop owned crawler containers before Frontier;
retain all volumes.

## KEY COMPONENTS

| Area | Responsibility and local instructions |
| --- | --- |
| [JVM application](src/AGENTS.md) | Mixed-language application and shared source boundaries |
| [Admission](src/main/java/dev/onionall/brawler/admission/AGENTS.md) | Crawl service, generated product graph, RPC and worker ownership |
| [Archive](src/main/java/dev/onionall/brawler/archive/AGENTS.md) | H2 catalog, immutable HTML objects and observations |
| [CLI](src/main/java/dev/onionall/brawler/cli/AGENTS.md) | Commands, offline workflows and terminal presentation |
| [Research](src/main/kotlin/dev/onionall/brawler/research/AGENTS.md) | Provider discovery, session/CAS persistence, approved child plans, document hints and exports |
| [Capability adapters](src/main/java/org/apache/stormcrawler/urlfrontier/AGENTS.md) | Source-pinned capability Spout/status updater |
| [Tor](src/main/kotlin/dev/onionall/brawler/tor/AGENTS.md) / [Discovery](src/main/kotlin/dev/onionall/brawler/discovery/AGENTS.md) | Fail-closed transport/scope and local-test discovery |
| [Resources](src/main/resources/AGENTS.md) | Default parser/filter resources and generated override boundary |
| [Frontier](frontier-server/AGENTS.md) | Owning server, durable state and conformance drivers |
| [Scripts](scripts/AGENTS.md) | Build, installation, verification, recovery and maintenance |
| [Docker](docker/AGENTS.md) | Tor infrastructure and pinned Storm lifecycle patch |
| [JVM tests](src/test/AGENTS.md) / [External fixtures](test/AGENTS.md) | Component tests and external runtime fixtures |
| [CI](.github/AGENTS.md) / [Maven wrapper](.mvn/AGENTS.md) | Reproducible verification and tool bootstrap |
| [Documents](docs/AGENTS.md) | Contracts, ADRs and evidence locations |

`.codex/` and `.entire/` contain agent/checkpoint integration settings; their
small configuration contour inherits this root rather than component rules.

## DATA FLOW

CLI plan/approval -> H2 plan -> CrawlService/CrawlExecution -> authenticated
Frontier admission/reservation -> generated worker -> CapabilitySpout leases ->
partition -> SimpleFetcher/TorProtocol -> JSoup -> StdOutIndexer.
DurableDiscoveryFilter reserves eligible outlinks before emission;
ArchiveParseFilter publishes HTML/catalog data. Fetch/parse/index status streams
reach the capability-aware archive status updater, then Frontier scheduling.
CLI status labels Frontier, worker and catalog observations separately.
Offline inspect/discover and assisted HTML import do not initiate crawling.

## DEPENDENCIES

Use the versions/checksums in [pom.xml](pom.xml),
[wrapper properties](.mvn/wrapper/maven-wrapper.properties) and
[Frontier build](frontier-server/README.md): Java 25, Kotlin, Storm,
StormCrawler, URLFrontier, gRPC, RocksDB, H2, JSoup and Picocli.
Use one matching JDK toolchain; do not downgrade to make checks run.
Prefer existing libraries and local components over new services or queues.

## INVARIANTS

- Frontier alone owns admission, immutable reservations, quota, scheduling,
  leases and retry dates. Preserve sync-WAL atomicity, replay idempotency,
  recovery rebuilding and server-side guards. H2 is not scheduling authority.
- Admission/Command and **all PutURLs/GetURLs** require the existing capability;
  GetURLs mutates leases. Admin mutations stay denied. Missing, malformed,
  unreadable or rejected capability must never cause anonymous fallback.
  Deploy compatible clients/server together; preserve credentials on reconnect.
- Onion requests use only the official Tor client: preserve ONLY mode, approved
  scope, remote target resolution, bounded calls/content/concurrency, robots,
  redirect validation and disabled DIRECT fallback/transparent transport retries.
  Regex filters and a local TCP listener do not prove isolation or readiness.
- Treat remote content/metadata as untrusted: no downloaded code execution,
  credential propagation or authentication/CAPTCHA/access-control bypass. Crawl
  only authorized or publicly discoverable, permitted-to-index sources. Apply
  content policy before persistence; no unlawful material or stolen personal data.
- Preserve archive/process identity, volumes, keys, catalogs and evidence.
  Ownership metadata is not OS process containment; uncertain liveness blocks
  new crawl start. Do not signal unknown processes or erase ownership to restart.
- No protobuf, persistent-schema, admission-contract, Tor-isolation or recovery
  architecture change without explicit review, compatibility and rollback.
  Do not add a second Frontier, scheduler, quota counter or authorization system.

## CHANGE GUIDELINES

- Begin with a bounded reproducer. Inspect actual callers, configuration, tests
  and persisted effects; make the smallest coherent change. Preserve assertions,
  public entrypoints, resource limits and Windows/Linux portability. Do not
  rewrite working components, expand scope or implement HOLD work without approval.
- Run relevant tests, lint/whitespace, diff and secret checks. Record commands,
  environment, real results and limitations in existing ledgers, not AGENTS.md.
  Historical evidence, exit zero, local fixtures and real Tor acceptance are
  distinct proofs. Never weaken assertions or suppress failures; do not repeat
  expensive failed network experiments without new evidence.
- Inspect branch, HEAD, worktree and remote before Git operations. Entire's
  standing policy permits meaningful verified atomic checkpoints and immediate
  ordinary pushes to existing origin during authorized development; the current
  user task can narrow/prohibit this. Follow its branch/publication limits.
  No unrelated remote, PR, merge, tag or release without authorization. Never
  force-push, rewrite history, reset/clean away work or overwrite user edits;
  investigate rejected pushes/conflicts. Preserve Entire/Codex settings, hooks
  and approvals; never disable them to bypass a restriction.
- Never commit secrets, credentials, private bridge lines/onion keys, raw runtime
  logs, volumes, datasets, build artifacts or unrelated user files. Raw Storm/
  ZooKeeper logs can contain credentials: publish sanitized summaries only.
  Keep private bridge files permission-restricted and mounted read-only.
- No destructive cleanup (`down -v`, volume pruning, data/identity deletion).
  Do not alter host VPN, DNS, proxy, routes, firewall, services or security settings,
  install system tools or bypass sandbox/approval boundaries. Preserve .gitignore,
  user untracked files and machine-specific configuration.

## RELATED MODULES

Read [canonical product topology](docs/adr/0004-canonical-product-topology.md),
[durable Admission](docs/m41-durable-admission.md),
[archive contract](docs/archive-mvp.md) and [script inventory](scripts/README.md).
Security, engineering and recovery ledgers hold evidence/history, not new authority.
Read the [Astra checkpoint](docs/engineering/astra-checkpoint.md) before continuing
the reconstruction; it records the active pass, actual evidence and next action.

## KNOWN LIMITATIONS

No global onion registry or exhaustive discovery guarantee. H2 catalog search and
StdOutIndexer are not a persistent full-text index. No application HTTP API or
desktop UI is implemented. Frontier RPC is plaintext with shared operator/worker
capability; do not infer TLS, separate roles or tenant isolation. Legacy Flux and
synthetic fixtures do not establish the generated product's Tor/admission guarantees.
