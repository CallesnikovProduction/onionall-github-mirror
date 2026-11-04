# Application JVM regressions

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

JUnit tests for application services, CLI, transport, archive and ownership.

## RESPONSIBILITIES

Use disposable catalogs/objects, loopback fixtures and explicitly owned subprocesses; assert state changes and rejection invariants, not only return codes.

## IMPLEMENTED ARCHITECTURE

Package-aligned Java tests exercise Java and Kotlin application classes through Maven. Nested JVM tests use Java argument files to handle long Windows classpaths.

## KEY COMPONENTS

`java/dev/onionall/brawler/{admission,archive,cli,discovery,tor}` and capability-adapter tests under `java/org/apache/stormcrawler/urlfrontier`.

## DATA FLOW

Controlled inputs -> component/loopback/process operation -> bounded assertions -> owned cleanup. Durable server proofs are separate Frontier conformance drivers.

## DEPENDENCIES

JUnit/Surefire, matching JDK tools and application classes; platform-sensitive tests must report genuine unavailable prerequisites.

## INVARIANTS

Never contact external onion services, signal unknown PIDs or weaken assertions/deadlines for a platform. Fake RPC responses are not persisted-state evidence. Characterizations of unsafe behavior are not successful security regression gates.

## CHANGE GUIDELINES

Run the smallest affected tests then relevant regressions. Preserve Windows argument-file launch behavior and process birth/ownership checks. Keep generated evidence/build outputs out of Git.

## RELATED MODULES

[External runtime fixtures](../../test/AGENTS.md); [Frontier durability](../../frontier-server/src/test/AGENTS.md); [Verification runner](../../scripts/runners/AGENTS.md).

## KNOWN LIMITATIONS

Unit tests do not qualify Docker, packaged PTY behavior, real Tor routing or the generated end-to-end product graph.
