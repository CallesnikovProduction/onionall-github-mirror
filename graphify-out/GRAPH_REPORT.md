# Graph Report - onionall-brawler  (2026-11-25)

## Corpus Check
- 94 files · ~104,290 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1702 nodes · 4274 edges · 77 communities (32 shown, 45 thin omitted)
- Extraction: 92% EXTRACTED · 8% INFERRED · 0% AMBIGUOUS · INFERRED: 331 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `31ce1d7b`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- ResearchWorkflow
- RuntimeCommands.kt
- State
- InteractiveSession
- DistributedFrontierService.java
- ArchiveStore
- .discover
- ResearchExporter
- QueueMetadata
- render.rs
- ResearchCommands
- .service
- Onionall
- RocksDBService
- map
- QueueInterface
- AhmiaDiscoveryProvider
- crawlercommons.urlfrontier.Urlfrontier.URLItem
- URLQueue
- QueueWithinCrawl
- ResearchCatalog
- CapabilityStatusUpdaterBolt
- FailureObservation
- ResearchFiles
- FrontierReadback
- URLFrontierServer.java
- AhmiaDiscoveryProvider.kt
- TorPolicy
- PlanRepository
- .connection
- AdmissionClient
- ArchiveCommands
- AbstractFrontierService
- ThreadCommands
- ShardedRocksDBService.java
- ArchiveStatusUpdaterBolt.java
- .admit
- CapabilitySpout.java
- DurableDiscoveryFilter
- FrontierCapability
- .ArchiveContext
- JobStatusService
- ForumExtractor.kt
- CrawlRuntimePreparer
- ArchiveContext
- .print
- Transport
- .parse
- RpcFailure
- SnapshotRepository
- ArchiveSupport
- ObservationRepository
- com.fasterxml.jackson.databind.ObjectMapper
- .status
- .write
- CrawlService
- CrawlExecution
- State
- NativeContent.kt
- TorScopeFilter
- StreamObserver<AckMessage>
- .write

## God Nodes (most connected - your core abstractions)
1. `ArchiveStore` - 54 edges
2. `Onionall` - 47 edges
3. `AbstractFrontierService` - 45 edges
4. `QueueWithinCrawl` - 42 edges
5. `RocksDBService` - 36 edges
6. `ArchiveContext` - 36 edges
7. `ResearchWorkflow` - 34 edges
8. `ResearchCommands` - 34 edges
9. `QueueInterface` - 34 edges
10. `TorPolicy` - 33 edges

## Surprising Connections (you probably didn't know these)
- `ResearchTorTransport` --calls--> `Config`  [INFERRED]
  src/main/kotlin/dev/onionall/brawler/research/ResearchTorTransport.kt → src/main/kotlin/dev/onionall/brawler/cli/RuntimeCommands.kt
- `TorProtocol` --references--> `TorPolicy`  [EXTRACTED]
  src/main/kotlin/dev/onionall/brawler/tor/TorProtocol.kt → src/main/kotlin/dev/onionall/brawler/tor/TorPolicy.kt
- `ResearchCommands` --references--> `Candidates`  [EXTRACTED]
  src/main/kotlin/dev/onionall/brawler/cli/ResearchCommands.kt → src/main/kotlin/dev/onionall/brawler/cli/SessionCommands.kt
- `ResearchCommands` --references--> `Import`  [EXTRACTED]
  src/main/kotlin/dev/onionall/brawler/cli/ResearchCommands.kt → src/main/kotlin/dev/onionall/brawler/cli/SessionCommands.kt
- `ResearchCommands` --references--> `Select`  [EXTRACTED]
  src/main/kotlin/dev/onionall/brawler/cli/ResearchCommands.kt → src/main/kotlin/dev/onionall/brawler/cli/SessionCommands.kt

## Import Cycles
- None detected.

## Communities (77 total, 45 thin omitted)

### Community 0 - "ResearchWorkflow"
Cohesion: 0.08
Nodes (6): ForumPagination, ResearchThreads, Backend, ProviderCall, ResearchWorkflow, TypeReference

### Community 1 - "RuntimeCommands.kt"
Cohesion: 0.06
Nodes (13): OnionSmoke, Config, Doctor, Printer, Proxy, RuntimeCommands, TypeReference, Set (+5 more)

### Community 2 - "State"
Cohesion: 0.06
Nodes (30): CrawlWorker, OwnedChild, Assessment, Completion, COMPLETE, INCOMPLETE, UNKNOWN, JobStatus (+22 more)

### Community 3 - "InteractiveSession"
Cohesion: 0.07
Nodes (7): Activity, Brand, Dimensions, InteractiveSession, Writer, Size, Mascot

### Community 6 - ".discover"
Cohesion: 0.11
Nodes (6): DiscoveryReport, Link, Report, DiscoveryParseFilter, DiscoveryPlanner, Edge

### Community 7 - "ResearchExporter"
Cohesion: 0.10
Nodes (12): Approve, Base, Candidates, Discover, Export, Import, Pages, Select (+4 more)

### Community 8 - "QueueMetadata"
Cohesion: 0.05
Nodes (7): AdmissionLedger, AdmissionRpc, ServerInterceptor, ServerCall.Listener<>, ServerCall.Listener<>, AdmissionState, QueueMetadata

### Community 10 - "render.rs"
Cohesion: 0.10
Nodes (35): checks_version_and_capacity_without_writing_a_partial_frame(), onionall_content_render_v1(), overlapping_ranges_are_rejected_before_reading_or_writing(), batched_plain_text_preserves_crlf_unicode_and_the_empty_line_contract(), body(), bounded_seeded_mutations_never_panic_or_exceed_the_wire_limit(), Buffer, children() (+27 more)

### Community 11 - "ResearchCommands"
Cohesion: 0.13
Nodes (8): Backend, Files, Links, Plan, ResearchCommands, Results, Run, Search

### Community 15 - "Onionall"
Cohesion: 0.11
Nodes (5): Discover, Fetch, Onionall, Status, Transport

### Community 20 - "AhmiaDiscoveryProvider"
Cohesion: 0.12
Nodes (7): AhmiaDiscoveryProvider, DiscoveryBatch, DiscoveryCandidate, DiscoveryProvider, ProviderResponse, ProviderTransport, ResearchTorTransport

### Community 21 - "crawlercommons.urlfrontier.Urlfrontier.URLItem"
Cohesion: 0.12
Nodes (3): StreamObserver<URLItem>, MemoryFrontierService, MemoryURLItemIterator

### Community 23 - "QueueWithinCrawl"
Cohesion: 0.14
Nodes (3): QueueWithinCrawl, RocksDBURLItemIterator, SynchronizedStreamObserver

### Community 27 - "FailureObservation"
Cohesion: 0.16
Nodes (10): FailureObservation, Phase, ARCHIVE, FETCH, PARSE, ROBOTS, UNKNOWN, Source (+2 more)

### Community 29 - "FrontierReadback"
Cohesion: 0.23
Nodes (6): Counts, FrontierReadback, Page, Reservation, Row, SessionConflict

### Community 30 - "URLFrontierServer.java"
Cohesion: 0.07
Nodes (3): Hearbeat, HeartbeatListener, URLFrontierServer

### Community 33 - "PlanRepository"
Cohesion: 0.22
Nodes (4): ArchiveStoreNode, ArchiveQueryService, GraphRepository, PlanRepository

### Community 34 - ".connection"
Cohesion: 0.20
Nodes (5): DocumentRepository, TypeReference<List<Map<String, Object>>>, TypeReference<List<String>>, Capture, ResearchExportService

### Community 35 - "AdmissionClient"
Cohesion: 0.14
Nodes (4): AdmissionClient, TypeReference<Map<String, Object>>, OutputStream, RuntimeProbe

### Community 36 - "ArchiveCommands"
Cohesion: 0.32
Nodes (16): Approve, Archive, ArchiveCommands, Crawl, Errors, Export, ImportHtml, Page (+8 more)

### Community 38 - "ThreadCommands"
Cohesion: 0.24
Nodes (4): Crawl, Plan, Show, ThreadCommands

### Community 40 - "ArchiveStatusUpdaterBolt.java"
Cohesion: 0.18
Nodes (3): ArchiveStatusUpdaterBolt, CapabilityArchiveStatusUpdaterBolt, Pending

### Community 41 - ".admit"
Cohesion: 0.24
Nodes (7): AdmissionFailure, CrawlAdmissionService, FailureOutcome, CONFLICT, PROTOCOL_VIOLATION, REJECTED, UNKNOWN

### Community 46 - "JobStatusService"
Cohesion: 0.30
Nodes (3): FrontierObservation, JobStatusService, TypeReference<>

### Community 47 - "ForumExtractor.kt"
Cohesion: 0.31
Nodes (5): ArchiveForumExtractor, Capture, capture(), extract(), UriReferences

### Community 48 - "CrawlRuntimePreparer"
Cohesion: 0.18
Nodes (3): CrawlResources, CrawlRuntimePreparer, CrawlTopology

### Community 49 - "ArchiveContext"
Cohesion: 0.17
Nodes (3): ArchiveContext, Operation, ResearchSessions

### Community 51 - "Transport"
Cohesion: 0.20
Nodes (10): Transport, HTTP_ERROR, HTTP_OK, ONION_UNREACHABLE, PROTOCOL_CLOSED, REDIRECT, REQUEST_TIMEOUT, SCOPE_REJECTED (+2 more)

### Community 53 - "RpcFailure"
Cohesion: 0.25
Nodes (4): FailureKind, PROTOCOL, TRANSPORT, RpcFailure

### Community 55 - "SnapshotRepository"
Cohesion: 0.15
Nodes (3): BlobStore, SnapshotIdentity, SnapshotRepository

### Community 63 - "State"
Cohesion: 0.33
Nodes (6): State, HELD, PENDING, SCHEDULED_WAIT, UNKNOWN, UNSCHEDULED

### Community 64 - "NativeContent.kt"
Cohesion: 0.07
Nodes (7): decode(), FrameLimit, NativeContent, NativeContentException, Projection, NodeFilter, Rendered

## Knowledge Gaps
- **59 isolated node(s):** `MAX_UNITS`, `MAX_DEPTH`, `MAX_NODES`, `PUNCTUATION`, `Malformed` (+54 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 368 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **45 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Onionall` connect `Onionall` to `RuntimeCommands.kt`, `.print`, `.parse`, `.service`?**
  _High betweenness centrality (0.019) - this node is a cross-community bridge._
- **What connects `MAX_UNITS`, `MAX_DEPTH`, `MAX_NODES` to the rest of the system?**
  _59 weakly-connected nodes found - possible missing edges._
- **Should `ResearchWorkflow` be split into smaller, more focused modules?**
  _Cohesion score 0.07686116700201208 - nodes in this community are weakly interconnected._
- **Why does `CrawlService` connect `CrawlService` to `.status`?**
  _High betweenness centrality (0.012) - this node is a cross-community bridge._
- **Should `RuntimeCommands.kt` be split into smaller, more focused modules?**
  _Cohesion score 0.056329113924050635 - nodes in this community are weakly interconnected._
- **Why does `ArchiveStore` connect `ArchiveStore` to `PlanRepository`, `.connection`, `.close`, `ArchiveContext`, `FailureObservation`?**
  _High betweenness centrality (0.011) - this node is a cross-community bridge._
- **Should `State` be split into smaller, more focused modules?**
  _Cohesion score 0.05516431924882629 - nodes in this community are weakly interconnected._