# Graph Report - onionall-brawler  (2026-11-15)

## Corpus Check
- 109 files · ~96,145 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 23 file(s) not represented in the graph (top: (none) 9, .patch 2, .toml 1)

## Summary
- 1645 nodes · 4081 edges · 73 communities (26 shown, 47 thin omitted)
- Extraction: 92% EXTRACTED · 8% INFERRED · 0% AMBIGUOUS · INFERRED: 331 edges (avg confidence: 0.85)
Token usage: unavailable for host/subagent semantic extraction; AST extraction uses no LLM.

## Community Hubs (Navigation)
- Research: workflow и извлечение
- Tor: транспорт и runtime
- Worker: ownership и наблюдение
- CLI: интерактивное представление
- Frontier: распределённые сервисы
- Crawl: сервисы и архив
- URL policy и discovery
- Research: команды и экспорт
- Admission: ledger и квоты
- Документированная архитектура
- Research: CLI backend
- CLI: прикладные adapters
- Onionall: вход в CLI
- RocksDB: очереди и recovery
- Конфигурация и admission imports
- Frontier: базовый сервис
- Ahmia: provider и transport
- Frontier: URL items
- Memory backend: очередь
- Frontier: queue iterators
- Research: каталог и CAS
- Ресурсы и текстовые документы
- Status updater: pending ACK
- Archive: наблюдения ошибок
- RocksDB: queue metadata
- Frontier: readback validation
- Frontier: server bootstrap
- HTML: parser и references
- Archive: context и repositories
- Archive: validation и query
- Admission: RPC client
- Archive: команды CLI
- Frontier: RPC callbacks
- Forum research: команды
- RocksDB: sharded backend
- Archive: status callbacks
- Admission: open reserve reconcile
- CapabilitySpout: gRPC channel
- Discovery: durable reservation
- Capability: credentials lifecycle
- Archive: immutable blob store
- Archive: transactions и export
- Archive: parser callback
- Worker: generated job resources
- Archive: transaction operations
- CLI: вывод и assisted import
- Frontier: heartbeat
- CLI: offline HTML inspection
- Admission: RPC failures
- Archive: HTML snapshots
- Archive: failure repository
- Archive: observation repository
- Archive: text document persistence
- Research: bundle rendering
- Status: channel reconnect
- CrawlExecution: entry methods
- Job state: observed values
- Text: UTF-8 decode

## God Nodes (most connected - your core abstractions)
- `ArchiveStore` — 54 связи.
- `Onionall` — 47 связей.
- `AbstractFrontierService` — 45 связей.

## Surprising Connections (you probably didn't know these)
- `StdOutIndexer` участвует в передаче статусов, хотя full-text индекс не реализован.
- `ArchiveContext` связывает archive repositories с borrowed `ResearchCatalog` в H2.

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Документированный канонический поток обработки** — src_main_java_org_apache_stormcrawler_urlfrontier_agents_capabilityspout, docs_adr_0004_canonical_product_topology_urlpartitionerbolt, docs_adr_0004_canonical_product_topology_simplefetcherbolt, docs_adr_0004_canonical_product_topology_jsoupparserbolt, docs_adr_0004_canonical_product_topology_stdoutindexer [EXTRACTED 1.00]
- **Blob publication → transaction commit → parser emission** — src_main_java_dev_onionall_brawler_archive_agents_blobstore, src_main_java_dev_onionall_brawler_archive_agents_archivecontext, src_main_java_dev_onionall_brawler_archive_agents_archiveparsefilter [EXTRACTED 1.00]

## Communities (73 total, 47 thin omitted)

### Community 0 - "Research: workflow и извлечение"
Cohesion: 0.06
Nodes (9): ArchiveHtmlMarkdown, Rendered, ForumPagination, ResearchFiles, ResearchThreads, Backend, ProviderCall, ResearchWorkflow (+1 more)

### Community 1 - "Tor: транспорт и runtime"
Cohesion: 0.06
Nodes (13): OnionSmoke, Config, Doctor, Printer, Proxy, RuntimeCommands, TypeReference, Set (+5 more)

### Community 2 - "Worker: ownership и наблюдение"
Cohesion: 0.06
Nodes (30): CrawlWorker, OwnedChild, Assessment, Completion, COMPLETE, INCOMPLETE, UNKNOWN, JobStatus (+22 more)

### Community 3 - "CLI: интерактивное представление"
Cohesion: 0.07
Nodes (7): Activity, Brand, Dimensions, InteractiveSession, Writer, Size, Mascot

### Community 4 - "Frontier: распределённые сервисы"
Cohesion: 0.08
Nodes (3): DistributedFrontierService, StreamObserver<URLItem>, SynchronizedStreamObserver

### Community 5 - "Crawl: сервисы и архив"
Cohesion: 0.05
Nodes (7): ArchiveStore, CrawlService, Execution, Execution, FrontierObservation, JobStatusService, TypeReference<>

### Community 6 - "URL policy и discovery"
Cohesion: 0.07
Nodes (9): DiscoveryReport, Link, Report, DiscoveryParseFilter, DiscoveryPlanner, Edge, DiscoveryScopeFilter, TorPolicy (+1 more)

### Community 7 - "Research: команды и экспорт"
Cohesion: 0.10
Nodes (12): Approve, Base, Candidates, Discover, Export, Import, Pages, Select (+4 more)

### Community 8 - "Admission: ledger и квоты"
Cohesion: 0.09
Nodes (6): AdmissionLedger, AdmissionRpc, ServerInterceptor, ServerCall.Listener<Q>, ServerCall.Listener<Q>, AdmissionState

### Community 10 - "Документированная архитектура"
Cohesion: 0.06
Nodes (38): Onionall Brawler — исследовательский crawler, JSoupParserBolt, Каноническая продуктовая Storm-топология, SimpleFetcherBolt, StdOutIndexer — статус без full-text индекса, URLPartitionerBolt, Frontier — отдельная сборка и durable scheduler, AdmissionRpc.guard — capability перед dispatch (+30 more)

### Community 11 - "Research: CLI backend"
Cohesion: 0.13
Nodes (8): Backend, Files, Links, Plan, ResearchCommands, Results, Run, Search

### Community 15 - "Onionall: вход в CLI"
Cohesion: 0.11
Nodes (6): Discover, Fetch, Inspect, Onionall, Status, Transport

### Community 20 - "Ahmia: provider и transport"
Cohesion: 0.13
Nodes (7): AhmiaDiscoveryProvider, DiscoveryBatch, DiscoveryCandidate, DiscoveryProvider, ProviderResponse, ProviderTransport, ResearchTorTransport

### Community 24 - "Research: каталог и CAS"
Cohesion: 0.19
Nodes (3): ResearchSessions, ResearchCatalog, OutputStream

### Community 27 - "Archive: наблюдения ошибок"
Cohesion: 0.09
Nodes (20): FailureObservation, Phase, ARCHIVE, FETCH, PARSE, ROBOTS, UNKNOWN, Source (+12 more)

### Community 29 - "Frontier: readback validation"
Cohesion: 0.21
Nodes (6): Counts, FrontierReadback, Page, Reservation, Row, SessionConflict

### Community 33 - "Archive: context и repositories"
Cohesion: 0.25
Nodes (6): ArchiveStoreNode, ArchiveContext, ArchiveQueryService, DocumentRepository, GraphRepository, PlanRepository

### Community 35 - "Admission: RPC client"
Cohesion: 0.15
Nodes (4): AdmissionClient, TypeReference<Map<String, Object>>, OutputStream, RuntimeProbe

### Community 36 - "Archive: команды CLI"
Cohesion: 0.32
Nodes (16): Approve, Archive, ArchiveCommands, Crawl, Errors, Export, ImportHtml, Page (+8 more)

### Community 38 - "Forum research: команды"
Cohesion: 0.24
Nodes (4): Crawl, Plan, Show, ThreadCommands

### Community 40 - "Archive: status callbacks"
Cohesion: 0.27
Nodes (3): ArchiveStatusUpdaterBolt, CapabilityArchiveStatusUpdaterBolt, Pending

### Community 41 - "Admission: open reserve reconcile"
Cohesion: 0.24
Nodes (7): AdmissionFailure, CrawlAdmissionService, FailureOutcome, CONFLICT, PROTOCOL_VIOLATION, REJECTED, UNKNOWN

### Community 46 - "Archive: transactions и export"
Cohesion: 0.23
Nodes (3): Operation, Capture, ResearchExportService

### Community 48 - "Worker: generated job resources"
Cohesion: 0.20
Nodes (3): CrawlResources, CrawlRuntimePreparer, CrawlTopology

### Community 53 - "Admission: RPC failures"
Cohesion: 0.25
Nodes (4): FailureKind, PROTOCOL, TRANSPORT, RpcFailure

### Community 63 - "Job state: observed values"
Cohesion: 0.33
Nodes (6): State, HELD, PENDING, SCHEDULED_WAIT, UNKNOWN, UNSCHEDULED

## Knowledge Gaps
- **60 isolated node(s):** `ServerCall.Listener<Q>`, `ServerCall.Listener<Q>`, `TRANSPORT`, `PROTOCOL`, `TypeReference<Map<String, Object>>` (+55 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 355 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **47 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
- Как `ArchiveStore` связывает crawl, research и repositories, сохраняя управление расписанием у Frontier?

## Область и проверка карты Onionall

- Основная карта: `onionall-map.html` — 13 подсистем, 92 исходных Java/Kotlin файла, классы и evidence каждой зависимости. Узел подробной карты представляет исходный файл и его основной/вложенные типы.
- Полный `graph.html` включает методы, внешние reference placeholders и документированные концепции. Его vis-network загружается с CDN; основная карта работает без сети.
- Корпус проекта: 410 файлов / 282210 слов; выбранный: 109 файлов / 96145 слов (92 кода + 17 архитектурных документов). Tests, scripts, Compose и исторические отчёты исключены намеренно. Подсистемы — области ответственности/package boundaries, не Maven reactor modules.
- Snapshot включает существующие незакоммиченные изменения и untracked production sources. Production code, `.gitignore` и история Git этой задачей не изменялись. `graphify-out/` добавлен в рабочее дерево и виден в git status.
- Все 17 документов прочитаны до конца; coverage проверен по фактическому числу строк.
- **Ограничение AST:** ArchiveTextDocuments.kt и ForumExtractor.kt разобраны частично; это предупреждения parser grammar, не вывод о компилируемости исходников. Исходные файлы не изменялись.
- **Health warning:** raw extraction имеет 629 edges с отсутствующим явным node declaration, 29 self-loops и 192 повторных directed endpoint pairs. Graphify materializes reference endpoints; DiGraph объединяет однонаправленные пары и может терять отдельные relationship/location attributes. Исходные evidence сохранены в `extraction.json`, диагностика — `health.json`; основная карта сохраняет evidence массивом и использует только разрешённые внутренние endpoints.
- **Проверенная ошибка разрешения имён:** из производного графа удалены две ложные INFERRED calls: ResearchTorTransport → RuntimeCommands.Config (фактически org.apache.storm.Config) и ForumExtractor.extract → HtmlFiles.parse (фактически локальный Kotlin parse). Аудит: `rejected-edges.json`. Остальные INFERRED candidates в полном графе остаются предположениями; основная карта исключает их все.
- Семантические связи описывают документацию; AST — код. Предложенный archive-fault FAIL/retry не считается реализованным.
- Token cost: AST = 0 LLM tokens; host/subagent semantic tokens = **неизвестно** (инструмент не возвращает usage). Нули в служебных graphify counters — placeholders, не измерение и не утверждение бесплатности semantic extraction.

## Verification

- 92 source-file SHA-256 values match the snapshot; all internal edge endpoints and evidence line numbers validate.
- Browser: overview, module/class search, dependency inspector and pipeline navigation checked.
- Application build/tests not run: this task adds graph artifacts only. Existing `git diff --check` reports a pre-existing trailing whitespace at RuntimeProbe.java:51; it was left untouched.
- Graphify benchmark (estimate): 82250 words / ~109666 naive tokens; ~1609 tokens per graph query; 68.2x reduction. Benchmark uses its own source-word accounting, distinct from detection corpus sizing.
