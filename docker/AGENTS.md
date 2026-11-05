# Container implementation boundaries

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Infrastructure images and the focused Storm lifecycle dependency patch.

## RESPONSIBILITIES

Keep image provenance, service ownership and network/state boundaries explicit across root Compose files and local Docker implementations.

## IMPLEMENTED ARCHITECTURE

m2/ contains official packaged Tor/obfs4 launch and health/state helpers; storm-lifecycle/ contains the pinned Nimbus shutdown patch image. Root Compose recipes select distinct startup, legacy HTTP, Tor and archive contours.

## KEY COMPONENTS

`m2/`, `storm-lifecycle/`; root compose*.yaml files are callers, not interchangeable runtime contracts.

## DATA FLOW

Pinned image/build inputs + explicit Compose configuration -> scoped containers/networks/state -> selected runtime fixture or crawler.

## DEPENDENCIES

Docker/Compose, documented image digests, application and Frontier JARs, scripts/runners and test/ fixtures.

## INVARIANTS

No published service ports or direct egress added as a workaround. Preserve internal crawler networks, read-only roots, dropped capabilities, nosuid temporary mounts and persistent identities. JNI runtime /tmp execution needs must not silently become noexec.

## CHANGE GUIDELINES

Inspect every affected Compose consumer and UID/mount/health interaction. Use owned disposable fixtures; no down -v, prune or volume deletion. Keep bridge secrets out of image layers/logs.

## RELATED MODULES

[Tor image](m2/AGENTS.md); [Storm image](storm-lifecycle/AGENTS.md); [Runtime contours](../test/AGENTS.md).

## KNOWN LIMITATIONS

Container configuration alone does not prove a runtime network boundary. Historical root startup recipes are not generated-product qualification.
