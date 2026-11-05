# Capability-aware upstream adapters

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Source-pinned StormCrawler URLFrontier Spout and status-updater adapters.

## RESPONSIBILITIES

Attach mandatory existing capability credentials to initial and recreated RPC streams without replacing upstream scheduling, backpressure or ACK behavior.

## IMPLEMENTED ARCHITECTURE

CapabilitySpout and CapabilityStatusUpdaterBolt derive from copied StormCrawler 3.7.0 implementations; class identity and CallCredentials wiring are the intentional differences. Archive subclass adds catalog observation before status storage.

## KEY COMPONENTS

`CapabilitySpout` and `CapabilityStatusUpdaterBolt`; FrontierCapability supplies credentials for each new RPC.

## DATA FLOW

Spout GetURLs -> leased tuples -> downstream ACK/fail handling. Updater status -> authenticated PutURLs -> existing confirmation/ACK flow; reconnect creates another authenticated stream.

## DEPENDENCIES

Exact upstream StormCrawler version in pom.xml, gRPC URLFrontier API and application admission credentials.

## INVARIANTS

GetURLs is a mutation because it acquires leases. Never retry without a token after file errors, rejection or reconnect. Preserve upstream scheduler/status ordering and backpressure.

## CHANGE GUIDELINES

Diff copied classes against the pinned upstream when upgrading. Cover missing/wrong credentials, reconnect, unchanged state/leases and legitimate status/lease behavior using the owning server.

## RELATED MODULES

[Credentials and graph](../../../../dev/onionall/brawler/admission/AGENTS.md); [Server authorization](../../../../../../../frontier-server/src/main/java/crawlercommons/urlfrontier/service/AGENTS.md); [Archive updater](../../../../dev/onionall/brawler/archive/AGENTS.md).

## KNOWN LIMITATIONS

Shared capability does not separate operator and worker authority. Stock legacy clients belong only to their separate fixtures, not the protected generated product.
