# Crawl application and ownership

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Application service, durable crawl launch and observation of Frontier-owned work.

## RESPONSIBILITIES

Validate approved plans, authenticate RPC, generate the product graph/resources, own the worker launch and project status without becoming a scheduler.

## IMPLEMENTED ARCHITECTURE

CrawlService delegates to CrawlExecution. CrawlTopology builds CapabilitySpout -> partition -> SimpleFetcher -> JSoup -> StdOutIndexer, with URL-grouped archive status updates. Generated filters order DurableDiscoveryFilter before ArchiveParseFilter.

## KEY COMPONENTS

`AdmissionClient`, `FrontierCapability`, `CrawlResources`, `CrawlWorker`, `WorkerOwnership`, `WorkerObservation`, `JobStatus`, `JobStatusView` and `DurableDiscoveryFilter`.

## DATA FLOW

Archive plan/approval -> archive lock and ownership preparation -> resources and Frontier admission -> pinned worker/child -> readiness/status. Discovery reserves through Frontier; replay preserves original reservation and quota.

## DEPENDENCIES

ArchiveStore catalog; Kotlin TorPolicy/TorProtocol; pinned capability adapters; owning Frontier Admission RPC; existing Storm lease/status semantics.

## INVARIANTS

No anonymous fallback or H2 scheduling. Keep immutable origin/seeds/budget and authoritative parent depth. Ownership marker is archive-scoped/versioned/atomically published; PID alone or absent parent does not prove child death. Live/UNKNOWN or unproved legacy ownership blocks start; only identified owned processes may be signalled.

## CHANGE GUIDELINES

Check failure paths before/after admission, launch handoff, reconnect and restart. Preserve lock guard and identity/birth/scope checks. Add regression cases for unchanged quota/leases on rejection, replay and durable readback. Never erase markers or adopt unknown children to make restart work.

## RELATED MODULES

[Archive](../archive/AGENTS.md); [Owning server](../../../../../../../frontier-server/AGENTS.md); [Product contract](../../../../../../../docs/adr/0004-canonical-product-topology.md); [Product fixture](../../../../../../../test/admission/AGENTS.md).

## KNOWN LIMITATIONS

Worker exit is not completion. UNSCHEDULED does not retain a success/terminal reason; outcomes remain nullable. Ownership is not process containment; uncertain descendants remain fail-closed. Cached/catalog observations are not fresh Frontier authority.
