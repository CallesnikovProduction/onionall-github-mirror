# Local runtime recovery

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Bounded supervision and fail-closed recovery of an explicitly owned local M2 runtime.

## RESPONSIBILITIES

Qualify transport failure, quarantine consumers, restart only the identified gateway and resume only after actual required health; preserve all persistent identity/state.

## IMPLEMENTED ARCHITECTURE

recover_tor_gateway.py creates an exclusive recovery journal and permits one qualified SOCKS-connect recovery cycle. supervise_local_crawl.py owns bounded crawler batches and at most one recovery; neither is a distributed Storm scheduler.

## KEY COMPONENTS

`recover_tor_gateway.py`, `supervise_local_crawl.py`.

## DATA FLOW

Owned project/evidence identity -> qualifying detection -> consumer quarantine -> same gateway restart -> health/readiness -> controlled resume or retained quarantine.

## DEPENDENCIES

Compose M2 service names, Docker ownership labels, structured probes under test/m2 and prebuilt runtime artifacts.

## INVARIANTS

Do not reseed URLs or rewrite Frontier metadata. Preserve gateway identity/pinned endpoint, volumes/onion keys and evidence. Never alter host DNS/VPN/routes/firewall, repeatedly restart, or call assisted recovery spontaneous.

## CHANGE GUIDELINES

Test detection rejection, partial quarantine, health failure, restart attribution, retained state and signal cleanup. Unknown ownership or readiness must prevent resume; do not broaden cleanup selectors.

## RELATED MODULES

[Runtime](../../docker/m2/AGENTS.md); [Recovery fixtures](../../test/m2/AGENTS.md); [Archive worker ownership](../../src/main/java/dev/onionall/brawler/admission/AGENTS.md).

## KNOWN LIMITATIONS

Log interpretation remains diagnostic. Gateway restart cannot prove every consumer recovered; these scripts do not provide OS process containment or distributed worker recovery.
