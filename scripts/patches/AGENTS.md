# Pinned dependency patch builds

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Reproducible assembly of the narrowly patched Storm lifecycle image.

## RESPONSIBILITIES

Verify exact source/base inputs and apply only the existing Nimbus cleanup patches.

## IMPLEMENTED ARCHITECTURE

build-storm-lifecycle.sh drives docker/storm-lifecycle using exact version/hash inputs; the patch serializes cleanup ownership and drains running cleanup before shutdown resources close.

## KEY COMPONENTS

`build-storm-lifecycle.sh`; Dockerfile and two patch files in docker/storm-lifecycle.

## DATA FLOW

Pinned base/source -> checksum verification -> exact patch application -> rebuilt lifecycle classes/image -> dedicated lifecycle probes.

## DEPENDENCIES

Docker, documented cached inputs and build toolchain; Nimbus probe sources under test/storm-lifecycle.

## INVARIANTS

No fuzzy patching, blind dependency upgrade, log whitelisting or assertion suppression. Preserve upstream licensing/provenance and all unrelated Storm behavior.

## CHANGE GUIDELINES

Inspect source/hash/patch compatibility and run the actual lock/drain probes. Do not claim an image is equivalent solely because it builds.

## RELATED MODULES

[Image inputs](../../docker/storm-lifecycle/AGENTS.md); [Lifecycle probes](../../test/storm-lifecycle/AGENTS.md).

## KNOWN LIMITATIONS

This patch concerns Nimbus shutdown ordering, not worker process containment, Tor transport or application scheduling.
