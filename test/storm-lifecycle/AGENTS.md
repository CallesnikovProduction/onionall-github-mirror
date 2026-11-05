# Nimbus lifecycle probes

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Focused real-class probes for Nimbus cleanup lock and shutdown draining.

## RESPONSIBILITIES

Verify the dependency patch fixes its ordering contract with a meaningful baseline control.

## IMPLEMENTED ARCHITECTURE

Plain-Java probes load the selected Storm classes and exercise cleanup serialization and wait-before-resource-close behavior.

## KEY COMPONENTS

`NimbusCleanupLockProbe.java`, `NimbusCleanupShutdownProbe.java`.

## DATA FLOW

Pinned baseline/candidate classes -> controlled concurrent cleanup/shutdown -> lock/drain assertions.

## DEPENDENCIES

Matching Storm/JDK classpath and patched image/build inputs.

## INVARIANTS

Do not replace synchronization assertions with log filtering, sleeps alone or process exit zero. Preserve exact candidate/baseline identity.

## CHANGE GUIDELINES

Run both probes for lifecycle patch/build changes and retain bounded deadlines. Keep dependency fixes separate from product process ownership.

## RELATED MODULES

[Patch](../../docker/storm-lifecycle/AGENTS.md); [Builder](../../scripts/patches/AGENTS.md).

## KNOWN LIMITATIONS

These probes do not prove child-tree containment, arbitrary worker recovery or network behavior.
