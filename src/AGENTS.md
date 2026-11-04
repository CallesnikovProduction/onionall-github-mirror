# JVM application

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Application source and resources for the mixed Java/Kotlin crawler and CLI.

## RESPONSIBILITIES

Keep orchestration, transport, archive and presentation in their existing packages; tools/OnionSmoke is a diagnostic entrypoint, not application orchestration.

## IMPLEMENTED ARCHITECTURE

Maven compiles Kotlin in process-sources before Java compilation; both use Java 25. The shaded application and thin CLI distribution have different dependency surfaces.

## KEY COMPONENTS

`main/java/dev/onionall/brawler/{admission,archive,cli,tools}`; `main/java/org/apache/stormcrawler/urlfrontier`; `main/kotlin/dev/onionall/brawler/{tor,discovery}`; `main/resources`; `test`.

## DATA FLOW

CLI -> Java services -> Kotlin protocol/filter interfaces and authenticated Frontier. Parser callbacks -> archive; scheduler feedback -> Frontier.

## DEPENDENCIES

Root pom.xml supplies StormCrawler/Storm APIs, Kotlin, H2, JSoup and gRPC. Frontier server sources/build are separate.

## INVARIANTS

Do not introduce a Java-to-Kotlin-to-Java compile cycle. Preserve JVM-visible constructors, interface signatures and no-arg protocol construction used by reflection.

## CHANGE GUIDELINES

Inspect Maven source order, generated job resources and thin-package contents when changing package boundaries. Put component regressions under src/test; keep infrastructure harnesses under test/.

## RELATED MODULES

[Build](../pom.xml); [JVM tests](test/AGENTS.md); [Packaging](../scripts/cli/AGENTS.md).

## KNOWN LIMITATIONS

Root Flux is a separate graph. Compiling the application does not build or qualify the owning Frontier server.
