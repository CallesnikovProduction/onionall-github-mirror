# Security review boundary

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Evidence-backed review of security contracts and their limitations.

## RESPONSIBILITIES

Maintain SECURITY-REVIEW.md and link concrete source, fixtures, persisted effects and remaining acceptance gates.

## IMPLEMENTED ARCHITECTURE

Review separates authorization, transport, admission/recovery and deployment risks; execution details live in the existing engineering ledger.

## KEY COMPONENTS

`SECURITY-REVIEW.md`; related engineering/security-execution-ledger.md and architecture contracts.

## DATA FLOW

Hypothesis -> source/caller analysis -> isolated owned fixture -> state/restart proof -> classification/impact -> approved minimal remediation -> relevant regression evidence.

## DEPENDENCIES

Server AdmissionRpc/AdmissionLedger, capability clients, TorProtocol, worker ownership and controlled regression harnesses.

## INVARIANTS

RPC OK is not persisted-state proof. Preserve rejection without metadata/lease/quota mutation, valid-client compatibility, no anonymous fallback and explicit security-versus-product classification. Never include tokens or real private destinations.

## CHANGE GUIDELINES

Record severity, confidence, affected symbols, exact conditions, current evidence and limits in review/ledger. Do not declare audit closure from partial component evidence or imply reports authorize code/publication.

## RELATED MODULES

[Execution evidence](../engineering/security-execution-ledger.md); [Authorization](../../frontier-server/src/main/java/crawlercommons/urlfrontier/service/AGENTS.md); [Transport](../../src/main/kotlin/dev/onionall/brawler/tor/AGENTS.md).

## KNOWN LIMITATIONS

Plaintext RPC, shared capability and environment-dependent acceptance need separate analysis; local fixtures do not prove external deployment reachability.
